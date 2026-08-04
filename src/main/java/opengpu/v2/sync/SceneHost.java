package opengpu.v2.sync;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.MessageCodec;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.SnapshotCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.ServerScene;

/**
 * Server-side per-scene sync driver: owns the watcher subscription set and turns a
 * {@link ServerScene} into wire traffic per the design's rules —
 *
 * - one sealed batch per tick, broadcast to every subscribed watcher (encoded only when
 *   someone is watching; sealing — and the seq advance — happens regardless);
 * - an idle heartbeat (seq-only probe) after {@code heartbeatInterval} delta-less ticks, and
 *   on subscribe (the "you are at seq N" re-entry check);
 * - ALL large responses are amplification-defended: snapshot AND resource-body requests are
 *   rate-limited per watcher, deduplicated, deferred to the tick boundary, and served from
 *   caches (snapshot per seq; body envelopes per resource, identity-checked). Bodies are
 *   additionally budgeted to {@code bodiesPerWatcherPerTick} sends per watcher per tick.
 * - {@link #destroy()} broadcasts MSG_SCENE_GONE so mirrors evict instead of retrying
 *   forever against a dead scene.
 *
 * Rate-limit clocks come from the host's own tick counter — inbound handlers pass no tick,
 * so an adapter cannot poison the limiter with a wrong time source. Rate-limit stamps
 * survive unsubscribe/resubscribe (range thrash and relog cannot reset the floor); they are
 * released only by {@link #evictWatcher} on player disconnect.
 *
 * Thread contract: single-threaded under the owner's scene lock, like ServerScene itself.
 */
public final class SceneHost {
	private final ServerScene scene;
	private final SceneTransport transport;
	private final int heartbeatInterval;
	private final int snapshotMinIntervalTicks;
	/** Per-(watcher, resource) re-serve floor. Independent of the snapshot floor. */
	private final int bodyMinIntervalTicks;
	private final int bodiesPerWatcherPerTick;

	private final LinkedHashSet<String> watchers = new LinkedHashSet<String>();
	private final Map<String, Long> lastSnapshotServe = new HashMap<String, Long>();
	private final Map<String, Map<Integer, Long>> lastBodyServe = new HashMap<String, Map<Integer, Long>>();
	private final LinkedHashSet<String> pendingSnapshotRequests = new LinkedHashSet<String>();
	private final Map<String, LinkedHashSet<Integer>> pendingBodyRequests = new LinkedHashMap<String, LinkedHashSet<Integer>>();

	private long lastTick;
	private int idleTicks;
	private boolean destroyed;
	private int cachedSnapshotSeq;
	private int cachedSnapshotManifestGen = -1;
	private byte[] cachedSnapshotEnvelope;
	private final Map<Integer, CachedBody> bodyEnvelopeCache = new HashMap<Integer, CachedBody>();

	private static final class CachedBody {
		int version;
		long hash;
		byte[] envelope;
	}

	public SceneHost(ServerScene scene, SceneTransport transport,
			int heartbeatInterval, int snapshotMinIntervalTicks, int bodiesPerWatcherPerTick) {
		// Default body floor of 20 ticks: often enough that a streaming texture recovers
		// within a second, rare enough to stay an amplification defence.
		this(scene, transport, heartbeatInterval, snapshotMinIntervalTicks, 20, bodiesPerWatcherPerTick);
	}

	public SceneHost(ServerScene scene, SceneTransport transport, int heartbeatInterval,
			int snapshotMinIntervalTicks, int bodyMinIntervalTicks, int bodiesPerWatcherPerTick) {
		this.scene = scene;
		this.transport = transport;
		this.heartbeatInterval = heartbeatInterval;
		this.snapshotMinIntervalTicks = snapshotMinIntervalTicks;
		this.bodyMinIntervalTicks = bodyMinIntervalTicks;
		this.bodiesPerWatcherPerTick = bodiesPerWatcherPerTick;
	}

	public ServerScene scene() {
		return scene;
	}

	public void subscribe(String watcherKey) {
		if (destroyed)
			return;
		watchers.add(watcherKey);
		transport.sendToWatcher(watcherKey, heartbeatEnvelope());
	}

