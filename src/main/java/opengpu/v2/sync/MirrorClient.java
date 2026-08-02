package opengpu.v2.sync;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.MessageCodec;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.SnapshotCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.SceneSnapshot;

/**
 * Client-side sync driver: a registry of {@link SceneMirror}s fed by inbound envelopes, plus
 * the retry loops the design mandates —
 *
 * - while a mirror {@code needsResync}, a resync request is (re)sent every
 *   {@code resyncRetryTicks}, up to {@code maxResyncAttempts} unanswered attempts, after
 *   which the mirror is dropped (a scene that never answers is gone or unsubscribed —
 *   zombies must decay, not retry forever);
 * - pending texture bodies are first satisfied from a hash-keyed local byte cache (the
 *   content-addressed cache the design calls for: a resync flips delivered textures back to
 *   pending, and the cache makes that free instead of a full re-download), then requested
 *   over the wire with the same cadence.
 *
 * Mirrors are auto-created by batches, snapshots, AND heartbeats — the subscribe-time
 * heartbeat is precisely how a late joiner to an idle scene bootstraps (nothing else will
 * arrive until it resyncs). The zombie hazard this creates (a straggler message resurrecting
 * an evicted mirror) is bounded by decay, not prevention: an unanswered resync loop stops at
 * {@code maxResyncAttempts} and drops the mirror, and MSG_SCENE_GONE evicts immediately.
 * {@link #clear()} resets everything (world unload / disconnect).
 *
 * Thread contract: all methods under the caller's mirror-registry lock; the renderer reads
 * mirrors under the same lock.
 */
public final class MirrorClient {
	private final ClientTransport transport;
	private final int resyncRetryTicks;
	private final int maxResyncAttempts;
	private final int bodyCacheEntries;

	private final Map<String, SceneMirror> mirrors = new LinkedHashMap<String, SceneMirror>();
	private final Map<String, Long> lastResyncRequest = new HashMap<String, Long>();
	private final Map<String, Integer> resyncAttempts = new HashMap<String, Integer>();
	private final Map<String, Map<Integer, Long>> lastResourceRequest = new HashMap<String, Map<Integer, Long>>();
	private final LinkedHashMap<Long, byte[]> bodyCache;

	public MirrorClient(ClientTransport transport, int resyncRetryTicks) {
		this(transport, resyncRetryTicks, 32, 64);
	}

