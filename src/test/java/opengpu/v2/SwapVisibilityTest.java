package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.ServerScene;

/**
 * The double-buffer swap: the atomicity primitive for a frame too large to arrive in one call.
 *
 * <h2>What this buys, and why the obvious alternative was rejected</h2>
 * A chunked publish is N independent direct callbacks, each taking and releasing {@code sceneLock}
 * on its own, while the seal runs on the server thread at tick END. A seal can therefore fall
 * between chunk k and k+1 and ship a batch holding chunk 1's destructive publish alone, which
 * every watcher renders as a partial frame. That is a TIMING property, so no byte budget can
 * remove it — the 2026-08-04 constants fix took tearing from certain to rare and stopped there.
 *
 * Server-side frame assembly was designed and rejected. It caps an atomic frame at what one batch
 * carries; it must charge bytes before anything is staged, which permanently wedges the submit
 * budget because {@link ServerScene#sealBatch} returns early on an empty staged list WITHOUT
 * resetting its counters; and it narrows {@code append}, whose command-cap precheck is
 * deliberately compaction-blind so that both wire sides decide identically before mutating.
 *
 * Drawing into a hidden node needs none of that: content assembled where nothing is looking does
 * not have to be atomic, only the reveal does.
 *
 * <h2>What these tests pin</h2>
 * Every test here is headless. The mechanism is pure scene/delta semantics, which is the half of
 * this feature that a JVM test CAN see — the callback wiring on {@code TileEntityGpu2} needs Forge
 * and is in-game only, so that half is deliberately not claimed here.
 */
public class SwapVisibilityTest {

	private static final String SCENE = "swap-scene";
	private static final int CAP = 4096;

	private static ServerScene freshScene() {
		ServerScene scene = new ServerScene(SCENE);
		scene.setCurrentTick(1);
		return scene;
	}