	public void unsubscribe(String watcherKey) {
		watchers.remove(watcherKey);
		pendingSnapshotRequests.remove(watcherKey);
		pendingBodyRequests.remove(watcherKey);
		if (watchers.isEmpty()) {
			// Nobody left: release the potentially large cached payloads.
			cachedSnapshotEnvelope = null;
			bodyEnvelopeCache.clear();
		}
	}

	/** Full removal on player disconnect: subscription AND rate-limit history. */
	public void evictWatcher(String watcherKey) {
		unsubscribe(watcherKey);
		lastSnapshotServe.remove(watcherKey);
		lastBodyServe.remove(watcherKey);
	}

	public boolean isSubscribed(String watcherKey) {
		return watchers.contains(watcherKey);
	}

	/** Scene destroyed: tell every mirror to evict, then go inert. */
	public void destroy() {
		byte[] envelope = MessageCodec.envelope(MessageCodec.MSG_SCENE_GONE,
				MessageCodec.encodeSceneGone(new MessageCodec.SceneGone(scene.sceneId)));
		for (String watcher : watchers) {
			transport.sendToWatcher(watcher, envelope);
		}
		watchers.clear();
		pendingSnapshotRequests.clear();
		pendingBodyRequests.clear();
		cachedSnapshotEnvelope = null;
		bodyEnvelopeCache.clear();
		destroyed = true;
	}

	public boolean isDestroyed() {
		return destroyed;
	}

	/**
	 * Save-boundary helper: seal the pending batch and broadcast it, so persistence can
	 * encode at a true batch boundary WITHOUT desyncing watchers. Call under the scene lock
	 * immediately before ScenePersistence.encodeStructure. The extra mid-tick batch is a
	 * sanctioned exception to one-batch-per-tick (rare, small, and mirrors handle
	 * consecutive seqs within a tick fine). Never seal-and-discard instead: staged deltas
	 * are already applied to server state, so discarding the batch silently desyncs every
	 * mirror with perfect seq continuity.
	 */
	public void saveBoundary() {
		SceneBatch batch = scene.sealBatch();
		if (batch != null) {
			if (!watchers.isEmpty()) {
				byte[] envelope = MessageCodec.envelope(MessageCodec.MSG_BATCH, BatchCodec.encode(batch));
				// A save-boundary seal is a real batch on the real wire, so it counts. Leaving it
				// out made saves invisible to the measurement while still costing bandwidth --
				// and a save is exactly when a scene's largest batch tends to go out.
				stats.onBatch(envelope.length, batch.deltas.size(), watchers.size());
				for (String watcher : watchers) {
					transport.sendToWatcher(watcher, envelope);
				}
			}
			idleTicks = 0;
		}
	}

	/**
	 * Wire-cost counters for this scene. Plain longs mutated under the scene lock the callers
	 * already hold; see SceneStats for why nothing here is timed or buffered.
	 */
	private final opengpu.v2.stats.SceneStats stats = new opengpu.v2.stats.SceneStats();

	public opengpu.v2.stats.SceneStats stats() {
		return stats;
	}

