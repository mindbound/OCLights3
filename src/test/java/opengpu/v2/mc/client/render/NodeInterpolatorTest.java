package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;

/**
 * Node interpolation is what makes 20 Hz server updates look like 60 fps motion. It is pure
 * arithmetic over the shared scene model — no GL, no Forge — so it is testable here, which
 * matters because most of its failure modes are invisible in a screenshot: a wrong lerp reads
 * as "slightly off", and a wrong angular path reads as a rare backspin nobody catches.
 *
 * These were rewritten when interpolation moved from a fixed window measured at BATCH ARRIVAL
 * to keyframes replayed against the SERVER clock. Worth stating what the old tests could not
 * see, because it explains how the first implementation passed ten green tests while being the
 * exact model the design warns against: every one of them fed batches at perfectly even
 * intervals, where arrival time and server time coincide up to a constant. Jitter is the only
 * condition that tells the two models apart, so it now has tests of its own.
 */
public class NodeInterpolatorTest {

	private static final long MS = 1000L * 1000L;
	private static final long TICK = ServerTimeline.TICK_NANOS;
	private static final long DELAY = ServerTimeline.INTERPOLATION_DELAY_TICKS * TICK;

	/** Arrivals are paced exactly one tick apart, so the clock offset is a constant. */
	private static final long T0 = 1000L;      // first server tick
	private static final long N0 = 7_000_000L; // arbitrary local nanos at that arrival

	private static final Set<Integer> NONE = Collections.<Integer>emptySet();

	/** Local time at which the render clock is showing {@code serverTick}. */
	private static long localShowing(double serverTick) {
		// renderNanos(N) = N + offset - DELAY, with offset = T0*TICK - N0 for paced arrivals.
		return (long) (serverTick * TICK) - (T0 * TICK - N0) + DELAY;
	}

	/** Local arrival time of the k-th paced batch. */
	private static long arrival(long k) {
		return N0 + k * TICK;
	}

	private static SceneState stateWith(SceneNode... nodes) {
		SceneState s = new SceneState();
		for (SceneNode n : nodes) {
			s.nodes.put(n.id, n);
		}
		return s;
	}

	private static SceneNode node(int id, double x, double y) {
		SceneNode n = new SceneNode(id, V2Wire.NODE_SPRITE, 1);
		n.x = x;
		n.y = y;
		return n;
	}

	// ------------------------------------------------------------------
	// The clock

	@Test
	public void theRenderClockLagsLiveByExactlyTheInterpolationDelay() {
		// The lag IS the jitter buffer: to interpolate TOWARD a keyframe we must already hold
		// it, so the rendered moment has to sit far enough in the past that the next one has
		// normally arrived. Driving this to zero would look right and stall on every batch.
		ServerTimeline clock = new ServerTimeline();
		assertFalse(clock.primed());
		clock.onBatch(T0, N0);
		assertTrue(clock.primed());

		assertEquals(clock.serverNanos(N0) - DELAY, clock.renderNanos(N0));
		assertEquals("at the moment a tick lands we render DELAY behind it",
				ServerTimeline.tickNanos(T0) - DELAY, clock.renderNanos(N0));
	}

	@Test
	public void aLateBatchMovesTheClockByAFractionOfItsLateness() {
		// The property the whole rewrite exists for. Under the old model a batch 20 ms late
		// compressed that node's motion into 30 ms and the next stretched it, so motion surged
		// and stalled. Smoothing means one late arrival barely moves the clock.
		ServerTimeline clock = new ServerTimeline();
		clock.onBatch(T0, N0);
		long before = clock.serverNanos(N0);

		long late = 20 * MS;
		clock.onBatch(T0 + 1, arrival(1) + late);
		long after = clock.serverNanos(N0);

		long shift = Math.abs(after - before);
		assertTrue("a 20 ms late batch must not drag the clock 20 ms: " + shift, shift < late / 2);
		assertTrue("but it must move it somewhat, or the clock never tracks", shift > 0);
	}