	public MirrorClient(ClientTransport transport, int resyncRetryTicks,
			int maxResyncAttempts, final int bodyCacheEntries) {
		this.transport = transport;
		this.resyncRetryTicks = resyncRetryTicks;
		this.maxResyncAttempts = maxResyncAttempts;
		this.bodyCacheEntries = bodyCacheEntries;
		this.bodyCache = new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
				return size() > bodyCacheEntries;
			}
		};
	}

	public SceneMirror mirror(String sceneId) {
		SceneMirror mirror = mirrors.get(sceneId);
		if (mirror == null) {
			mirror = new SceneMirror(sceneId);
			mirrors.put(sceneId, mirror);
		}
		return mirror;
	}

	public boolean hasMirror(String sceneId) {
		return mirrors.containsKey(sceneId);
	}

	public Iterable<SceneMirror> mirrors() {
		return mirrors.values();
	}

	/** Drops a scene's mirror and retry state (chunk unload / scene destroyed). */
	public void evict(String sceneId) {
		mirrors.remove(sceneId);
		lastResyncRequest.remove(sceneId);
		resyncAttempts.remove(sceneId);
		lastResourceRequest.remove(sceneId);
	}

	/** Full reset for world unload / disconnect. The body cache survives (content-addressed). */
	public void clear() {
		mirrors.clear();
		lastResyncRequest.clear();
		resyncAttempts.clear();
		lastResourceRequest.clear();
	}

	public void onMessage(byte[] envelope) throws CodecException {
		byte kind = MessageCodec.kindOf(envelope);
		byte[] payload = MessageCodec.payloadOf(envelope);
		switch (kind) {
			case MessageCodec.MSG_BATCH: {
				SceneBatch batch = BatchCodec.decode(payload);
				SceneMirror mirror = mirror(batch.sceneId);
				int epochBefore = mirror.knownEpoch();
				boolean clean = mirror.applyBatch(batch);
				noteEpochTransition(batch.sceneId, epochBefore, mirror);
				if (clean) {
					noteProgress(batch.sceneId);
				}
				break;
			}
			case MessageCodec.MSG_SNAPSHOT: {
				SceneSnapshot snapshot = SnapshotCodec.decode(payload);
				SceneMirror mirror = mirror(snapshot.sceneId);
				int epochBefore = mirror.knownEpoch();
				mirror.applySnapshot(snapshot);
				noteEpochTransition(snapshot.sceneId, epochBefore, mirror);
				if (!mirror.needsResync()) {
					noteProgress(snapshot.sceneId);
				}
				break;
			}
			case MessageCodec.MSG_HEARTBEAT: {
				MessageCodec.Heartbeat hb = MessageCodec.decodeHeartbeat(payload);
				SceneMirror mirror = mirror(hb.sceneId);
				int epochBefore = mirror.knownEpoch();
				mirror.observeSeq(hb.epoch, hb.seq);
				noteEpochTransition(hb.sceneId, epochBefore, mirror);
				break;
			}
			case MessageCodec.MSG_RESOURCE_BODY: {
				MessageCodec.ResourceBody body = MessageCodec.decodeResourceBody(payload);
				SceneMirror mirror = mirrors.get(body.sceneId);
				if (mirror != null && mirror.deliverResourceBody(
						body.epoch, body.resId, body.version, body.hash, body.bytes)) {
					// Hash comes from the message: the mirror already validated against it,
					// so recomputing here would be a second O(size) pass per delivery.
					cacheBody(body.hash, body.bytes);
					pruneResourceStamp(body.sceneId, body.resId);
				}
				break;
			}
			case MessageCodec.MSG_SCENE_GONE: {
				MessageCodec.SceneGone gone = MessageCodec.decodeSceneGone(payload);
				evict(gone.sceneId);
				break;
			}
			default:
				// Client-bound kinds only; server-bound kinds arriving here are protocol noise.
				throw new CodecException("Unexpected message kind on client: " + kind);
		}
	}

	/** Called once per client tick under the registry lock; drives the retry loops. */
	public void tick(long currentTick) {
		Iterator<Map.Entry<String, SceneMirror>> iter = mirrors.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, SceneMirror> e = iter.next();
			String sceneId = e.getKey();
			SceneMirror mirror = e.getValue();
			if (mirror.needsResync()) {
				Integer attempts = resyncAttempts.get(sceneId);
				int made = attempts == null ? 0 : attempts;
				if (made >= maxResyncAttempts) {
					// Unanswered for the whole window: the scene is gone or we are not
					// subscribed. Decay instead of retrying forever.
					iter.remove();
					lastResyncRequest.remove(sceneId);
					resyncAttempts.remove(sceneId);
					lastResourceRequest.remove(sceneId);
					continue;
				}
				Long last = lastResyncRequest.get(sceneId);
				if (last == null || currentTick - last >= resyncRetryTicks) {
					lastResyncRequest.put(sceneId, currentTick);
					resyncAttempts.put(sceneId, made + 1);
					byte[] payload = MessageCodec.encodeResyncRequest(
							new MessageCodec.ResyncRequest(sceneId, mirror.lastSeq()));
					transport.sendToServer(MessageCodec.envelope(MessageCodec.MSG_RESYNC_REQUEST, payload));
				}
				continue; // resource requests wait until the state is reliable again
			}
			requestPendingBodies(sceneId, mirror, currentTick);
		}
	}

	private void requestPendingBodies(String sceneId, SceneMirror mirror, long currentTick) {
		Map<Integer, Long> perScene = lastResourceRequest.get(sceneId);
		boolean anyPending = false;
		for (ResourceInfo res : mirror.state().resources.values()) {
			// needsBody, not isPending: a texture we hold at an older version also needs a
			// fetch, and it renders its stale content meanwhile rather than a placeholder.
			if (!res.needsBody())
				continue;
			// Content-addressed cache first: a resync flipped this back to pending, but we
			// may already hold the validated bytes. Only consult it when the advertised hash
			// actually describes the version we need.
			if (res.knownHashVersion == res.latestVersion) {
				byte[] cached = bodyCache.get(res.knownHash);
				if (cached != null && mirror.deliverResourceBody(mirror.knownEpoch(), res.id,
						res.latestVersion, res.knownHash, cached)) {
					pruneResourceStamp(sceneId, res.id);
					continue;
				}
			}
			anyPending = true;
			if (perScene == null) {
				perScene = new HashMap<Integer, Long>();
				lastResourceRequest.put(sceneId, perScene);
			}
			Long last = perScene.get(res.id);
			if (last == null || currentTick - last >= resyncRetryTicks) {
				perScene.put(res.id, currentTick);
				byte[] payload = MessageCodec.encodeResourceRequest(
						new MessageCodec.ResourceRequest(sceneId, mirror.knownEpoch(), res.id));
				transport.sendToServer(MessageCodec.envelope(MessageCodec.MSG_RESOURCE_REQUEST, payload));
			}
		}
		if (!anyPending && perScene != null && perScene.isEmpty()) {
			lastResourceRequest.remove(sceneId);
		}
	}

	private void noteProgress(String sceneId) {
		lastResyncRequest.remove(sceneId);
		resyncAttempts.remove(sceneId);
	}

	/**
	 * An incarnation change resets the mirror, so retry state accrued against the dead
	 * incarnation must not carry over: attempts charged to a scene that "never answered"
	 * would wrongly evict a scene that just proved it is alive, and per-resource request
	 * stamps collide with the new incarnation's reallocated ids.
	 */
	private void noteEpochTransition(String sceneId, int epochBefore, SceneMirror mirror) {
		if (epochBefore != mirror.knownEpoch()) {
			lastResyncRequest.remove(sceneId);
			resyncAttempts.remove(sceneId);
			lastResourceRequest.remove(sceneId);
		}
	}

	private void pruneResourceStamp(String sceneId, int resId) {
		Map<Integer, Long> perScene = lastResourceRequest.get(sceneId);
		if (perScene != null) {
			perScene.remove(resId);
			if (perScene.isEmpty()) {
				lastResourceRequest.remove(sceneId);
			}
		}
	}

	private void cacheBody(long hash, byte[] bytes) {
		bodyCache.put(hash, bytes.clone());
	}
}
