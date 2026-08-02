package opengpu.v2.mc;

/**
 * How a scene is letterboxed onto a display surface, and the exact inverse of that fit.
 *
 * The renderer draws its quad from this; the click path inverts it. Those were previously
 * separate transcriptions of the same arithmetic in different files, and they drifted — the
 * single-tile version shipped a 63-row click offset, and by the time the wall arrived there
 * were four copies, one of them dead code that nothing called. One definition is the only
 * thing that makes "the click lands on the pixel the player aimed at" a checkable claim
 * rather than a hope.
 *
 * UNITS are the caller's and only have to be consistent within a single call: tiles for a
 * wall, blocks for one face. SURFACE SPACE has its origin at the surface's bottom-left with
 * +u to the viewer's right and +v UP. The scene's origin is TOP-LEFT, so the v flip is part
 * of the inverse and not the caller's problem.
 *
 * Deliberately free of Minecraft types: this is the part of the display geometry that can be
 * tested without a world, which is the other half of why it exists.
 */
public final class SurfaceFit {
	private final double surfaceW;
	private final double surfaceH;
	private final int sceneW;
	private final int sceneH;
	private final double halfW;
	private final double halfH;

	private SurfaceFit(double surfaceW, double surfaceH, int sceneW, int sceneH) {
		this.surfaceW = surfaceW;
		this.surfaceH = surfaceH;
		this.sceneW = sceneW;
		this.sceneH = sceneH;
		// Preserve the scene's aspect: whichever axis runs out first bounds the image.
		double hw = surfaceW * 0.5;
		double hh = surfaceH * 0.5;
		double sceneAspect = (double) sceneW / (double) sceneH;
		double surfaceAspect = surfaceW / surfaceH;
		if (sceneAspect >= surfaceAspect) {
			hh = hw / sceneAspect; // limited by width
		} else {
			hw = hh * sceneAspect; // limited by height
		}
		this.halfW = hw;
		this.halfH = hh;
	}

	/**
	 * Fit a {@code sceneW x sceneH} image, centred, into a {@code surfaceW x surfaceH} box.
	 *
	 * Throws rather than returning a degenerate fit: every non-positive input here is a bug
	 * upstream, and a NaN propagating into the inverse would silently report every click as
	 * logical pixel (0,0) instead of failing where it went wrong.
	 */
	public static SurfaceFit of(double surfaceW, double surfaceH, int sceneW, int sceneH) {
		if (!(surfaceW > 0.0) || !(surfaceH > 0.0)) {
			throw new IllegalArgumentException("Surface extent must be positive: "
					+ surfaceW + "x" + surfaceH);
		}
		if (sceneW <= 0 || sceneH <= 0) {
			throw new IllegalArgumentException("Scene size must be positive: "
					+ sceneW + "x" + sceneH);
		}
		return new SurfaceFit(surfaceW, surfaceH, sceneW, sceneH);
	}

	/** Half the image's width, in surface units — the quad spans centre +/- this. */
	public double halfWidth() {
		return halfW;
	}

	/** Half the image's height, in surface units. */
	public double halfHeight() {
		return halfH;
	}

	/**
	 * Centre of logical pixel {@code (lx, ly)} in surface space, as {@code {u, v}}.
	 *
	 * The forward direction, and the inverse of {@link #toLogical}: it exists so the
	 * agreement between the two can be asserted directly instead of inferred by reading two
	 * files side by side.
	 *
	 * Expects a pixel that is actually in the scene — {@code 0 <= lx < sceneW} and
	 * {@code 0 <= ly < sceneH}. Anything else extrapolates to a point off the image, which
	 * {@link #toLogical} then correctly reports as a miss.
	 */
	public double[] centreOfPixel(int lx, int ly) {
		double u = (surfaceW * 0.5 - halfW) + ((lx + 0.5) / sceneW) * (2.0 * halfW);
		double v = (surfaceH * 0.5 + halfH) - ((ly + 0.5) / sceneH) * (2.0 * halfH);
		return new double[] { u, v };
	}

	/**
	 * A surface-space point to LOGICAL scene pixels, or null when it landed on a letterbox
	 * bar. A bar is a miss, never a clamp to the nearest edge pixel: clamping would report a
	 * click on the black border as a click on the image.
	 */
	public int[] toLogical(double u, double v) {
		double su = (u - (surfaceW * 0.5 - halfW)) / (2.0 * halfW);
		double sv = ((surfaceH * 0.5 + halfH) - v) / (2.0 * halfH); // canvas origin is top-left
		if (su < 0.0 || su >= 1.0 || sv < 0.0 || sv >= 1.0) {
			return null;
		}
		int lx = (int) (su * sceneW);
		int ly = (int) (sv * sceneH);
		// Rounding at the far edge can land exactly on sceneW/sceneH despite su,sv < 1.
		return new int[] { Math.max(0, Math.min(sceneW - 1, lx)),
				Math.max(0, Math.min(sceneH - 1, ly)) };
	}
}
