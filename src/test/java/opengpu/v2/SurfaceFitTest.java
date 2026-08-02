package opengpu.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import opengpu.v2.mc.SurfaceFit;

/**
 * The forward mapping (the TESR's quad) and the inverse (an in-world click) have to agree
 * exactly. They previously did not, and the failure was a 63-row click offset that only a
 * human aiming at a pixel could notice. These are the assertions that make that agreement
 * checkable without a running world.
 *
 * Wall shapes are deliberately non-square with a scene that is neither, so a swapped axis or
 * a dropped col/row offset cannot pass by symmetry.
 */
public class SurfaceFitTest {

	/** Walls worth exercising: square, wide, tall, single tile, and the maximum span. */
	private static final int[][] WALLS = {
		{ 1, 1 }, { 4, 3 }, { 16, 9 }, { 2, 5 }, { 16, 1 }, { 1, 16 }, { 16, 16 }, { 7, 3 },
	};

	/** Scenes: 16:9 (the default's aspect), square, portrait, and extreme ratios. */
	private static final int[][] SCENES = {
		{ 512, 288 }, { 256, 256 }, { 100, 400 }, { 1024, 1 }, { 1, 1024 }, { 320, 200 },
	};

	@Test
	public void everyPixelCentreMapsBackToItself() {
		for (int[] wall : WALLS) {
			for (int[] scene : SCENES) {
				SurfaceFit fit = SurfaceFit.of(wall[0], wall[1], scene[0], scene[1]);
				// Sample the corners, edges and interior rather than every pixel: a sign
				// error or a half-pixel bias shows at the extremes first.
				int[] xs = { 0, 1, scene[0] / 2, scene[0] - 2, scene[0] - 1 };
				int[] ys = { 0, 1, scene[1] / 2, scene[1] - 2, scene[1] - 1 };
				for (int lx : xs) {
					for (int ly : ys) {
						// A 1- or 2-pixel dimension makes some of the fixed samples fall
						// outside the scene; those are not round-trippable by definition.
						if (lx < 0 || ly < 0 || lx >= scene[0] || ly >= scene[1]) {
							continue;
						}
						double[] p = fit.centreOfPixel(lx, ly);
						int[] back = fit.toLogical(p[0], p[1]);
						assertNotNull("wall " + wall[0] + "x" + wall[1] + " scene "
								+ scene[0] + "x" + scene[1] + " pixel " + lx + "," + ly
								+ " fell outside the image", back);
						assertArrayEquals("round trip for wall " + wall[0] + "x" + wall[1]
								+ " scene " + scene[0] + "x" + scene[1],
								new int[] { lx, ly }, back);
					}
				}
			}
		}
	}

	@Test
	public void imageKeepsTheScenesAspectAndFitsInsideTheWall() {
		for (int[] wall : WALLS) {
			for (int[] scene : SCENES) {
				SurfaceFit fit = SurfaceFit.of(wall[0], wall[1], scene[0], scene[1]);
				double imageAspect = fit.halfWidth() / fit.halfHeight();
				double sceneAspect = (double) scene[0] / scene[1];
				assertEquals("aspect preserved", sceneAspect, imageAspect, 1e-9);
				assertTrue("image wider than its wall", fit.halfWidth() <= wall[0] * 0.5 + 1e-9);
				assertTrue("image taller than its wall", fit.halfHeight() <= wall[1] * 0.5 + 1e-9);
				// One axis must touch the wall, or the fit left usable space unused.
				boolean touchesW = Math.abs(fit.halfWidth() - wall[0] * 0.5) < 1e-9;
				boolean touchesH = Math.abs(fit.halfHeight() - wall[1] * 0.5) < 1e-9;
				assertTrue("image is smaller than it needs to be", touchesW || touchesH);
			}
		}
	}

	@Test
	public void letterboxBarsAreAMissNotAnEdgePixel() {
		// 16:9 scene on a square 4x4 wall leaves bars above and below.
		SurfaceFit fit = SurfaceFit.of(4, 4, 512, 288);
		double centreU = 2.0;
		assertTrue("expected a letterboxed fit", fit.halfHeight() < 2.0 - 1e-6);
		// Just outside the top and bottom edges of the image.
		assertNull("above the image", fit.toLogical(centreU, 2.0 + fit.halfHeight() + 0.01));
		assertNull("below the image", fit.toLogical(centreU, 2.0 - fit.halfHeight() - 0.01));
		// Corners of the wall are always bars for a non-matching aspect.
		assertNull("wall corner", fit.toLogical(0.0, 0.0));
		assertNull("wall corner", fit.toLogical(4.0, 4.0));
		// Just inside is a hit, so the boundary is not simply rejecting everything.
		assertNotNull("inside the image", fit.toLogical(centreU, 2.0 + fit.halfHeight() - 0.01));
	}

	@Test
	public void wideWallPutsBarsLeftAndRight() {
		// The wall-specific direction: a 16:9 scene on a 16x1 wall is limited by HEIGHT.
		SurfaceFit fit = SurfaceFit.of(16, 1, 512, 288);
		assertTrue("expected pillarboxing", fit.halfWidth() < 8.0 - 1e-6);
		assertEquals("height should fill the wall", 0.5, fit.halfHeight(), 1e-9);
		assertNull("left bar", fit.toLogical(0.05, 0.5));
		assertNull("right bar", fit.toLogical(15.95, 0.5));
		assertNotNull("centre", fit.toLogical(8.0, 0.5));
	}

	@Test
	public void topLeftOfTheSceneIsTopLeftOnTheWall() {
		// The v flip is the easiest thing to get backwards and the hardest to see in code.
		SurfaceFit fit = SurfaceFit.of(4, 3, 512, 288);
		double[] topLeft = fit.centreOfPixel(0, 0);
		double[] bottomRight = fit.centreOfPixel(511, 287);
		assertTrue("scene pixel 0,0 must sit ABOVE the last row in world space",
				topLeft[1] > bottomRight[1]);
		assertTrue("scene pixel 0,0 must sit LEFT of the last column",
				topLeft[0] < bottomRight[0]);
	}

	@Test
	public void imageIsCentredOnTheWall() {
		SurfaceFit fit = SurfaceFit.of(7, 3, 320, 200);
		// Equal margins: the midpoint of the first and last pixel centres is the wall centre.
		double[] first = fit.centreOfPixel(0, 0);
		double[] last = fit.centreOfPixel(319, 199);
		assertEquals("horizontally centred", 3.5, (first[0] + last[0]) * 0.5, 1e-9);
		assertEquals("vertically centred", 1.5, (first[1] + last[1]) * 0.5, 1e-9);
	}

	@Test
	public void degenerateInputsThrowRatherThanMappingEverythingToTheOrigin() {
		// A NaN reaching toLogical would pass the range guard (every NaN comparison is false)
		// and silently report logical pixel 0,0 for every click.
		int[][] bad = { { 0, 1 }, { 1, 0 }, { -1, 4 }, { 4, -1 } };
		for (int[] b : bad) {
			try {
				SurfaceFit.of(4, 3, b[0], b[1]);
				fail("expected a rejection for scene " + b[0] + "x" + b[1]);
			} catch (IllegalArgumentException expected) {
				// intended
			}
			try {
				SurfaceFit.of(b[0], b[1], 512, 288);
				fail("expected a rejection for surface " + b[0] + "x" + b[1]);
			} catch (IllegalArgumentException expected) {
				// intended
			}
		}
	}
}
