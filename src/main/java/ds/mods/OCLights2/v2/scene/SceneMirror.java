package ds.mods.OCLights2.v2.scene;

import ds.mods.OCLights2.v2.protocol.Delta;
import ds.mods.OCLights2.v2.protocol.SceneBatch;
import ds.mods.OCLights2.v2.protocol.V2Wire;

/**
 * The client-side scene mirror, GL-free by design so server/mirror convergence is testable
 * headlessly. Implements the v2 ordering rules — these rules (not any lock) are what close
 * the legacy snapshot/delta ordering bug class:
 *
 * - stale batch (seq <= last applied, wraparound-safe) → discarded;
 * - in-order batch (seq == last + 1) → applied; any apply failure (unknown id, canvas
 *   mismatch, cap breach) flags {@code needsResync};
 * - gap (seq > last + 1) → nothing applied, {@code needsResync} flagged;
 * - while {@code needsResync} is set nothing applies until a snapshot arrives;
 * - a snapshot replaces the state, stamps lastSeq with its seq, and clears the flag —
 *   the stale rule then discards any late batches the snapshot already covers.
 *
 * The transport layer polls {@link #needsResync()} to drive retried snapshot requests.
 * {@code dirty} is the renderer's re-render trigger; the renderer clears it. A failed batch
 * does not set dirty: the renderer keeps showing the last clean frame until the snapshot
 * replaces the state.
 *
 * Thread contract: every method — including {@link #state()} reads — runs under the caller's
 * mirror lock; the renderer must hold that lock across its whole read of a frame. Nothing
 * here is internally synchronized.
 */
public final class SceneMirror {
	public final String sceneId;
	private SceneState state = new SceneState();
	private int lastSeq;
	private long lastServerTick;
	private boolean needsResync;
	private boolean dirty;

	public SceneMirror(String sceneId) {
		this(sceneId, 0);
	}

	/** initialSeq is exposed for wraparound testing. */
	public SceneMirror(String sceneId, int initialSeq) {
		this.sceneId = sceneId;
		this.lastSeq = initialSeq;
	}

	public SceneState state() {
		return state;
	}

	public int lastSeq() {
		return lastSeq;
	}

	public long lastServerTick() {
		return lastServerTick;
	}

	public boolean needsResync() {
		return needsResync;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void clearDirty() {
		dirty = false;
	}

	/** @return true when the batch was applied cleanly. */
	public boolean applyBatch(SceneBatch batch) {
		if (!sceneId.equals(batch.sceneId))
			return false;
		int delta = V2Wire.seqDelta(batch.seq, lastSeq);
		if (delta <= 0)
			return false; // stale — already covered by state or snapshot
		if (needsResync)
			return false; // unreliable state; wait for the snapshot
		if (delta > 1) {
			needsResync = true;
			return false;
		}
		if (batch.deltas.isEmpty()) {
			// The server never seals an empty batch; an in-order empty batch is a protocol
			// anomaly (e.g. a heartbeat wrongly encoded as a batch would silently swallow a
			// lost batch's seq here). Heartbeats must use observeSeq, never applyBatch.
			needsResync = true;
			return false;
		}
		boolean clean = true;
		for (Delta d : batch.deltas) {
			try {
				DeltaApplier.apply(state, d);
			} catch (Exception e) {
				// Unknown id / mismatch: state is unreliable from here — resync overwrites.
				needsResync = true;
				clean = false;
				break;
			}
		}
		lastSeq = batch.seq;
		lastServerTick = batch.serverTick;
		if (clean) {
			dirty = true;
		}
		return clean;
	}

	/**
	 * Seq-only probe for idle heartbeats and the "you are at seq N" re-entry check: flags
	 * resync when the server is ahead, applies nothing, never advances lastSeq.
	 */
	public void observeSeq(int serverSeq) {
		if (V2Wire.seqDelta(serverSeq, lastSeq) > 0) {
			needsResync = true;
		}
	}

	/**
	 * Validated delivery of a pending texture body — the only sanctioned way bytes reach a
	 * mirror. Rejects unknown ids (freed mid-transfer: the free cancels the transfer),
	 * non-textures, wrong lengths, and hash mismatches (caller should re-request).
	 */
	public boolean deliverResourceBody(int resId, byte[] bytes) {
		ResourceInfo res = state.resources.get(resId);
		if (res == null || res.type != V2Wire.RES_TEXTURE || bytes == null)
			return false;
		if (bytes.length != res.sizeBytes || V2Wire.contentHash(bytes) != res.hash)
			return false;
		res.bytes = bytes.clone();
		dirty = true;
		return true;
	}

	/** Stale snapshots (a delayed response to an earlier request) are discarded. */
	public void applySnapshot(SceneSnapshot snapshot) {
		if (!sceneId.equals(snapshot.sceneId))
			return;
		if (V2Wire.seqDelta(snapshot.seq, lastSeq) < 0)
			return; // keep needsResync latched; the retry cadence fetches a fresh one
		state = snapshot.state.copy();
		lastSeq = snapshot.seq;
		lastServerTick = snapshot.serverTick;
		needsResync = false;
		dirty = true;
	}
}
