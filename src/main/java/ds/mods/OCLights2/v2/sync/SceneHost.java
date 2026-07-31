package ds.mods.OCLights2.v2.sync;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import ds.mods.OCLights2.v2.protocol.BatchCodec;
import ds.mods.OCLights2.v2.protocol.MessageCodec;
import ds.mods.OCLights2.v2.protocol.SceneBatch;
import ds.mods.OCLights2.v2.protocol.SnapshotCodec;
import ds.mods.OCLights2.v2.protocol.V2Wire;
import ds.mods.OCLights2.v2.scene.ResourceInfo;
import ds.mods.OCLights2.v2.scene.SceneSnapshot;
import ds.mods.OCLights2.v2.scene.ServerScene;

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
	private byte[] cachedSnapshotEnvelope;
	private final Map<Integer, CachedBody> bodyEnvelopeCache = new HashMap<Integer, CachedBody>();

	private static final class CachedBody {
		byte[] bytesRef;
		byte[] envelope;
	}

	public SceneHost(ServerScene scene, SceneTransport transport,
			int heartbeatInterval, int snapshotMinIntervalTicks, int bodiesPerWatcherPerTick) {
		this.scene = scene;
		this.transport = transport;
		this.heartbeatInterval = heartbeatInterval;
		this.snapshotMinIntervalTicks = snapshotMinIntervalTicks;
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

	/** Called once per server tick, after the component layer's mutations, under the scene lock. */
	public void tick(long currentTick) {
		lastTick = currentTick;
		if (destroyed)
			return;
		scene.setCurrentTick(currentTick);
		SceneBatch batch = scene.sealBatch();
		if (batch != null) {
			if (!watchers.isEmpty()) {
				byte[] envelope = MessageCodec.envelope(MessageCodec.MSG_BATCH, BatchCodec.encode(batch));
				for (String watcher : watchers) {
					transport.sendToWatcher(watcher, envelope);
				}
			}
			idleTicks = 0;
		} else if (!watchers.isEmpty() && ++idleTicks >= heartbeatInterval) {
			byte[] envelope = heartbeatEnvelope();
			for (String watcher : watchers) {
				transport.sendToWatcher(watcher, envelope);
			}
			idleTicks = 0;
		}
		// Snapshots only exist at batch boundaries — serve queued requests now.
		if (!pendingSnapshotRequests.isEmpty()) {
			byte[] envelope = snapshotEnvelope();
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
	public void onResourceRequest(String watcherKey, int resId) {
		if (!watchers.contains(watcherKey))
			return;
		Map<Integer, Long> serves = lastBodyServe.get(watcherKey);
		if (serves != null) {
			Long lastServe = serves.get(resId);
			if (lastServe != null && lastTick - lastServe < snapshotMinIntervalTicks)
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
				ids.remove();
				byte[] envelope = bodyEnvelope(resId);
				if (envelope == null)
					continue; // freed / not a texture: the client's resync path resolves it
				stampBodyServe(watcher, resId, currentTick);
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
				new MessageCodec.Heartbeat(scene.sceneId, scene.currentSeq()));
		return MessageCodec.envelope(MessageCodec.MSG_HEARTBEAT, payload);
	}

	private byte[] snapshotEnvelope() {
		if (cachedSnapshotEnvelope == null || cachedSnapshotSeq != scene.currentSeq()) {
			SceneSnapshot snapshot = scene.snapshot();
			cachedSnapshotEnvelope = MessageCodec.envelope(
					MessageCodec.MSG_SNAPSHOT, SnapshotCodec.encode(snapshot));
			cachedSnapshotSeq = snapshot.seq;
		}
		return cachedSnapshotEnvelope;
	}

	private byte[] bodyEnvelope(int resId) {
		ResourceInfo res = scene.state().resources.get(resId);
		if (res == null || res.type != V2Wire.RES_TEXTURE || res.bytes == null) {
			bodyEnvelopeCache.remove(resId);
			return null;
		}
		CachedBody cached = bodyEnvelopeCache.get(resId);
		// Texture bytes are cloned once at create and never replaced, so reference identity
		// is a sound cache validity check.
		if (cached == null || cached.bytesRef != res.bytes) {
			cached = new CachedBody();
			cached.bytesRef = res.bytes;
			cached.envelope = MessageCodec.envelope(MessageCodec.MSG_RESOURCE_BODY,
					MessageCodec.encodeResourceBody(
							new MessageCodec.ResourceBody(scene.sceneId, resId, res.bytes)));
			bodyEnvelopeCache.put(resId, cached);
		}
		return cached.envelope;
	}
}
