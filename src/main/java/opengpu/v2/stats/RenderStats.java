package opengpu.v2.stats;

/**
 * Client-side render counters — the half of the picture the server cannot see.
 *
 * The immediate reason this exists: node interpolation (landed 2026-08-04) changed the render
 * cost model and nobody measured it. A scene used to re-render when a batch arrived, at most
 * 20 times a second. It now re-renders on every frame while any node is mid-flight — 60 to 200
 * times a second — replaying the whole command list through immediate-mode GL, on a runtime
 * where immediate mode is emulated. Stage B's animators would multiply exactly this, so the
 * number is wanted before anything is built on top of it.
 *
 * <h2>What is timed, and what deliberately is not</h2>
 * One {@link System#nanoTime()} pair around a whole scene render. At 200 Hz that is 400 calls a
 * second, comfortably below noise. Timing individual primitives would cost more than the
 * primitives — the thing being measured is a {@code glBegin}/{@code glEnd} pair — so the
 * per-command figure is derived by dividing, not sampled.
 *
 * <h2>Why frames and renders are counted separately</h2>
 * {@link #framesWithWork} over {@link #prePasses} is the interpolation cost directly: the
 * fraction of frames that had to redraw at all. A settled scene should sit near zero, and if it
 * does not, {@code active()} is failing to go false — which is the specific regression this
 * class would catch and no test can.
 *
 * Static because there is one client and one render thread; every mutation below happens on it.
 */
public final class RenderStats {

	private RenderStats() {}

	/** Pre-passes run — effectively client frames in which any scene was in use. */
	public static long prePasses;
	/** Pre-passes where at least one scene actually re-rendered. */
	public static long framesWithWork;

	/** Scene FBO re-renders. Can exceed framesWithWork when several scenes are visible. */
	public static long sceneRenders;
	/** Re-renders caused by interpolation rather than by an arriving batch. */
	public static long interpolationRenders;

	public static long renderNanos;
	public static long renderNanosMax;

	/** Canvas commands replayed, so cost per command can be derived. */
	public static long commandsReplayed;

	/** Texture bytes uploaded and the uploads that carried them. */
	public static long uploadBytes;
	public static long uploads;
	/**
	 * TEXTURES deferred because the per-frame upload budget ran out — not frames.
	 *
	 * Several textures can be deferred in one frame, so this counts higher than the number of
	 * frames affected. Named and documented for what it is because the previous wording said
	 * "frames", and a reader comparing it against the frame count would have concluded the
	 * budget was exhausted more often than it is.
	 */
	public static long texturesDeferred;

	public static void onPrePass(boolean didWork) {
		prePasses++;
		if (didWork) {
			framesWithWork++;
		}
	}

	public static void onSceneRender(long nanos, int commands, boolean drivenByInterpolation) {
		sceneRenders++;
		renderNanos += nanos;
		commandsReplayed += commands;
		if (nanos > renderNanosMax) {
			renderNanosMax = nanos;
		}
		if (drivenByInterpolation) {
			interpolationRenders++;
		}
	}

	public static void onUpload(int bytes) {
		uploads++;
		uploadBytes += bytes;
	}

	public static void onTextureDeferred() {
		texturesDeferred++;
	}

	/** Fraction of pre-passes that had to redraw. Near zero for a settled scene. */
	public static double workFraction() {
		return prePasses == 0 ? 0.0 : (double) framesWithWork / (double) prePasses;
	}

	/** Fraction of re-renders that interpolation caused rather than fresh server state. */
	public static double interpolationFraction() {
		return sceneRenders == 0 ? 0.0 : (double) interpolationRenders / (double) sceneRenders;
	}

	public static double meanRenderMicros() {
		return sceneRenders == 0 ? 0.0 : renderNanos / (double) sceneRenders / 1000.0;
	}

	/** Nanoseconds per replayed canvas command — the figure that decides if replay is the cost. */
	public static double nanosPerCommand() {
		return commandsReplayed == 0 ? 0.0 : (double) renderNanos / (double) commandsReplayed;
	}

	public static void reset() {
		prePasses = 0;
		framesWithWork = 0;
		sceneRenders = 0;
		interpolationRenders = 0;
		renderNanos = 0;
		renderNanosMax = 0;
		commandsReplayed = 0;
		uploadBytes = 0;
		uploads = 0;
		texturesDeferred = 0;
	}
}
