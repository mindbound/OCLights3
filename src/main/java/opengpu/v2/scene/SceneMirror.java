package opengpu.v2.scene;

import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;

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
	/** 0 = no incarnation adopted yet; set by the first epoch-bearing message. */
	private int knownEpoch;
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

	public int knownEpoch() {
		return knownEpoch;
	}

	/**
	 * Epoch discipline: a mismatched incarnation stamp means the scene was destroyed and
	 * recreated (or restored divergently) — every seq/state assumption is void. Hard reset:
	 * empty state, lastSeq 0, adopt the new epoch. The normal ordering rules then bootstrap
	 * the new incarnation (an in-order batch applies; anything else gaps into a resync).
	 * A mirror that has adopted no epoch yet (0) adopts silently without resetting, so
	 * late-joiner construction with an initial seq keeps working.
	 */
	private void adoptEpoch(int epoch) {
		if (knownEpoch == epoch)
			return;
		if (knownEpoch != 0) {
			hardReset();
		}
		knownEpoch = epoch;
	}

	private void hardReset() {
		state = new SceneState();
		lastSeq = 0;
		needsResync = false;
		dirty = true;
	}

	/** @return true when the batch was applied cleanly. */
	public boolean applyBatch(SceneBatch batch) {
		if (!sceneId.equals(batch.sceneId))
			return false;
		adoptEpoch(batch.epoch);
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
	 * resync when the server is ahead, applies nothing, never advances lastSeq. An epoch
	 * mismatch hard-resets and flags resync unconditionally (nothing of the old incarnation
	 * is trustworthy, and the new one must be fetched).
	 *
	 * A same-epoch heartbeat carrying a seq strictly BEHIND lastSeq is impossible in a
	 * healthy incarnation under the per-watcher FIFO transport contract (the mirror only
	 * ever learned seqs the server had already passed) — it is proof of a divergent restore
	 * (crash-without-save, live NBT rollback, epoch collision). Hard reset and resync, so
	 * the restored incarnation's snapshot installs instead of being stale-discarded forever.
	 */
	public void observeSeq(int epoch, int serverSeq) {
		boolean mismatch = knownEpoch != 0 && knownEpoch != epoch;
		adoptEpoch(epoch);
		if (mismatch) {
			needsResync = true;
			return;
		}
		int delta = V2Wire.seqDelta(serverSeq, lastSeq);
		if (delta > 0) {
			needsResync = true;
		} else if (delta < 0) {
			hardReset();
			needsResync = true;
		}
	}

	/**
	 * Validated delivery of a pending texture body — the only sanctioned way bytes reach a
	 * mirror. Rejects unknown ids (freed mid-transfer: the free cancels the transfer),
	 * non-textures, wrong lengths, and hash mismatches (caller should re-request).
	 */
	public boolean deliverResourceBody(int epoch, int resId, int version, long hash, byte[] bytes) {
		// I-6: never install while the state is unreliable. `latestVersion` is only a
		// trustworthy acceptance key on a mirror that has not missed deltas; a content hash
		// used to be self-certifying regardless of mirror health, and no longer is.
		if (needsResync || epoch != knownEpoch)
			return false;
		ResourceInfo res = state.resources.get(resId);
		if (res == null || res.type != V2Wire.RES_TEXTURE || bytes == null)
			return false;
		if (bytes.length != res.sizeBytes || V2Wire.contentHash(bytes) != hash)
			return false;
		// The body must be the version we believe is current. A body older than what the
		// delta stream already told us about would silently roll the texture back; a newer
		// one means we missed a write, which is a divergence we must not paper over.
		if (version != res.latestVersion)
			return false;
		res.bytes = bytes.clone();
		res.version = version;
		res.knownHash = hash;
		res.knownHashVersion = version;
		res.markFullDirty();
		dirty = true;
		return true;
	}

	/**
	 * Stale snapshots (a delayed response to an earlier request) are discarded — but only
	 * within the same incarnation: across an epoch change the stale rule is void (the new
	 * incarnation's seq may legitimately be behind the old one's).
	 */
	public void applySnapshot(SceneSnapshot snapshot) {
		if (!sceneId.equals(snapshot.sceneId))
			return;
		boolean sameEpoch = knownEpoch == snapshot.epoch;
		adoptEpoch(snapshot.epoch);
		if (sameEpoch && V2Wire.seqDelta(snapshot.seq, lastSeq) < 0)
			return; // keep needsResync latched; the retry cadence fetches a fresh one
		SceneState fresh = snapshot.state.copy();
		if (sameEpoch) {
			// Carry over bytes we already hold within the same incarnation: resource ids are
			// never reused, so bytes for (id, version) are still valid content. They land as
			// STALE if the snapshot names a newer version, which schedules exactly one refetch
			// instead of re-downloading every texture on every resync.
			for (ResourceInfo old : state.resources.values()) {
				if (old.bytes == null || old.version == 0)
					continue;
				ResourceInfo now = fresh.resources.get(old.id);
				if (now != null && now.type == V2Wire.RES_TEXTURE
						&& now.sizeBytes == old.sizeBytes && old.version <= now.latestVersion) {
					now.bytes = old.bytes;
					now.version = old.version;
					now.markFullDirty();
				}
			}
		}
		state = fresh;
		lastSeq = snapshot.seq;
		lastServerTick = snapshot.serverTick;
		needsResync = false;
		dirty = true;
	}
}
