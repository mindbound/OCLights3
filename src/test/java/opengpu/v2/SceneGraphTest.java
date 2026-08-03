package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.ServerScene;

/**
 * The retained scene graph — offscreen canvases, sprite and canvas nodes, node properties —
 * now that Lua can reach it. These are the semantics the new callbacks delegate to, exercised
 * through the real codec so a node property that fails to encode shows up here rather than as
 * a silently unmoving sprite in a world.
 */
public class SceneGraphTest {

	private static final String SCENE = "gpu-node-address";
	private static final int CAP = 4096;

	private static void ship(ServerScene server, SceneMirror mirror) throws Exception {
		SceneBatch batch = server.sealBatch();
		if (batch == null) {
			return;
		}
		assertTrue(mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(batch))));
	}

	/** A scene with its implicit display canvas + node, as TileEntityGpu2 builds one. */
	private static int[] withDisplay(ServerScene scene) {
		int res = scene.createCanvas(512, 288, CAP);
		int node = scene.createNode(V2Wire.NODE_CANVAS, res);
		return new int[] { res, node };
	}

	@Test
	public void nodePropertiesConvergeThroughTheCodec() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		server.setCurrentTick(1);
		withDisplay(server);
		byte[] px = new byte[8 * 8 * 4];
		int tex = server.createTexture(8, 8, px);
		int sprite = server.createNode(V2Wire.NODE_SPRITE, tex);

		server.setTransform(sprite, 12.5, -3.25, 1.5, 2.0, 0.5);
		server.setZ(sprite, 7);
		server.setVisible(sprite, false);
		server.setTint(sprite, 0x80FF8040);
		ship(server, mirror);

		assertTrue("scene graph diverged", server.state().contentEquals(mirror.state()));
		SceneNode n = mirror.state().nodes.get(sprite);
		assertNotNull(n);
		assertEquals(12.5, n.x, 1e-9);
		assertEquals(-3.25, n.y, 1e-9);
		assertEquals(1.5, n.rot, 1e-9);
		assertEquals(2.0, n.sx, 1e-9);
		assertEquals(0.5, n.sy, 1e-9);
		assertEquals(7, n.z);
		assertFalse(n.visible);
		assertEquals(0x80FF8040, n.tint);
	}

	@Test
	public void aSpriteMaySourceAnOffscreenCanvas() throws Exception {
		// The v2 answer to OCL2's "textures are framebuffers": draw into a canvas, then show
		// it through a sprite. If the ref were restricted to textures this would throw.
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		server.setCurrentTick(1);
		withDisplay(server);
		int offscreen = server.createCanvas(64, 64, CAP);
		int sprite = server.createNode(V2Wire.NODE_SPRITE, offscreen);
		ship(server, mirror);

		assertTrue(server.state().contentEquals(mirror.state()));
		assertEquals(offscreen, mirror.state().nodes.get(sprite).ref);
	}

	@Test
	public void addingNodesDoesNotDisplaceTheDisplayCanvas() throws Exception {
		// The invariant DisplayCanvasTest pins, exercised through the operations the new Lua
		// API actually performs. Offscreen canvases are exactly what makes it fragile.
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		server.setCurrentTick(1);
		withDisplay(server);

		int offscreen = server.createCanvas(64, 64, CAP);
		server.createNode(V2Wire.NODE_CANVAS, offscreen);
		byte[] px = new byte[4 * 4 * 4];
		server.createNode(V2Wire.NODE_SPRITE, server.createTexture(4, 4, px));
		ship(server, mirror);

		assertEquals(512, server.state().displayCanvas().width);
		assertEquals(288, server.state().displayCanvas().height);
		assertEquals("mirror disagrees on which canvas is the display",
				512, mirror.state().displayCanvas().width);
	}

	@Test
	public void freeingANodeRemovesItFromTheMirror() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		server.setCurrentTick(1);
		withDisplay(server);
		byte[] px = new byte[4 * 4 * 4];
		int sprite = server.createNode(V2Wire.NODE_SPRITE, server.createTexture(4, 4, px));
		ship(server, mirror);
		assertNotNull(mirror.state().nodes.get(sprite));

		server.setCurrentTick(2);
		server.freeNode(sprite);
		ship(server, mirror);
		assertTrue(server.state().contentEquals(mirror.state()));
		assertFalse("freed node still in the mirror", mirror.state().nodes.containsKey(sprite));
	}

	@Test
	public void nodeCountIsBounded() {
		// Without this the id space (2^31) is the only bound, and every node costs server
		// memory, snapshot bytes to every watcher, and per-frame client work.
		ServerScene server = new ServerScene(SCENE);
		withDisplay(server);
		int budget = ServerScene.MAX_NODES - server.state().nodes.size();
		for (int i = 0; i < budget; i++) {
			server.createNode(V2Wire.NODE_GROUP, 0); // ref 0 = no resource
		}
		assertEquals(ServerScene.MAX_NODES, server.state().nodes.size());
		try {
			server.createNode(V2Wire.NODE_GROUP, 0);
			fail("expected the node limit to be enforced");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("node limit"));
		}
	}

	@Test
	public void freeingUnderTheLimitLetsAllocationResume() {
		// The cap must be on LIVE nodes, not on ids ever handed out — otherwise a program that
		// churns nodes each frame dies after 4096 frames rather than 4096 live nodes.
		ServerScene server = new ServerScene(SCENE);
		withDisplay(server);
		int budget = ServerScene.MAX_NODES - server.state().nodes.size();
		int first = -1;
		for (int i = 0; i < budget; i++) {
			int id = server.createNode(V2Wire.NODE_GROUP, 0);
			if (i == 0) {
				first = id;
			}
		}
		server.freeNode(first);
		int replacement = server.createNode(V2Wire.NODE_GROUP, 0);
		assertTrue("a freed slot must be reusable", replacement > 0);
		assertEquals(ServerScene.MAX_NODES, server.state().nodes.size());
	}

	@Test
	public void aNodeCannotReferenceAResourceThatDoesNotExist() {
		ServerScene server = new ServerScene(SCENE);
		withDisplay(server);
		try {
			server.createNode(V2Wire.NODE_SPRITE, 9999);
			fail("expected an unknown resource ref to be rejected");
		} catch (IllegalStateException expected) {
			// intended
		}
	}

	@Test
	public void freeingACanvasLeavesItsNodeDanglingWithoutDiverging() throws Exception {
		// Documented semantics: a dangling ref renders the pending placeholder, and both sides
		// dangle identically, so convergence is unaffected. Asserted so it stays deliberate.
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		server.setCurrentTick(1);
		withDisplay(server);
		int offscreen = server.createCanvas(64, 64, CAP);
		int node = server.createNode(V2Wire.NODE_CANVAS, offscreen);
		ship(server, mirror);

		server.setCurrentTick(2);
		server.freeResource(offscreen);
		ship(server, mirror);

		assertTrue(server.state().contentEquals(mirror.state()));
		assertNotNull("the node should survive its resource", mirror.state().nodes.get(node));
		assertFalse(mirror.state().resources.containsKey(offscreen));
	}
}
