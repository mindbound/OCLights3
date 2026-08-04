package opengpu.v2.stats;

/**
 * Per-scene server-side counters: what this scene puts on the wire, and how much.
 *
 * The roadmap has said "no performance baselines exist" since before the rework, and the
 * design's own requirement to measure bytes/tick BEFORE optimising was never met — compression,
 * sub-rect uploads and untinted drawTexture all shipped on argument. This class is the missing
 * instrumentation, not a nice-to-have: there is currently no way to get a single one of these
 * numbers out of a running server.
 *
 * <h2>Encoded bytes and sent bytes are different quantities</h2>
 * {@code SceneHost.tick} encodes one envelope and hands the same array to every watcher, so the
 * cost of a batch to the SERVER is its encoded size while the cost to the NETWORK is that size
 * times the watcher count. Recording only the first understates a populated server by a factor
 * of N, which is exactly the regime where the caps being tuned actually matter. Both are kept.
 *
 * <h2>Deliberately allocation-free and lock-free</h2>
 * Every field is a plain long mutated under the scene lock the callers already hold. No
 * timing, no ring buffer, no per-delta work: an instrument that perturbs the thing it measures
 * is worse than none, and the server path is the one where a few hundred nanoseconds per tick
 * would show up in the number being read.
 */
public final class SceneStats {

	/**
	 * Every tick this scene was ticked, counted once and unconditionally.
	 *
	 * The divisor for anything per-tick, and it is its own field for a reason. Deriving it as
	 * batches + idleTicks looked right and silently dropped two cases: a tick that sealed a
	 * batch with NO watchers (nothing is sent, so neither counter fired) and a tick that emitted
	 * a heartbeat. Both vanished from the denominator, inflating every per-tick figure — which
	 * would have argued for raising the transport caps on a number that was too high.
	 */
	public long ticks;

	/** Batches actually sealed and sent (ticks with no staged deltas produce none). */
	public long batches;
	/** Ticks that produced nothing and were therefore free. */
	public long idleTicks;
	/** Deltas across all batches — the divisor for "bytes per delta". */
	public long deltas;

	/** Encoded batch bytes, counted once per batch regardless of watcher count. */
	public long batchBytes;
	/** The largest single batch seen, which is what has to fit the decoder's inflate ceiling. */
	public long batchBytesMax;
	/** Encoded bytes times watchers: what the network actually carried. */
	public long batchBytesSent;

	/** Heartbeats emitted for scenes that are visually live but network-silent. */
	public long heartbeats;

	/** Resync snapshots served, and their encoded size — the cost of a client entering range. */
	public long snapshots;
	public long snapshotBytes;
	public long snapshotBytesMax;

	/** Resource body bytes served, which the per-watcher fairness cursor paces. */
	public long bodies;
	public long bodyBytes;

	/** Peak watcher count seen, so a byte total can be read against the audience it had. */
	public int watchersMax;

	public void onBatch(int encodedBytes, int deltaCount, int watchers) {
		batches++;
		deltas += deltaCount;
		batchBytes += encodedBytes;
		batchBytesSent += (long) encodedBytes * watchers;
		if (encodedBytes > batchBytesMax) {
			batchBytesMax = encodedBytes;
		}
		if (watchers > watchersMax) {
			watchersMax = watchers;
		}
	}

	/** Call once per tick, before any of the outcome-specific methods below. */
	public void onTick() {
		ticks++;
	}

	public void onIdleTick() {
		idleTicks++;
	}

	public void onHeartbeat(int watchers) {
		heartbeats++;
		if (watchers > watchersMax) {
			watchersMax = watchers;
		}
	}

	public void onSnapshot(int encodedBytes, int recipients) {
		snapshots += recipients;
		snapshotBytes += (long) encodedBytes * recipients;
		if (encodedBytes > snapshotBytesMax) {
			snapshotBytesMax = encodedBytes;
		}
	}

	public void onBodyServed(int bytes) {
		bodies++;
		bodyBytes += bytes;
	}

	/** Mean encoded bytes per sealed batch; 0 when nothing has been sent. */
	public double meanBatchBytes() {
		return batches == 0 ? 0.0 : (double) batchBytes / (double) batches;
	}

	/**
	 * Mean encoded bytes per TICK, counting idle ticks.
	 *
	 * This is the figure the transport caps should be judged against, and it is not
	 * {@link #meanBatchBytes()}: a scene that seals one batch every twentieth tick costs a
	 * twentieth as much per tick as its batch size suggests. Reading the wrong one of these two
	 * would argue for a cap change in the wrong direction.
	 */
	public double meanBytesPerTick() {
		return ticks == 0 ? 0.0 : (double) batchBytes / (double) ticks;
	}

	public void reset() {
		ticks = 0;
		batches = 0;
		idleTicks = 0;
		deltas = 0;
		batchBytes = 0;
		batchBytesMax = 0;
		batchBytesSent = 0;
		heartbeats = 0;
		snapshots = 0;
		snapshotBytes = 0;
		snapshotBytesMax = 0;
		bodies = 0;
		bodyBytes = 0;
		watchersMax = 0;
	}
}
