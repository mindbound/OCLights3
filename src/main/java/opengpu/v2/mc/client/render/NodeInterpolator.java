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
 * burning its whole call budget to raise an update rate it cannot actually raise (the batch
 * seals once per tick regardless). Interpolating here makes 20 Hz updates look like 60 fps
 * motion and costs the program nothing.
 *
 * <h2>Keyframes on the server clock, not a window from arrival</h2>
 * Each node keeps the last two states it was seen in, each stamped with the SERVER TICK that
 * carried it, and rendering samples them at {@link ServerTimeline#renderNanos}. The previous
 * implementation instead lerped over a fixed 50 ms window starting when the batch arrived
 * locally — the naive lerp-from-arrival DESIGN-RENDERER-V2 explicitly warns against. That
 * version is indistinguishable from this one on a LAN and wrong under jitter: a batch 20 ms
 * late compressed a node's motion into 30 ms and the next stretched it, so motion surged and
 * stalled. Replaying against server time means a late batch changes when we learn of a
 * movement, never how fast it appears to happen.
 *
 * CLIENT-ONLY, deliberately. Previous-transform tracking is a presentation concern: putting it
 * on {@link SceneNode} would push it into the shared model, into snapshots via
 * {@code copyStructure()}, and into {@code contentEquals} — where a mirror mid-interpolation
 * would read as diverged from the server.
 */
final class NodeInterpolator {
	private static final int X = 0, Y = 1, ROT = 2, SX = 3, SY = 4;
	private static final int FIELDS = 5;

	/**
	 * Interpolate only across states this close together in server ticks.
	 *
	 * DESIGN: "Lerp only between states from consecutive server ticks (or within a small
	 * threshold); otherwise snap — an idle node that moves after 400 ticks jumps, it does not
	 * glide for 20 seconds." Without this rule a node that sat still for a minute and then
	 * moved would be lerped across the entire idle span, since its two keyframes really are
	 * that far apart. Three ticks tolerates a program updating slightly slower than every tick
	 * without turning a genuine teleport into a crawl.
	 */
	private static final long MAX_GAP_TICKS = 3;

	private static final class Track {
		final double[] prev = new double[FIELDS];
		final double[] curr = new double[FIELDS];
		long prevTick;
		long currTick;
		/** This transition is a jump: a teleport, a resync seam, or too wide a tick gap. */
		boolean snap;
	}

	private final Map<Integer, Track> tracks = new HashMap<Integer, Track>();
	private final ServerTimeline timeline = new ServerTimeline();

	/**
	 * Fold a freshly applied batch in. Call when the mirror reports dirty, BEFORE rendering.
	 *
	 * @param serverTick the tick the batch was sealed on — the x-axis everything below uses.
	 * @param teleported nodes whose change carried PROP_TELEPORT; they snap. Without this a
	 *                   deliberate jump across the screen crawls, which is worse than the
	 *                   stepping this class exists to fix.
	 */
	void capture(SceneState state, long serverTick, long nowNanos, java.util.Set<Integer> teleported) {
		boolean rebased = timeline.onBatch(serverTick, nowNanos);
		for (SceneNode node : state.nodes.values()) {
			Track t = tracks.get(node.id);
			if (t == null) {
				// First sight: settle immediately. Lerping a new node from a zeroed transform
				// would fling it in from the origin at scale 0.
				t = new Track();
				write(t.curr, node);
				System.arraycopy(t.curr, 0, t.prev, 0, FIELDS);
				t.prevTick = serverTick;
				t.currTick = serverTick;
				t.snap = true;
				tracks.put(node.id, t);
				continue;
			}
			if (rebased) {
				// The clock re-based under us, so the stamps on this node's keyframes no longer
				// describe the same timeline. Interpolating across that seam would sweep the
				// node along an interval that never existed. DESIGN: "Resync always snaps."
				t.snap = true;
			}
			if (unchanged(t.curr, node)) {
				continue;
			}
			System.arraycopy(t.curr, 0, t.prev, 0, FIELDS);
			t.prevTick = t.currTick;
			write(t.curr, node);
			t.currTick = serverTick;
			t.snap = teleported.contains(Integer.valueOf(node.id))
					|| t.currTick - t.prevTick > MAX_GAP_TICKS
					|| t.currTick <= t.prevTick
					|| rebased;
		}
		// Drop tracks for nodes that are gone, or a long-lived scene leaks one entry per freed
		// node forever.
		for (Iterator<Map.Entry<Integer, Track>> it = tracks.entrySet().iterator(); it.hasNext();) {
			if (!state.nodes.containsKey(it.next().getKey())) {
				it.remove();
			}
		}
	}

	/** Discard the clock estimate and settle every node. For an epoch change or hard resync. */
	void reset() {
		timeline.reset();
		for (Track t : tracks.values()) {
			t.snap = true;
		}
	}

	/**
	 * Is any node still mid-flight? The pre-pass re-renders the scene FBO while this holds,
	 * which is the cost interpolation buys its smoothness with — and why it must go false for
	 * a settled scene rather than pinning every scene at full frame rate forever.
	 */
	boolean active(long nowNanos) {
		if (!timeline.primed()) {
			return false;
		}
		long render = timeline.renderNanos(nowNanos);
		for (Track t : tracks.values()) {
			if (!t.snap && render < ServerTimeline.tickNanos(t.currTick)) {
				return true;
			}
		}
		return false;
	}

	/** The node's transform as it should appear now, written into {@code out} (5 fields). */
	void transformOf(SceneNode node, long nowNanos, double[] out) {
		Track t = tracks.get(node.id);
		if (t == null) {
			write(out, node);
			return;
		}
		if (t.snap || !timeline.primed()) {
			System.arraycopy(t.curr, 0, out, 0, FIELDS);
			return;
		}
		sample(t, timeline.renderNanos(nowNanos), out);
	}

	private static void sample(Track t, long renderNanos, double[] out) {
		long t0 = ServerTimeline.tickNanos(t.prevTick);
		long t1 = ServerTimeline.tickNanos(t.currTick);
		if (renderNanos <= t0 || t1 <= t0) {
			System.arraycopy(t.prev, 0, out, 0, FIELDS);
			return;
		}
		if (renderNanos >= t1) {
			System.arraycopy(t.curr, 0, out, 0, FIELDS);
			return;
		}
		double a = (double) (renderNanos - t0) / (double) (t1 - t0);
		out[X] = lerp(t.prev[X], t.curr[X], a);
		out[Y] = lerp(t.prev[Y], t.curr[Y], a);
		out[SX] = lerp(t.prev[SX], t.curr[SX], a);
		out[SY] = lerp(t.prev[SY], t.curr[SY], a);
		// Rotation takes the SHORTEST angular path. A plain lerp from 6.2 to 0.1 rad spins the
		// long way round — a full reverse revolution — every time a program wraps its angle.
		out[ROT] = t.prev[ROT] + shortestAngle(t.curr[ROT] - t.prev[ROT]) * a;
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