	/** Called once per server tick, after the component layer's mutations, under the scene lock. */
	public void tick(long currentTick) {
		lastTick = currentTick;
		if (destroyed)
			return;
		scene.setCurrentTick(currentTick);
		// Counted here, unconditionally and before the branches below, because the branches do
		// NOT cover every tick: sealing a batch with no watchers sends nothing, and a heartbeat
		// tick is neither a batch nor idle. Deriving the divisor from those outcomes dropped
		// both cases and inflated every per-tick figure.
		stats.onTick();
		SceneBatch batch = scene.sealBatch();
		if (batch != null) {
			if (!watchers.isEmpty()) {
				byte[] payload = BatchCodec.encode(batch);
				byte[] envelope = MessageCodec.envelope(MessageCodec.MSG_BATCH, payload);
				// Measured on the ENVELOPE, not the payload: the envelope is what the transport
				// hands to the network, and the header is not free. Watcher count is passed
				// along because one encoding is sent to each of them -- the server pays for it
				// once and the network pays per watcher, and only recording the former would
				// understate a populated server by exactly that factor.
				stats.onBatch(envelope.length, batch.deltas.size(), watchers.size());
				for (String watcher : watchers) {
					transport.sendToWatcher(watcher, envelope);
				}
			}
			idleTicks = 0;
		} else if (!watchers.isEmpty() && ++idleTicks >= heartbeatInterval) {
			byte[] envelope = heartbeatEnvelope();
			stats.onHeartbeat(watchers.size());
			for (String watcher : watchers) {
				transport.sendToWatcher(watcher, envelope);
			}
			idleTicks = 0;
		} else {
			// A tick that produced nothing. Counted because it is the divisor that turns batch
			// size into bytes-per-tick, and those two figures argue for opposite cap changes.
			stats.onIdleTick();
		}
		// Snapshots only exist at batch boundaries — serve queued requests now.
		if (!pendingSnapshotRequests.isEmpty()) {
			byte[] envelope = snapshotEnvelope();
			stats.onSnapshot(envelope.length, pendingSnapshotRequests.size());
			for (String watcher : pendingSnapshotRequests) {
				if (watchers.contains(watcher)) {
					lastSnapshotServe.put(watcher, currentTick);
					transport.sendToWatcher(watcher, envelope);
				}
			}
			pendingSnapshotRequests.clear();
		}
		servePendingBodies(currentTick);
	}

	/**
	 * Inbound resync request. No tick parameter: the limiter clock is {@link #tick}'s.
	 * Unsubscribed requesters are ignored (the watch check the design requires).
	 */
	public void onResyncRequest(String watcherKey) {
		if (!watchers.contains(watcherKey))
			return;
		Long lastServe = lastSnapshotServe.get(watcherKey);
		if (lastServe != null && lastTick - lastServe < snapshotMinIntervalTicks)
			return;
		pendingSnapshotRequests.add(watcherKey);
	}

	/** Inbound resource-body request: deduped, rate-limited, served at the tick boundary. */
	public void onResourceRequest(String watcherKey, int epoch, int resId) {
		if (!watchers.contains(watcherKey))
			return;
		// Aimed at a dead incarnation: the bytes would be discarded on arrival.
		if (epoch != scene.epoch())
			return;
		Map<Integer, Long> serves = lastBodyServe.get(watcherKey);
		if (serves != null) {
			Long lastServe = serves.get(resId);
			// A body floor of its own, NOT the snapshot interval: a mutable texture
			// legitimately needs re-fetching far more often than a scene needs resyncing,
			// and reusing the 100-tick snapshot floor would stall a streaming texture for
			// five seconds after any single missed write.
			if (lastServe != null && lastTick - lastServe < bodyMinIntervalTicks)
				return;
		}
		LinkedHashSet<Integer> pending = pendingBodyRequests.get(watcherKey);
		if (pending == null) {
			pending = new LinkedHashSet<Integer>();
			pendingBodyRequests.put(watcherKey, pending);
		}
		pending.add(resId);
	}

	private void servePendingBodies(long currentTick) {
		if (pendingBodyRequests.isEmpty())
			return;
		Iterator<Map.Entry<String, LinkedHashSet<Integer>>> watcherIter =
				pendingBodyRequests.entrySet().iterator();
		while (watcherIter.hasNext()) {
			Map.Entry<String, LinkedHashSet<Integer>> entry = watcherIter.next();
			String watcher = entry.getKey();
			if (!watchers.contains(watcher)) {
				watcherIter.remove();
				continue;
			}
			LinkedHashSet<Integer> pending = entry.getValue();
			Iterator<Integer> ids = pending.iterator();
			int served = 0;
			while (ids.hasNext() && served < bodiesPerWatcherPerTick) {
				int resId = ids.next();
				byte[] envelope = bodyEnvelope(resId);
				// REMOVE ONLY ON A DECIDED OUTCOME — served, or permanently unservable.
				//
				// A null envelope means freed, not a texture, or no bytes server-side: none of
				// those can become servable by waiting, so dropping the request is correct and
				// the client's resync path covers it. Anything DEFERRED must stay pending.
				//
				// Today the only deferral is the per-tick count, and the loop condition stops
				// before the next ids.next(), so nothing is lost. That safety is incidental,
				// though: the design's outstanding body BYTE budget defers a specific id for
				// its size, and removing before the send — as this did — would silently lose
				// exactly the large bodies the budget exists to pace. Keep the removal tied to
				// the outcome so adding that budget cannot reintroduce it.
				ids.remove();
				if (envelope == null)
					continue;
				stampBodyServe(watcher, resId, currentTick);
				stats.onBodyServed(envelope.length);
				transport.sendToWatcher(watcher, envelope);
				served++;
			}
			if (pending.isEmpty()) {
				watcherIter.remove();
			}
		}
	}

