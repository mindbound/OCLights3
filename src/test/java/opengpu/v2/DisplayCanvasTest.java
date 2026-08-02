package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.ServerScene;

/**
 * Pins the rule that decides a scene's logical size: the display canvas is the LOWEST-ID
 * canvas node's canvas.
 *
 * It has no enforcement anywhere — it holds only because the implicit canvas is created
 * first and node ids are monotone. Breaking it is silent: both sides agree on the state, so
 * there is no sequence gap, no apply failure and no log line, just a scene FBO allocated at
 * the wrong size. Offscreen canvases are a specified feature (DESIGN-RENDERER-V2), so the
 * second canvas node is coming; these assertions are what should fail first when it does.
 */
public class DisplayCanvasTest {

	private static final int CAP = 4096;

	/** The implicit display canvas, created the way TileEntityGpu2.ensureImplicitCanvas does. */
	private static int[] withDisplayCanvas(ServerScene scene, int w, int h) {
		int res = scene.createCanvas(w, h, CAP);
		int node = scene.createNode(V2Wire.NODE_CANVAS, res);
		return new int[] { res, node };
	}

	@Test
	public void noCanvasNodeMeansNoLogicalSize() {
		ServerScene scene = new ServerScene("s");
		assertNull("a scene with no canvas node has no size to render at",
				scene.state().displayCanvas());
	}

	@Test
	public void theSoleCanvasIsTheDisplay() {
		ServerScene scene = new ServerScene("s");
		withDisplayCanvas(scene, 512, 288);
		ResourceInfo display = scene.state().displayCanvas();
		assertNotNull(display);
		assertEquals(512, display.width);
		assertEquals(288, display.height);
	}

	@Test
	public void aLaterOffscreenCanvasDoesNotBecomeTheDisplay() {
		// The case that matters. An offscreen canvas is just another canvas node; the only
		// thing keeping it out of the display slot is that its node id sorts later.
		ServerScene scene = new ServerScene("s");
		withDisplayCanvas(scene, 512, 288);

		int offscreenRes = scene.createCanvas(64, 64, CAP);
		scene.createNode(V2Wire.NODE_CANVAS, offscreenRes);

		ResourceInfo display = scene.state().displayCanvas();
		assertNotNull(display);
		assertEquals("an offscreen canvas displaced the display canvas", 512, display.width);
		assertEquals(288, display.height);
	}

	@Test
	public void severalOffscreenCanvasesStillDoNotDisplaceIt() {
		ServerScene scene = new ServerScene("s");
		withDisplayCanvas(scene, 512, 288);
		for (int i = 0; i < 5; i++) {
			int res = scene.createCanvas(32 + i, 16 + i, CAP);
			scene.createNode(V2Wire.NODE_CANVAS, res);
		}
		assertEquals(512, scene.state().displayCanvas().width);
	}

	@Test
	public void recreatingTheDisplayAtANewNodeIdSILENTLYBreaksTheRule() {
		// This is the trap, asserted so it is documented as a known consequence rather than
		// discovered as a misrender. A resolution change implemented as free-then-create at
		// a FRESH node id hands the display slot to whatever canvas now sorts lowest.
		ServerScene scene = new ServerScene("s");
		int[] display = withDisplayCanvas(scene, 512, 288);

		int offscreenRes = scene.createCanvas(64, 64, CAP);
		scene.createNode(V2Wire.NODE_CANVAS, offscreenRes);

		// "Resize" the display the naive way: drop it, make a new one.
		scene.freeNode(display[1]);
		scene.freeResource(display[0]);
		int newRes = scene.createCanvas(1024, 576, CAP);
		scene.createNode(V2Wire.NODE_CANVAS, newRes);

		// The OFFSCREEN canvas is now the lowest-id canvas node, so it is the display.
		ResourceInfo nowDisplay = scene.state().displayCanvas();
		assertEquals("if this ever reports 1024, the rule changed and the comment on "
				+ "SceneState.displayCanvas() plus this test must be revisited",
				64, nowDisplay.width);
		assertEquals(64, nowDisplay.height);
	}

	@Test
	public void freeingTheDisplayPromotesTheNextCanvasNode() {
		// Corollary of the same rule, worth pinning because it is the recovery path: the
		// slot is never empty while any canvas node survives.
		ServerScene scene = new ServerScene("s");
		int[] display = withDisplayCanvas(scene, 512, 288);
		int secondRes = scene.createCanvas(64, 64, CAP);
		scene.createNode(V2Wire.NODE_CANVAS, secondRes);

		scene.freeNode(display[1]);
		scene.freeResource(display[0]);

		assertEquals(64, scene.state().displayCanvas().width);
	}
}