	private static byte[] pack(int fillCount) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(fillCount * 2);
		for (int i = 0; i < fillCount; i++) {
			out.writeByte(V2Wire.OP_SET_COLOR);
			out.writeDouble(i % 255);
			out.writeDouble(0);
			out.writeDouble(0);
			out.writeDouble(255);
			out.writeByte(V2Wire.OP_FILL_RECT);
			out.writeDouble(i % 40);
			out.writeDouble(i % 30);
			out.writeDouble(4);
			out.writeDouble(4);
		}
		out.flush();
		return bytes.toByteArray();
	}

	private static SceneBatch ship(ServerScene server, SceneMirror mirror) throws Exception {
		SceneBatch batch = server.sealBatch();
		if (batch == null) {
			return null;
		}
		assertTrue(mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(batch))));
		return batch;
	}

	/** The command list a mirror would actually replay for a node: empty when it is hidden. */
	private static List<CanvasCommand> visibleThrough(SceneMirror mirror, int nodeId) {
		SceneNode node = mirror.state().nodes.get(Integer.valueOf(nodeId));
		assertNotNull("the mirror must know this node", node);
		if (!node.visible) {
			return new ArrayList<CanvasCommand>();
		}
		return new ArrayList<CanvasCommand>(
				mirror.state().resources.get(Integer.valueOf(node.ref)).canvas.visibleCommands());
	}

	@Test
	public void bothDeltasLandInOneBatchWithHideFirst() throws Exception {
		ServerScene server = freshScene();
		int frontCanvas = server.createCanvas(64, 64, CAP);
		int backCanvas = server.createCanvas(64, 64, CAP);
		int front = server.createNode(V2Wire.NODE_CANVAS, frontCanvas);
		int back = server.createNode(V2Wire.NODE_CANVAS, backCanvas);
		server.setVisible(back, false);
		server.sealBatch();

		server.swapVisibility(front, back);
		SceneBatch batch = server.sealBatch();

		assertNotNull("the swap must stage something", batch);
		assertEquals("exactly two deltas, so nothing else rides along", 2, batch.deltas.size());
		// Order matters: a replay must never have both visible, even for one delta.
		assertEquals(front, ((Delta.NodeProps) batch.deltas.get(0)).nodeId);
		assertEquals(back, ((Delta.NodeProps) batch.deltas.get(1)).nodeId);
		assertEquals(0.0, ((Delta.NodeProps) batch.deltas.get(0)).values[0], 1e-9);
		assertEquals(1.0, ((Delta.NodeProps) batch.deltas.get(1)).values[0], 1e-9);
	}

	@Test
	public void anUnknownNodeStagesNOTHING() throws Exception {
		// The assertion server-side frame assembly could never make. applyAndStage applies to
		// state AND appends to `staged` with no rollback anywhere, so partial application is
		// permanent — which is precisely why both ids are validated before either is staged.
		ServerScene server = freshScene();
		int canvas = server.createCanvas(64, 64, CAP);
		int front = server.createNode(V2Wire.NODE_CANVAS, canvas);
		server.sealBatch();

		try {
			server.swapVisibility(front, 9999);
			fail("an unknown show-node must be refused");
		} catch (IllegalStateException expected) {
		}

		assertFalse("a refused swap must leave nothing staged", server.hasStagedDeltas());
		assertTrue("and must not have hidden the front buffer",
				server.state().nodes.get(Integer.valueOf(front)).visible);
	}

	@Test
	public void swappingANodeWithItselfIsRefusedAndStagesNothing() throws Exception {
		// Otherwise the pair would be hide-then-show on one node: a no-op that still costs two
		// deltas, and a caller bug that would look like it worked.
		ServerScene server = freshScene();
		int canvas = server.createCanvas(64, 64, CAP);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		server.sealBatch();

		try {
			server.swapVisibility(node, node);
			fail("swapping a node with itself must be refused");
		} catch (IllegalArgumentException expected) {
		}
		assertFalse(server.hasStagedDeltas());
		assertTrue(server.state().nodes.get(Integer.valueOf(node)).visible);
	}

	@Test
	public void aBackBufferFilledAcrossMANYSEALEDBATCHESIsNeverVisibleUntilTheSwap() throws Exception {
		// THE headline test, and the whole reason the feature exists.
		//
		// The frame is built with seals driven BETWEEN the chunks -- the exact interleaving that
		// tears a chunked publish today, since each chunk is its own batch and a mirror renders
		// whatever it has after each one. Because the target node is hidden, the mirror's visible
		// output must be byte-identical across every one of those batches, and change exactly
		// once: at the swap.
		ServerScene server = freshScene();
		SceneMirror mirror = new SceneMirror(SCENE);

		int frontCanvas = server.createCanvas(64, 64, CAP);
		int backCanvas = server.createCanvas(64, 64, CAP);
		int front = server.createNode(V2Wire.NODE_CANVAS, frontCanvas);
		int back = server.createNode(V2Wire.NODE_CANVAS, backCanvas);
		server.setVisible(back, false);

		// The front buffer holds the frame the viewer is currently looking at.
		List<CanvasCommand> frontFrame = BatchCodec.decodeCommandList(pack(3));
		server.submitCanvas(frontCanvas, frontFrame, true, 200);
		ship(server, mirror);

		List<CanvasCommand> onScreenBefore = visibleThrough(mirror, front);
		assertEquals("the front buffer is showing its 6 commands", 6, onScreenBefore.size());

		// Now compose a much larger frame into the BACK buffer, one chunk per sealed batch.
		List<CanvasCommand> firstChunk = BatchCodec.decodeCommandList(pack(20));
		server.submitCanvas(backCanvas, firstChunk, true, 1400);
		for (int chunk = 0; chunk < 6; chunk++) {
			server.setCurrentTick(2 + chunk);
			assertNotNull("each chunk seals as its own batch", ship(server, mirror));

			assertEquals("the viewer's frame must not move while the back buffer fills",
					onScreenBefore.size(), visibleThrough(mirror, front).size());
			assertTrue("and the back buffer must stay invisible throughout",
					visibleThrough(mirror, back).isEmpty());

			server.submitCanvas(backCanvas, BatchCodec.decodeCommandList(pack(20)), false, 1400);
		}
		server.setCurrentTick(20);
		ship(server, mirror);

		// One call reveals the whole thing.
		server.swapVisibility(front, back);
		SceneBatch swap = ship(server, mirror);
		assertNotNull(swap);
		assertEquals("the reveal is exactly two deltas", 2, swap.deltas.size());

		assertTrue("the old frame is gone", visibleThrough(mirror, front).isEmpty());
		assertEquals("and the new one appears whole, all 7 chunks of it",
				7 * 40, visibleThrough(mirror, back).size());
		assertTrue("server and mirror agree", server.state().contentEquals(mirror.state()));
	}

	@Test
	public void theSwapSurvivesTheRealCodecAndConverges() throws Exception {
		// Convergence is structural here -- DeltaApplier is the single mutation path on both
		// sides -- so this is really checking that NodeProps with PROP_VISIBLE round-trips the
		// encoder, which nothing else in this file would catch if the mask were mis-packed.
		ServerScene server = freshScene();
		SceneMirror mirror = new SceneMirror(SCENE);
		int a = server.createCanvas(32, 32, CAP);
		int b = server.createCanvas(32, 32, CAP);
		int na = server.createNode(V2Wire.NODE_CANVAS, a);
		int nb = server.createNode(V2Wire.NODE_CANVAS, b);
		server.setVisible(nb, false);
		ship(server, mirror);

		server.swapVisibility(na, nb);
		ship(server, mirror);

		assertFalse(mirror.state().nodes.get(Integer.valueOf(na)).visible);
		assertTrue(mirror.state().nodes.get(Integer.valueOf(nb)).visible);
		assertTrue(server.state().contentEquals(mirror.state()));

		// And it is symmetric: swapping back returns exactly to the starting state.
		server.swapVisibility(nb, na);
		ship(server, mirror);
		assertTrue(mirror.state().nodes.get(Integer.valueOf(na)).visible);
		assertFalse(mirror.state().nodes.get(Integer.valueOf(nb)).visible);
		assertTrue(server.state().contentEquals(mirror.state()));
	}
}
