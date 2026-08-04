package opengpu.v2.mc.client.render;

/**
 * The client's smoothed estimate of server time for one scene, and the render clock derived
 * from it.
 *
 * DESIGN-RENDERER-V2 § Animation: "the client keeps a monotonic estimate of server time smoothed
 * from batch arrivals (jitter buffer) and renders the scene at that estimate minus a fixed
 * interpolation delay of 1-2 ticks. Naive lerp-from-arrival stutters under jitter; MC's own
 * entity sync exists for this reason."
 *
 * The first implementation of node interpolation ignored this and lerped over a fixed 50 ms
 * window measured from LOCAL ARRIVAL — precisely the version the design warns against. It looks
 * correct on a LAN, where arrivals are evenly spaced, and degrades exactly where it matters: a
 * batch 20 ms late compresses that node's motion into 30 ms and the next one stretches it, so
 * the sprite surges and stalls. No test can see it, because the defect IS the timing.
 *
 * What this buys, concretely: node motion is replayed against SERVER time, so a late batch
 * changes when we learn about a movement but not how fast it appears to happen.
 *
 * <h2>Why an offset rather than a rate estimate</h2>
 * Server ticks and client nanoseconds both advance at real-time rates, so there is no clock
 * SKEW to estimate — only a fixed unknown offset plus transit jitter. Estimating a rate would
 * fit noise. The offset is smoothed with an EMA and hard-reset when it moves further than any
 * jitter could explain (server restart, a long freeze, a scene epoch change).
 *
 * The offset absorbs one-way transit latency as a constant bias, which is harmless: the render
 * clock is deliberately behind live anyway, and a consistent bias shifts everything equally.
 * What would NOT be harmless is a bias that varies per batch — which is what the arrival-time
 * implementation had.
 */
final class ServerTimeline {
	/** One server tick at 20 tps. */
	static final long TICK_NANOS = 50L * 1000L * 1000L;

	/**
	 * How far behind the live estimate we render, in ticks.
	 *
	 * This is the jitter buffer, and it is the whole reason the class exists: to interpolate
	 * toward a keyframe we must already HOLD it, so we must render far enough in the past that
	 * the next keyframe has normally arrived. One tick is the theoretical minimum and leaves
	 * nothing for a late batch — the display would stall on the newest keyframe whenever a
	 * batch slipped even slightly. Two ticks tolerates a batch arriving a full tick late.
	 *
	 * The cost is honest and worth stating: node motion appears 100 ms behind the server. For a
	 * program that moves a sprite in response to a click, that is added to the click's own
	 * round trip. Canvas CONTENT is unaffected — it never interpolates and is drawn as soon as
	 * it applies. DESIGN records 1-2 ticks with the exact value to be tuned in Stage B.
	 */
	static final int INTERPOLATION_DELAY_TICKS = 2;

	private static final long DELAY_NANOS = INTERPOLATION_DELAY_TICKS * TICK_NANOS;

	/** EMA weight for a new offset sample. Low: we are smoothing jitter, not tracking drift. */
	private static final double ALPHA = 0.125;

	/**
	 * Beyond this the estimate is not jitter and must not be averaged toward over many batches.
	 * Half a second is far outside any plausible tick-to-tick network variation while being
	 * well inside the gap a server pause or a resync produces.
	 */
	private static final long RESET_THRESHOLD_NANOS = 500L * 1000L * 1000L;

	private long offsetNanos;
	private boolean primed;
	private long lastTick = Long.MIN_VALUE;

	/**
	 * Fold in a batch arrival.
	 *
	 * @return true if the timeline discontinuously re-based, meaning callers holding
	 *         server-time-stamped state must SNAP rather than interpolate across the seam.
	 */
	boolean onBatch(long serverTick, long nowNanos) {
		long sample = serverTick * TICK_NANOS - nowNanos;
		if (!primed) {
			offsetNanos = sample;
			primed = true;
			lastTick = serverTick;
			return true;
		}
		// A tick going BACKWARDS is not jitter — it is a different incarnation of the scene, or
		// a restored server. Averaging toward it would slide every node across the seam.
		boolean rebase = serverTick < lastTick
				|| Math.abs(sample - offsetNanos) > RESET_THRESHOLD_NANOS;
		if (rebase) {
			offsetNanos = sample;
		} else {
			offsetNanos += (long) ((sample - offsetNanos) * ALPHA);
		}
		lastTick = serverTick;
		return rebase;
	}

	/** Discard the estimate entirely; the next batch re-primes it. Use on epoch change/resync. */
	void reset() {
		primed = false;
		lastTick = Long.MIN_VALUE;
	}

	boolean primed() {
		return primed;
	}

	/** Estimated server time now, in the same nanosecond units the tick stamps use. */
	long serverNanos(long nowNanos) {
		return nowNanos + offsetNanos;
	}

	/**
	 * The moment being rendered: the estimate minus the interpolation delay.
	 *
	 * Deliberately NOT clamped monotonic. The EMA can nudge this backwards by a fraction of a
	 * millisecond, which merely re-shows a position a hair earlier and is invisible; clamping
	 * would instead freeze the clock whenever the estimate corrected downward, turning a
	 * sub-millisecond wobble into a visible stall.
	 */
	long renderNanos(long nowNanos) {
		return serverNanos(nowNanos) - DELAY_NANOS;
	}

	/** Server-time stamp of a tick, for comparing against {@link #renderNanos}. */
	static long tickNanos(long serverTick) {
		return serverTick * TICK_NANOS;
	}
}