	@Test
	public void pacedArrivalsHoldTheClockSteady() {
		ServerTimeline clock = new ServerTimeline();
		clock.onBatch(T0, N0);
		long offsetProbe = clock.serverNanos(N0) - N0;
		for (long k = 1; k < 40; k++) {
			assertFalse("paced traffic must never look like a re-base",
					clock.onBatch(T0 + k, arrival(k)));
		}
		assertEquals("a stable stream must not let the estimate drift",
				offsetProbe, clock.serverNanos(N0) - N0);
	}

	@Test
	public void aBackwardTickOrAHugeJumpRebases() {
		ServerTimeline clock = new ServerTimeline();
		clock.onBatch(T0, N0);
		assertTrue("a tick going backwards is a different incarnation, not jitter",
				clock.onBatch(T0 - 5, arrival(1)));

		ServerTimeline other = new ServerTimeline();
		other.onBatch(T0, N0);
		assertTrue("a jump past any plausible jitter must re-base, not be averaged toward",
				other.onBatch(T0 + 1, arrival(1) + 5_000L * MS));
	}

	// ------------------------------------------------------------------
	// Keyframes

	@Test
	public void firstSightSnapsInsteadOfFlyingInFromTheOrigin() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 100, 200);
		interp.capture(stateWith(n), T0, N0, NONE);

		double[] out = new double[5];
		interp.transformOf(n, N0, out);
		assertEquals("a new node must appear where it is, not lerp in", 100, out[0], 1e-9);
		assertEquals(200, out[1], 1e-9);
		assertFalse("a snapped node is not mid-flight", interp.active(N0));
	}

	/** x at the server moment halfway between the keyframes, for a given arrival lateness. */
	private static double positionAtHalfTick(long lateness) {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1) + lateness, NONE);

		double[] out = new double[5];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		return out[0];
	}

	@Test
	public void latenessIsDampedRatherThanPassedStraightThrough() {
		// THE regression test for the rewrite, and worth stating its arithmetic because the
		// obvious assertion — "the two are identical" — is WRONG and this test was written that
		// way first. The clock deliberately tracks arrivals, so lateness is not eliminated, it
		// is damped by ALPHA.
		//
		// Over a 50 ms keyframe interval carrying 100 units of travel, an 18 ms late batch:
		//   this model     -> clock absorbs ALPHA * 18 ms = 2.25 ms  ->  ~4.5 units of shift
		//   lerp-from-arrival -> the full 18 ms lands on the motion  ->  ~36 units of shift
		// The gap between those two numbers is the entire benefit, so assert against it rather
		// than against equality.
		double onTime = positionAtHalfTick(0);
		double late = positionAtHalfTick(18 * MS);
		double shift = Math.abs(onTime - late);

		assertTrue("lateness must be damped, not passed through: " + shift, shift < 8.0);
		assertTrue("an undamped model would shift ~36 units here", shift < 36.0 / 2);
		assertTrue("but the clock must still track arrivals at all", shift > 0.0);
	}

	@Test
	public void aNodeTraversesItsKeyframeIntervalExactlyOnce() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		n.y = 40;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[5];
		interp.transformOf(n, localShowing(T0), out);
		assertEquals("at the first keyframe it is at the start", 0, out[0], 1e-6);

		interp.transformOf(n, localShowing(T0 + 0.5), out);
		assertEquals(50, out[0], 1.0);
		assertEquals(20, out[1], 1.0);

		interp.transformOf(n, localShowing(T0 + 1), out);
		assertEquals("at the second keyframe it has arrived", 100, out[0], 1e-6);

		interp.transformOf(n, localShowing(T0 + 9), out);
		assertEquals("and it stays there rather than overshooting", 100, out[0], 1e-6);
	}

	@Test
	public void aLongIdleThenAMoveJumpsRatherThanCrawling() {
		// DESIGN: "an idle node that moves after 400 ticks jumps, it does not glide for 20
		// seconds". The two keyframes really ARE that far apart, so without the gap rule the
		// lerp would sweep the node across the whole idle span.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 500;
		interp.capture(stateWith(n), T0 + 400, arrival(400), NONE);

		double[] out = new double[5];
		interp.transformOf(n, localShowing(T0 + 200), out);
		assertEquals("a 400-tick gap must snap, not glide", 500, out[0], 1e-9);
		assertFalse(interp.active(arrival(400)));
	}

	@Test
	public void aTeleportSnapsWhileItsNeighbourKeepsSliding() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode jumper = node(1, 0, 0);
		SceneNode slider = node(2, 0, 0);
		interp.capture(stateWith(jumper, slider), T0, N0, NONE);

		jumper.x = 300;
		slider.x = 100;
		interp.capture(stateWith(jumper, slider), T0 + 1, arrival(1),
				Collections.singleton(Integer.valueOf(1)));

		double[] out = new double[5];
		interp.transformOf(jumper, localShowing(T0 + 0.5), out);
		assertEquals("the flagged node jumps", 300, out[0], 1e-9);
		interp.transformOf(slider, localShowing(T0 + 0.5), out);
		assertEquals("its neighbour is unaffected", 50, out[0], 1.0);
	}

	@Test
	public void rotationTakesTheShortestAngularPath() {
		// A plain lerp from 6.2 to 0.1 rad spins the long way round — a full reverse revolution
		// — every time a program wraps its angle past 2pi.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		n.rot = 6.2;
		interp.capture(stateWith(n), T0, N0, NONE);
		n.rot = 0.1;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[5];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		double mid = out[2];
		assertTrue("must cross the 2pi seam, not sweep back through pi: " + mid,
				mid > 6.2 || mid < 0.15);
	}

	@Test
	public void aRebaseSettlesEveryNodeInsteadOfSweepingAcrossTheSeam() {
		// Tick stamps from a dead incarnation describe nothing in the new one, so interpolating
		// across the seam would sweep every node along an interval that never existed.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		n.x = 700;
		interp.capture(stateWith(n), 5L, arrival(2), NONE); // fresh incarnation, ticks restart

		double[] out = new double[5];
		interp.transformOf(n, arrival(2), out);
		assertEquals("a re-based node shows its current value outright", 700, out[0], 1e-9);
	}

	@Test
	public void resetSettlesEverything() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);
		assertTrue(interp.active(arrival(1)));

		interp.reset();
		double[] out = new double[5];
		interp.transformOf(n, arrival(1), out);
		assertEquals(100, out[0], 1e-9);
		assertFalse("nothing is mid-flight after a reset", interp.active(arrival(1)));
	}

	@Test
	public void aSettledSceneStopsForcingRerenders() {
		// active() is what pins the scene at full frame rate; if it never went false a static
		// display would re-render forever, which is a cost interpolation should pay only while
		// something is actually moving.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		assertTrue("still catching up right after the batch", interp.active(arrival(1)));
		assertFalse("settled once the render clock passes the newest keyframe",
				interp.active(localShowing(T0 + 5)));
	}

	@Test
	public void freedNodesDoNotLeakTracks() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode a = node(1, 0, 0);
		SceneNode b = node(2, 0, 0);
		interp.capture(stateWith(a, b), T0, N0, NONE);
		interp.capture(stateWith(a), T0 + 1, arrival(1), NONE);

		// b is gone: it must not keep a track alive, and asking about it falls back to its own
		// values rather than a stale keyframe.
		b.x = 999;
		double[] out = new double[5];
		interp.transformOf(b, arrival(1), out);
		assertEquals(999, out[0], 1e-9);
	}

	@Test
	public void anUnchangedNodeKeepsItsOwnTimeline() {
		// A scene where one sprite moves must not restart everything else's interpolation.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode mover = node(1, 0, 0);
		SceneNode still = node(2, 25, 25);
		interp.capture(stateWith(mover, still), T0, N0, NONE);

		mover.x = 100;
		interp.capture(stateWith(mover, still), T0 + 1, arrival(1), NONE);

		double[] out = new double[5];
		interp.transformOf(still, localShowing(T0 + 0.5), out);
		assertEquals("a node nobody touched must not move", 25, out[0], 1e-9);
		assertEquals(25, out[1], 1e-9);
	}
}