	private void stampBodyServe(String watcher, int resId, long currentTick) {
		Map<Integer, Long> serves = lastBodyServe.get(watcher);
		if (serves == null) {
			serves = new HashMap<Integer, Long>();
			lastBodyServe.put(watcher, serves);
		}
		serves.put(resId, currentTick);
	}

	private byte[] heartbeatEnvelope() {
		byte[] payload = MessageCodec.encodeHeartbeat(
				new MessageCodec.Heartbeat(scene.sceneId, scene.epoch(), scene.currentSeq()));
		return MessageCodec.envelope(MessageCodec.MSG_HEARTBEAT, payload);
	}

	private byte[] snapshotEnvelope() {
		// Keyed on (seq, manifestGen): knownHash changes at body-serve and save time with no
		// seq advance, and a snapshot advertising a stale hint silently defeats the client's
		// content-addressed cache gate.
		if (cachedSnapshotEnvelope == null || cachedSnapshotSeq != scene.currentSeq()
				|| cachedSnapshotManifestGen != scene.manifestGen()) {
			SceneSnapshot snapshot = scene.snapshot();
			cachedSnapshotEnvelope = MessageCodec.envelope(
					MessageCodec.MSG_SNAPSHOT, SnapshotCodec.encode(snapshot));
			cachedSnapshotSeq = snapshot.seq;
			cachedSnapshotManifestGen = scene.manifestGen();
		}
		return cachedSnapshotEnvelope;
	}

	private byte[] bodyEnvelope(int resId) {
		ResourceInfo res = scene.state().resources.get(resId);
		if (res == null || res.type != V2Wire.RES_TEXTURE || res.bytes == null) {
			bodyEnvelopeCache.remove(resId);
			return null;
		}
		// Sweep entries for resources that are gone: each holds a whole encoded texture, and
		// without this they survive for as long as any watcher stays subscribed.
		if (bodyEnvelopeCache.size() > scene.state().resources.size()) {
			Iterator<Integer> stale = bodyEnvelopeCache.keySet().iterator();
			while (stale.hasNext()) {
				if (!scene.state().resources.containsKey(stale.next())) {
					stale.remove();
				}
			}
		}
		// I-5: a body names the version as of a batch boundary. Serving one with deltas
		// staged would name a version the watchers have not been told about yet.
		if (scene.hasStagedDeltas())
			throw new IllegalStateException("Seal the pending batch before serving a body");
		CachedBody cached = bodyEnvelopeCache.get(resId);
		// VERSION, not array identity. writeRegion mutates the array in place, so the old
		// reference check is now not merely useless but actively dangerous: identity stays
		// stable while the content changes, and the cache would serve stale bytes forever
		// with no symptom.
		if (cached == null || cached.version != res.version) {
			// The one place a whole-texture hash is affordable: an O(size) encode is already
			// happening here, and the result is amortised over every watcher.
			long hash = V2Wire.contentHash(res.bytes);
			res.knownHash = hash;
			res.knownHashVersion = res.version;
			// The manifest's cache hint changed without a seq advance, so any cached snapshot
			// envelope is now stale in a way seq alone cannot detect.
			scene.bumpManifestGen();
			cached = new CachedBody();
			cached.version = res.version;
			cached.hash = hash;
			cached.envelope = MessageCodec.envelope(MessageCodec.MSG_RESOURCE_BODY,
					MessageCodec.encodeResourceBody(new MessageCodec.ResourceBody(
							scene.sceneId, scene.epoch(), resId, res.version, hash, res.bytes)));
			bodyEnvelopeCache.put(resId, cached);
		}
		return cached.envelope;
	}
}
