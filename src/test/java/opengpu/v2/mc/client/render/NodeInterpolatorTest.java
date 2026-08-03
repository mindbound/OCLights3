package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;

/**
 * Node interpolation is what makes 20 Hz server updates look like 60 fps motion. It is pure
 * arithmetic over the shared scene model — no GL, no Forge — so it is testable here, which
 * matters because most of its failure modes are invisible in a screenshot: a wrong lerp reads
 * as "slightly off", and a wrong angular path reads as a rare backspin nobody catches.
 */
public class NodeInterpolatorTest {

	private static final long MS = 1000L * 1000L;
	private static final long WINDOW = 50L * MS; // one server tick

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

	@Test
	public void firstSightSnapsInsteadOfFlyingInFromTheOrigin() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 100, 200);
		interp.capture(stateWith(n), 0L);

		double[] out = new double[5];
		interp.transformOf(n, 0L, out);
		assertEquals("a new node must appear where it is, not lerp in", 100, out[0], 1e-9);
		assertEquals(200, out[1], 1e-9);
		assertFalse("a snapped node is not mid-flight", interp.active(0L));
	}

	@Test
	public void midWindowIsHalfway() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), 0L);

		n.x = 100;
		n.y = 40;
		interp.capture(stateWith(n), WINDOW); // a batch lands one tick later

		double[] out = new double[5];
		interp.transformOf(n, WINDOW + WINDOW / 2, out);
		assertEquals(50, out[0], 1e-6);
		assertEquals(20, out[1], 1e-6);
		assertTrue("still mid-flight", interp.active(WINDOW + WINDOW / 2));
	}

	@Test
	public void pastTheWindowItParksOnTarget() {
		// A late batch must not overshoot — the node waits at its target instead.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), 0L);
		n.x = 100;
		interp.capture(stateWith(n), WINDOW);

		double[] out = new double[5];
		interp.transformOf(n, WINDOW + WINDOW * 10, out);
		assertEquals(100, out[0], 1e-9);
		assertFalse("a settled scene must stop forcing re-renders",
				interp.active(WINDOW + WINDOW * 10));
	}

	@Test
	public void rotationTakesTheShortestAngularPath() {
		// 6.2 rad -> 0.1 rad is +0.18 forward across the wrap, NOT -6.1 backward. A plain lerp
		// spins a full reverse revolution every time a program wraps its angle.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = new SceneNode(1, V2Wire.NODE_SPRITE, 1);
		n.rot = 6.2;
		interp.capture(stateWith(n), 0L);

		n.rot = 0.1;
		interp.capture(stateWith(n), WINDOW);

		double[] out = new double[5];
		interp.transformOf(n, WINDOW + WINDOW / 2, out);
		// Halfway along the SHORT path from 6.2: 6.2 + (0.1 + 2pi - 6.2)/2 ~= 6.2915.
		double expected = 6.2 + (0.1 + Math.PI * 2 - 6.2) / 2.0;
		assertEquals("rotation took the long way round", expected, out[2], 1e-6);
	}

	@Test
	public void aChangeMidFlightRebasesFromWhereItLooks() {
		// Without re-basing, a second batch arriving mid-window snaps the node back to the
		// previous start point before setting off again — a visible stutter under any program
		// that updates faster than the window.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), 0L);
		n.x = 100;
		interp.capture(stateWith(n), WINDOW);

		long mid = WINDOW + WINDOW / 2;   // node visually at x = 50
		n.x = 200;
		interp.capture(stateWith(n), mid);

		double[] out = new double[5];
		interp.transformOf(n, mid, out);
		assertEquals("must continue from 50, not jump back to 0", 50, out[0], 1e-6);

		interp.transformOf(n, mid + WINDOW / 2, out);
		assertEquals("halfway from 50 to 200", 125, out[0], 1e-6);
	}

	@Test
	public void anUnchangedNodeDoesNotRestartItsInterpolation() {
		// A scene where one sprite moves must not re-trigger every other node every batch.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode moving = node(1, 0, 0);
		SceneNode still = node(2, 500, 500);
		interp.capture(stateWith(moving, still), 0L);

		moving.x = 100;
		interp.capture(stateWith(moving, still), WINDOW);

		double[] out = new double[5];
		interp.transformOf(still, WINDOW + WINDOW / 2, out);
		assertEquals(500, out[0], 1e-9);
		assertEquals(500, out[1], 1e-9);
	}

	@Test
	public void freedNodesAreDroppedRatherThanLeaked() {
		// A long-lived scene churning nodes would otherwise accumulate one track per node
		// ever created, and active() would scan them forever.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode a = node(1, 0, 0);
		SceneNode b = node(2, 0, 0);
		interp.capture(stateWith(a, b), 0L);

		a.x = 100;
		b.x = 100;
		interp.capture(stateWith(a, b), WINDOW);
		assertTrue(interp.active(WINDOW + WINDOW / 2));

		// b disappears; a settles. Nothing should still be considered in flight.
		interp.capture(stateWith(a), WINDOW + WINDOW * 5);
		assertFalse("a dropped node must not keep the scene rendering",
				interp.active(WINDOW + WINDOW * 6));
	}

	@Test
	public void aTeleportSnapsInsteadOfSliding() {
		// A deliberate jump must not crawl to its destination over a server tick — that is
		// worse than the stepping interpolation was introduced to fix.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), 0L);

		n.x = 400;
		interp.capture(stateWith(n), WINDOW,
				java.util.Collections.singleton(Integer.valueOf(1)));

		double[] out = new double[5];
		interp.transformOf(n, WINDOW, out);
		assertEquals("teleport must arrive immediately", 400, out[0], 1e-9);
		interp.transformOf(n, WINDOW + WINDOW / 2, out);
		assertEquals(400, out[0], 1e-9);
		assertFalse("a teleport leaves nothing in flight", interp.active(WINDOW));
	}

	@Test
	public void anUnflaggedNodeStillInterpolatesWhenAnotherTeleports() {
		// The flag is per node, so one sprite jumping must not snap the rest of the scene.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode jumper = node(1, 0, 0);
		SceneNode slider = node(2, 0, 0);
		interp.capture(stateWith(jumper, slider), 0L);

		jumper.x = 400;
		slider.x = 100;
		interp.capture(stateWith(jumper, slider), WINDOW,
				java.util.Collections.singleton(Integer.valueOf(1)));

		double[] out = new double[5];
		interp.transformOf(jumper, WINDOW + WINDOW / 2, out);
		assertEquals(400, out[0], 1e-9);
		interp.transformOf(slider, WINDOW + WINDOW / 2, out);
		assertEquals("the unflagged node must still lerp", 50, out[0], 1e-6);
	}

	@Test
	public void scaleInterpolatesToo() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = new SceneNode(1, V2Wire.NODE_SPRITE, 1);
		n.sx = 1; n.sy = 1;
		interp.capture(stateWith(n), 0L);
		n.sx = 3; n.sy = 5;
		interp.capture(stateWith(n), WINDOW);

		double[] out = new double[5];
		interp.transformOf(n, WINDOW + WINDOW / 2, out);
		assertEquals(2, out[3], 1e-6);
		assertEquals(3, out[4], 1e-6);
	}
}
