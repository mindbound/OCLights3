package opengpu.v2.mc.client.render;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;

/**
 * Smooths retained-node transforms across the 20 tps server channel.
 *
 * This is the reason retained nodes exist at all. Node properties land at most once per server
 * tick while the client draws at 60+ fps, so without this a sprite animated from Lua steps
 * visibly — and the only way a program could hide that was to busy-loop without sleeping,
 * burning its whole call budget to raise the update rate it cannot actually raise (the batch
 * seals once per tick regardless). Interpolating here makes 20 Hz updates look like 60 fps
 * motion and costs the program nothing.
 *
 * CLIENT-ONLY, deliberately. Previous-transform tracking is a presentation concern: putting it
 * on {@link SceneNode} would push it into the shared model, into snapshots via
 * {@code copyStructure()}, and into {@code contentEquals} — where a mirror mid-interpolation
 * would read as diverged from the server.
 *
 * TIME-based rather than tick-based. Batches arrive over the network, not on a clean client
 * tick boundary, so a tick counter would judder under jitter. Each node lerps from wherever it
 * visually WAS toward its new value over one server tick, clamped: a late batch parks the node
 * on target rather than overshooting, and an early one re-captures from the current
 * interpolated position instead of snapping.
 */
final class NodeInterpolator {
	/** One server tick. The interval a batch nominally covers. */
	private static final long WINDOW_NANOS = 50L * 1000L * 1000L;

	private static final int X = 0, Y = 1, ROT = 2, SX = 3, SY = 4;
	private static final int FIELDS = 5;

	private static final class Track {
		final double[] from = new double[FIELDS];
		final double[] to = new double[FIELDS];
		long startedNanos;
		boolean seen;
	}

	private final Map<Integer, Track> tracks = new HashMap<Integer, Track>();

	/**
	 * Fold a freshly applied batch in. Call when the mirror reports dirty, BEFORE rendering.
	 *
	 * A node whose transform did not change keeps its existing track, so a scene where only
	 * one sprite moves does not restart everything else's interpolation.
	 */
	void capture(SceneState state, long nowNanos) {
		for (SceneNode node : state.nodes.values()) {
			Track t = tracks.get(node.id);
			if (t == null) {
				// First sight: snap. Lerping a new node from a zeroed transform would fling it
				// in from the origin at scale 0.
				t = new Track();
				t.seen = true;
				write(t.to, node);
				System.arraycopy(t.to, 0, t.from, 0, FIELDS);
				t.startedNanos = nowNanos - WINDOW_NANOS; // already settled
				tracks.put(node.id, t);
				continue;
			}
			if (unchanged(t.to, node)) {
				continue;
			}
			// Re-base from where the node appears RIGHT NOW, not from the previous target —
			// otherwise a change arriving mid-window snaps back to the old start point.
			sample(t, nowNanos, t.from);
			write(t.to, node);
			t.startedNanos = nowNanos;
		}
		// Drop tracks for nodes that are gone, or a long-lived scene leaks one entry per
		// freed node forever.
		for (Iterator<Map.Entry<Integer, Track>> it = tracks.entrySet().iterator(); it.hasNext();) {
			if (!state.nodes.containsKey(it.next().getKey())) {
				it.remove();
			}
		}
	}

	/**
	 * Is any node still mid-flight? The pre-pass re-renders the scene FBO while this holds,
	 * which is the cost interpolation buys its smoothness with — and why it must go false for
	 * a settled scene rather than pinning every scene at full frame rate forever.
	 */
	boolean active(long nowNanos) {
		for (Track t : tracks.values()) {
			if (nowNanos - t.startedNanos < WINDOW_NANOS) {
				return true;
			}
		}
		return false;
	}

	/** The node's transform as it should appear now, written into {@code out} (5 fields). */
	void transformOf(SceneNode node, long nowNanos, double[] out) {
		Track t = tracks.get(node.id);
		if (t == null || !t.seen) {
			write(out, node);
			return;
		}
		sample(t, nowNanos, out);
	}

	private static void sample(Track t, long nowNanos, double[] out) {
		long elapsed = nowNanos - t.startedNanos;
		if (elapsed <= 0) {
			System.arraycopy(t.from, 0, out, 0, FIELDS);
			return;
		}
		if (elapsed >= WINDOW_NANOS) {
			System.arraycopy(t.to, 0, out, 0, FIELDS);
			return;
		}
		double a = (double) elapsed / (double) WINDOW_NANOS;
		out[X] = lerp(t.from[X], t.to[X], a);
		out[Y] = lerp(t.from[Y], t.to[Y], a);
		out[SX] = lerp(t.from[SX], t.to[SX], a);
		out[SY] = lerp(t.from[SY], t.to[SY], a);
		// Rotation takes the SHORTEST angular path. A plain lerp from 6.2 to 0.1 rad spins the
		// long way round — a full reverse revolution — every time a program wraps its angle.
		out[ROT] = t.from[ROT] + shortestAngle(t.to[ROT] - t.from[ROT]) * a;
	}

	private static double shortestAngle(double delta) {
		final double TWO_PI = Math.PI * 2.0;
		double d = delta % TWO_PI;
		if (d > Math.PI) {
			d -= TWO_PI;
		} else if (d < -Math.PI) {
			d += TWO_PI;
		}
		return d;
	}

	private static double lerp(double from, double to, double a) {
		return from + (to - from) * a;
	}

	private static void write(double[] dst, SceneNode node) {
		dst[X] = node.x;
		dst[Y] = node.y;
		dst[ROT] = node.rot;
		dst[SX] = node.sx;
		dst[SY] = node.sy;
	}

	private static boolean unchanged(double[] target, SceneNode node) {
		return target[X] == node.x && target[Y] == node.y && target[ROT] == node.rot
				&& target[SX] == node.sx && target[SY] == node.sy;
	}
}
