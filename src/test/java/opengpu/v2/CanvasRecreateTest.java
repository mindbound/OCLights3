package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.ServerScene;

/**
 * Can a canvas be replaced at new dimensions under its EXISTING resource id, using only the
 * delta types that already exist?
 *
 * This is the question the whole resolution-API shape rests on. If yes, a resolution change
 * costs no new delta type, no PROTOCOL_VERSION bump and no save migration, and — because
 * the node keeps pointing at the same id — it cannot disturb the lowest-id rule that
 * decides which canvas is the display (see DisplayCanvasTest).
 *
 * The applier rejects a create for an id that already exists, so the answer depends entirely
 * on the free immediately before it being applied first, on BOTH sides, after a real
 * encode/decode round trip. That is not worth arguing about; it is worth shipping a batch.
 */
public class CanvasRecreateTest {

	private static final String SCENE = "gpu-node-address";
	private static final int CAP = 4096;

	private static void ship(ServerScene server, SceneMirror mirror) throws Exception {
		SceneBatch batch = server.sealBatch();
		if (batch == null) {
			return;
		}
		SceneBatch decoded = BatchCodec.decode(BatchCodec.encode(batch));
		assertTrue("mirror rejected the batch", mirror.applyBatch(decoded));
	}

	private static List<CanvasCommand> frame() {
		List<CanvasCommand> f = new ArrayList<CanvasCommand>();
		f.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, 255, 0, 0, 255));
		f.add(CanvasCommand.of(V2Wire.OP_FILL));
		f.add(CanvasCommand.text(10, 10, "hello"));
		return f;
	}

	@Test
	public void recreateAtTheSameIdConvergesThroughTheRealCodec() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);

		server.setCurrentTick(1);
		int canvas = server.createCanvas(512, 288, CAP);
		server.createNode(V2Wire.NODE_CANVAS, canvas);
		server.canvasAppend(canvas, frame());
		ship(server, mirror);
		assertTrue(server.state().contentEquals(mirror.state()));

		// The resize, in one batch: free then create at the same id.
		server.setCurrentTick(2);
		server.recreateCanvas(canvas, 1024, 576, CAP);
		ship(server, mirror);

		assertTrue("server and mirror diverged after a same-id canvas recreate",
				server.state().contentEquals(mirror.state()));
		ResourceInfo res = mirror.state().resources.get(canvas);
		assertNotNull("the resource id did not survive the recreate", res);
		assertEquals(1024, res.width);
		assertEquals(576, res.height);
	}

	@Test
	public void theNodeSurvivesAndTheDisplaySlotIsUnmoved() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);

		server.setCurrentTick(1);
		int display = server.createCanvas(512, 288, CAP);
		int displayNode = server.createNode(V2Wire.NODE_CANVAS, display);
		// An offscreen canvas, the thing that makes the display rule fragile.
		int offscreen = server.createCanvas(64, 64, CAP);
		server.createNode(V2Wire.NODE_CANVAS, offscreen);
		ship(server, mirror);

		server.setCurrentTick(2);
		server.recreateCanvas(display, 1024, 576, CAP);
		ship(server, mirror);

		assertTrue(server.state().contentEquals(mirror.state()));
		assertNotNull("the display node was destroyed", mirror.state().nodes.get(displayNode));
		assertEquals("the display node stopped referencing its canvas",
				display, mirror.state().nodes.get(displayNode).ref);
		// The whole point: the offscreen canvas did NOT capture the display slot.
		assertEquals(1024, mirror.state().displayCanvas().width);
		assertEquals(576, mirror.state().displayCanvas().height);
	}

	@Test
	public void contentIsClearedNotCarried() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);

		server.setCurrentTick(1);
		int canvas = server.createCanvas(512, 288, CAP);
		server.createNode(V2Wire.NODE_CANVAS, canvas);
		server.canvasAppend(canvas, frame());
		ship(server, mirror);
		assertTrue(mirror.state().resources.get(canvas).canvas.visibleCommands().size() > 0);

		server.setCurrentTick(2);
		server.recreateCanvas(canvas, 640, 360, CAP);
		ship(server, mirror);

		// Documented semantics, asserted so a later change to "preserve" is a deliberate one.
		assertEquals("a recreated canvas must start empty",
				0, mirror.state().resources.get(canvas).canvas.visibleCommands().size());
		assertTrue(server.state().contentEquals(mirror.state()));
	}

	@Test
	public void drawingIntoTheRecreatedCanvasStillConverges() throws Exception {
		// The recreate resets the resource record (version, canvas, hash). If any of that
		// bookkeeping were carried over wrongly, the NEXT append is where it would surface.
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);

		server.setCurrentTick(1);
		int canvas = server.createCanvas(512, 288, CAP);
		server.createNode(V2Wire.NODE_CANVAS, canvas);
		server.canvasAppend(canvas, frame());
		ship(server, mirror);

		server.setCurrentTick(2);
		server.recreateCanvas(canvas, 640, 360, CAP);
		server.canvasAppend(canvas, frame());
		ship(server, mirror);
		assertTrue(server.state().contentEquals(mirror.state()));

		server.setCurrentTick(3);
		server.canvasAppend(canvas, frame());
		ship(server, mirror);
		assertTrue("divergence appeared a tick after the recreate",
				server.state().contentEquals(mirror.state()));
	}

	@Test
	public void recreateInTheSameBatchAsTheCreateConverges() throws Exception {
		// Degenerate ordering: create and recreate with no seal between them, so the batch
		// carries create/free/create for one id. Ordering is the only thing that saves it.
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);

		server.setCurrentTick(1);
		int canvas = server.createCanvas(512, 288, CAP);
		server.createNode(V2Wire.NODE_CANVAS, canvas);
		server.recreateCanvas(canvas, 800, 450, CAP);
		ship(server, mirror);

		assertTrue(server.state().contentEquals(mirror.state()));
		assertEquals(800, mirror.state().displayCanvas().width);
	}

	@Test
	public void recreateRejectsWhatItShould() {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(512, 288, CAP);
		byte[] bytes = new byte[8 * 8 * 4];
		int texture = server.createTexture(8, 8, bytes);

		try {
			server.recreateCanvas(canvas + 9999, 64, 64, CAP);
			fail("expected a rejection for an unknown resource");
		} catch (IllegalStateException expected) {
			// intended
		}
		try {
			server.recreateCanvas(texture, 64, 64, CAP);
			fail("expected a rejection for a non-canvas resource");
		} catch (IllegalStateException expected) {
			// intended
		}
		try {
			server.recreateCanvas(canvas, 0, 64, CAP);
			fail("expected a rejection for a degenerate size");
		} catch (IllegalArgumentException expected) {
			// intended
		}
	}
}
