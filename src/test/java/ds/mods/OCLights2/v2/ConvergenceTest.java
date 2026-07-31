package ds.mods.OCLights2.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import ds.mods.OCLights2.v2.protocol.BatchCodec;
import ds.mods.OCLights2.v2.protocol.SceneBatch;
import ds.mods.OCLights2.v2.protocol.V2Wire;
import ds.mods.OCLights2.v2.scene.CanvasCommand;
import ds.mods.OCLights2.v2.scene.SceneMirror;
import ds.mods.OCLights2.v2.scene.ServerScene;

/**
 * End-to-end convergence: every mutation on the authoritative scene, shipped through the
 * real codec (encode → bytes → decode), must reproduce identical state in the mirror.
 * This is the Stage A acceptance property the whole v2 design stakes correctness on.
 */
public class ConvergenceTest {

	private static final String SCENE = "gpu-node-address";

	private static void ship(ServerScene server, SceneMirror mirror) throws Exception {
		SceneBatch batch = server.sealBatch();
		if (batch == null)
			return;
		SceneBatch decoded = BatchCodec.decode(BatchCodec.encode(batch));
		assertTrue(mirror.applyBatch(decoded));
	}

	@Test
	public void fullScriptConverges() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);

		// Tick 1: canvas + node + first frame.
		server.setCurrentTick(1);
		int canvas = server.createCanvas(512, 288, 4096);
		int canvasNode = server.createNode(V2Wire.NODE_CANVAS, canvas);
		List<CanvasCommand> frame = new ArrayList<CanvasCommand>();
		frame.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, 255, 0, 0, 255));
		frame.add(CanvasCommand.of(V2Wire.OP_FILL));
		frame.add(CanvasCommand.text(10, 10, "hello"));
		server.canvasAppend(canvas, frame);
		ship(server, mirror);
		assertTrue(server.state().contentEquals(mirror.state()));

		// Tick 2: texture + sprite + transforms.
		server.setCurrentTick(2);
		byte[] pixels = new byte[8 * 8 * 4];
		for (int i = 0; i < pixels.length; i++) {
			pixels[i] = (byte) i;
		}
		int texture = server.createTexture(8, 8, pixels);
		int sprite = server.createNode(V2Wire.NODE_SPRITE, texture);
		server.setTransform(sprite, 100, 50, 0.25, 2, 2);
		server.setZ(sprite, 3);
		server.setTint(sprite, 0xCCFF8800);
		ship(server, mirror);
		assertTrue(server.state().contentEquals(mirror.state()));
		// Mirror has the texture meta but no bytes yet: the designed pending state.
		assertTrue(mirror.state().resources.get(texture).isPending());
		assertNotNull(server.state().resources.get(texture).bytes);
		assertEquals(server.state().resources.get(texture).hash,
				mirror.state().resources.get(texture).hash);

		// Body delivery is validated: wrong bytes are refused, correct bytes clear pending.
		mirror.clearDirty();
		assertTrue(!mirror.deliverResourceBody(texture, new byte[8 * 8 * 4]));
		assertTrue(mirror.state().resources.get(texture).isPending());
		assertTrue(mirror.deliverResourceBody(texture, server.state().resources.get(texture).bytes));
		assertTrue(!mirror.state().resources.get(texture).isPending());
		assertTrue(mirror.isDirty());

		// Tick 3: accumulate with compaction (fill truncates) + visibility toggle.
		server.setCurrentTick(3);
		List<CanvasCommand> more = new ArrayList<CanvasCommand>();
		more.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, 0, 0, 255, 255));
		more.add(CanvasCommand.of(V2Wire.OP_FILL));
		server.canvasAppend(canvas, more);
		server.setVisible(sprite, false);
		ship(server, mirror);
		assertTrue(server.state().contentEquals(mirror.state()));
		// Compaction ran identically on both sides.
		assertEquals(2, mirror.state().resources.get(canvas).canvas.visibleCommands().size());

		// Tick 4: frees.
		server.setCurrentTick(4);
		server.freeNode(sprite);
		server.freeResource(texture);
		ship(server, mirror);
		assertTrue(server.state().contentEquals(mirror.state()));
		assertNull(mirror.state().nodes.get(sprite));
		assertNull(mirror.state().resources.get(texture));

		// Empty tick seals nothing.
		assertNull(server.sealBatch());
		assertEquals(canvasNode, canvasNode); // silence unused warning
	}

	@Test
	public void lateJoinerViaSnapshotConverges() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror early = new SceneMirror(SCENE);

		server.setCurrentTick(1);
		int canvas = server.createCanvas(64, 64, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		int texture = server.createTexture(4, 4, new byte[4 * 4 * 4]);
		List<CanvasCommand> frame = new ArrayList<CanvasCommand>();
		frame.add(CanvasCommand.text(0, 0, "state"));
		server.canvasAppend(canvas, frame);
		ship(server, early);

		server.setCurrentTick(2);
		server.setTransform(node, 5, 6, 0, 1, 1);
		ship(server, early);

		// Late joiner: snapshot at current seq, then only future batches. Snapshots are
		// manifest-only — texture bytes arrive via the pending/body path, not the snapshot.
		SceneMirror late = new SceneMirror(SCENE);
		late.applySnapshot(server.snapshot());
		assertTrue(server.state().contentEquals(late.state()));
		assertTrue(late.state().resources.get(texture).isPending());
		assertTrue(late.deliverResourceBody(texture, server.state().resources.get(texture).bytes));

		server.setCurrentTick(3);
		server.setZ(node, -1);
		SceneBatch b3 = server.sealBatch();
		SceneBatch decoded = BatchCodec.decode(BatchCodec.encode(b3));
		assertTrue(early.applyBatch(decoded));
		assertTrue(late.applyBatch(decoded));

		assertTrue(server.state().contentEquals(early.state()));
		assertTrue(server.state().contentEquals(late.state()));
	}

	@Test
	public void freeWhileReferencedConverges() throws Exception {
		// Dangling refs are legal (render as placeholder); both sides must dangle identically.
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		server.setCurrentTick(1);
		int canvas = server.createCanvas(64, 64, 256);
		int texture = server.createTexture(4, 4, new byte[4 * 4 * 4]);
		List<CanvasCommand> draw = new ArrayList<CanvasCommand>();
		draw.add(CanvasCommand.of(V2Wire.OP_DRAW_TEXTURE, texture, 0, 0));
		server.canvasAppend(canvas, draw);
		ship(server, mirror);

		server.setCurrentTick(2);
		server.freeResource(texture);
		ship(server, mirror);
		assertTrue(server.state().contentEquals(mirror.state()));
		assertNull(mirror.state().resources.get(texture));
		assertEquals(1, mirror.state().resources.get(canvas).canvas.visibleCommands().size());
	}

	@Test
	public void unknownEmbeddedRefIsRejectedServerSide() {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 64, 256);
		List<CanvasCommand> draw = new ArrayList<CanvasCommand>();
		draw.add(CanvasCommand.of(V2Wire.OP_DRAW_TEXTURE, 999, 0, 0));
		try {
			server.canvasAppend(canvas, draw);
			throw new AssertionError("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("unknown resource"));
		}
	}

	@Test
	public void snapshotWithStagedDeltasIsRefused() {
		ServerScene server = new ServerScene(SCENE);
		server.createCanvas(64, 64, 256);
		try {
			server.snapshot();
			throw new AssertionError("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("Seal"));
		}
		server.sealBatch();
		server.snapshot(); // batch boundary: fine
	}

	@Test
	public void wraparoundConvergence() throws Exception {
		ServerScene server = new ServerScene(SCENE, Integer.MAX_VALUE - 1);
		SceneMirror mirror = new SceneMirror(SCENE, Integer.MAX_VALUE - 1);
		for (int i = 0; i < 4; i++) {
			server.setCurrentTick(i);
			server.createCanvas(16, 16, 64);
			ship(server, mirror);
		}
		// Crossed MAX_VALUE → MIN_VALUE without a spurious gap.
		assertTrue(server.state().contentEquals(mirror.state()));
		assertEquals(server.currentSeq(), mirror.lastSeq());
		assertTrue(server.currentSeq() < 0);
	}
}
