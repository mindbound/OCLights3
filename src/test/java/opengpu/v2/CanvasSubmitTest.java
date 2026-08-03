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
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;
import opengpu.v2.scene.SceneCanvas;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.ServerScene;

/**
 * Offscreen drawing via canvasSubmit — a whole packed command list applied to a named canvas.
 *
 * The reason this exists rather than a per-canvas recorder is worth restating where the tests
 * live: the immediate-mode recorder is six interacting mutable fields that have already
 * produced four shipped defects in this repo, and generalising it would have multiplied that
 * machine by N canvases plus a new persisted format for it. Submitting a finished list adds no
 * server-side mode state at all, so what is left to test is the payload boundary and the
 * allowance — which is exactly what is below.
 *
 * The payloads here are packed BY HAND in the wire layout a Lua program would produce, not
 * built through BatchCodec's own writer. That is deliberate: a test that encodes with the same
 * code it decodes with would still pass if the format silently changed under both, and this
 * call is the one place where an outside program writes bytes we then trust.
 */
public class CanvasSubmitTest {

	private static final String SCENE = "gpu-submit-address";
	private static final int CAP = 4096;

	/** Packs commands the way a Lua caller must: count, then op + fixed-arity doubles. */
	private static final class Packer {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(bytes);
		int count;

		Packer op(byte op, double... args) throws IOException {
			if (V2Wire.canvasOpArgCount(op) != args.length) {
				throw new IllegalArgumentException("test packs op " + op + " with the wrong arity");
			}
			out.writeByte(op);
			for (double a : args) {
				out.writeDouble(a);
			}
			count++;
			return this;
		}

		Packer text(double x, double y, String s) throws IOException {
			out.writeByte(V2Wire.OP_DRAW_TEXT);
			for (int i = 0; i < V2Wire.canvasOpArgCount(V2Wire.OP_DRAW_TEXT); i++) {
				out.writeDouble(i == 0 ? x : i == 1 ? y : 0);
			}
			out.writeUTF(s);
			count++;
			return this;
		}

		byte[] done() throws IOException {
			out.flush();
			ByteArrayOutputStream framed = new ByteArrayOutputStream();
			DataOutputStream head = new DataOutputStream(framed);
			head.writeInt(count);
			head.write(bytes.toByteArray());
			head.flush();
			return framed.toByteArray();
		}
	}

	private static byte[] sampleRect() throws IOException {
		// Colour channels are integral 0-255 on the wire, not floats: CanvasCommand normalizes
		// them so a recorded SET_COLOR is byte-identical to a compaction-emitted one.
		return new Packer()
				.op(V2Wire.OP_SET_COLOR, 255, 0, 0, 255)
				.op(V2Wire.OP_FILL_RECT, 4, 8, 16, 32)
				.done();
	}

	private static void ship(ServerScene server, SceneMirror mirror) throws Exception {
		SceneBatch batch = server.sealBatch();
		if (batch == null) {
			return;
		}
		assertTrue(mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(batch))));
	}

	private static ServerScene freshScene() {
		ServerScene scene = new ServerScene(SCENE);
		scene.setCurrentTick(1);
		return scene;
	}

	@Test
	public void aPackedListDecodesToTheCommandsItNames() throws Exception {
		List<CanvasCommand> decoded = BatchCodec.decodeCommandList(sampleRect());

		assertEquals(2, decoded.size());
		assertEquals(V2Wire.OP_SET_COLOR, decoded.get(0).op);
		assertEquals(4, decoded.get(0).args.length);
		assertEquals(V2Wire.OP_FILL_RECT, decoded.get(1).op);
		assertEquals(4, decoded.get(1).args[0], 1e-9);
		assertEquals(32, decoded.get(1).args[3], 1e-9);
	}

	@Test
	public void drawTextCarriesItsStringThroughTheList() throws Exception {
		// OP_DRAW_TEXT is the one op with a trailing UTF field, so it is the one that breaks if
		// the reader's arity table and the writer's disagree by a single argument.
		List<CanvasCommand> decoded = BatchCodec.decodeCommandList(
				new Packer().text(2, 3, "hello").done());
		assertEquals(1, decoded.size());
		assertEquals("hello", decoded.get(0).text);
	}

	private static void expectReject(byte[] payload, String fragment) {
		try {
			BatchCodec.decodeCommandList(payload);
			fail("expected a rejection mentioning: " + fragment);
		} catch (CodecException expected) {
			assertTrue("wrong message: " + expected.getMessage(),
					expected.getMessage().toLowerCase().contains(fragment));
		}
	}

	@Test
	public void malformedPayloadsAreRejectedWholeRatherThanPartlyApplied() throws Exception {
		byte[] good = sampleRect();

		byte[] truncated = new byte[good.length - 3];
		System.arraycopy(good, 0, truncated, 0, truncated.length);
		expectReject(truncated, "truncated");

		// Trailing data is refused rather than ignored: a caller whose packing is off by a field
		// must hear about it, not have the remainder silently dropped every frame.
		byte[] trailing = new byte[good.length + 1];
		System.arraycopy(good, 0, trailing, 0, good.length);
		expectReject(trailing, "trailing");

		ByteArrayOutputStream b = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(b);
		d.writeInt(1);
		d.writeByte(0x7E); // not an op
		d.flush();
		expectReject(b.toByteArray(), "unknown canvas op");

		// A hostile count must not make the reader reserve for a list it never sends.
		ByteArrayOutputStream huge = new ByteArrayOutputStream();
		DataOutputStream hd = new DataOutputStream(huge);
		hd.writeInt(Integer.MAX_VALUE);
		hd.flush();
		expectReject(huge.toByteArray(), "out of range");

		ByteArrayOutputStream negative = new ByteArrayOutputStream();
		DataOutputStream nd = new DataOutputStream(negative);
		nd.writeInt(-1);
		nd.flush();
		expectReject(negative.toByteArray(), "out of range");

		expectReject(null, "required");
	}

	@Test
	public void aDeclaredCountPastTheBoundIsRefusedBeforeAnythingIsAllocated() throws Exception {
		// A payload's BYTE length does not bound its command COUNT: the zero-arity ops are one
		// byte each, so a legal 64 KiB submit can declare 65,532 commands -- sixteen times the
		// default canvas cap. Without a count bound every one of those is allocated and scanned
		// before the cap rejects the list, and the rejection costs the caller nothing, so the
		// work is repeatable for as long as the call budget lasts.
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(b);
		d.writeInt(65532);
		for (int i = 0; i < 65532; i++) {
			d.writeByte(V2Wire.OP_FILL);
		}
		d.flush();
		byte[] swarm = b.toByteArray();
		assertTrue("this is a LEGAL payload by size", swarm.length <= V2Wire.MAX_SUBMIT_BYTES);

		// Unbounded, it decodes: that is the cost the bound exists to refuse.
		assertEquals(65532, BatchCodec.decodeCommandList(swarm).size());

		try {
			BatchCodec.decodeCommandList(swarm, CAP);
			fail("a count past the bound must be refused");
		} catch (CodecException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("exceeds the limit"));
		}

		// The bound is read from the first four bytes, so a lying header is refused even when no
		// command data follows it at all -- there is nothing to allocate and nothing to scan.
		ByteArrayOutputStream liar = new ByteArrayOutputStream();
		DataOutputStream ld = new DataOutputStream(liar);
		ld.writeInt(65532);
		ld.flush();
		try {
			BatchCodec.decodeCommandList(liar.toByteArray(), CAP);
			fail("a lying count must be refused on the header alone");
		} catch (CodecException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("exceeds the limit"));
		}

		// Exactly at the bound still decodes -- an off-by-one here would refuse a full frame.
		ByteArrayOutputStream exact = new ByteArrayOutputStream();
		DataOutputStream ed = new DataOutputStream(exact);
		ed.writeInt(CAP);
		for (int i = 0; i < CAP; i++) {
			ed.writeByte(V2Wire.OP_FILL);
		}
		ed.flush();
		assertEquals(CAP, BatchCodec.decodeCommandList(exact.toByteArray(), CAP).size());
	}

	@Test
	public void aSubmittedFrameConvergesOnTheClient() throws Exception {
		ServerScene server = freshScene();
		SceneMirror mirror = new SceneMirror(SCENE);
		int display = server.createCanvas(512, 288, CAP);
		server.createNode(V2Wire.NODE_CANVAS, display);
		int offscreen = server.createCanvas(64, 64, CAP);
		server.createNode(V2Wire.NODE_CANVAS, offscreen);
		ship(server, mirror);

		byte[] payload = sampleRect();
		assertTrue(server.submitCanvas(offscreen, BatchCodec.decodeCommandList(payload), true,
				payload.length));
		ship(server, mirror);

		assertTrue("submitted frame diverged", server.state().contentEquals(mirror.state()));
		assertNotNull(mirror.state().resources.get(offscreen).canvas);
		assertEquals(2, mirror.state().resources.get(offscreen).canvas.visibleCommands().size());
	}

	@Test
	public void publishReplacesAndAppendAdds() throws Exception {
		ServerScene server = freshScene();
		SceneMirror mirror = new SceneMirror(SCENE);
		int canvas = server.createCanvas(64, 64, CAP);
		server.createNode(V2Wire.NODE_CANVAS, canvas);

		byte[] payload = sampleRect();
		List<CanvasCommand> cmds = BatchCodec.decodeCommandList(payload);
		assertTrue(server.submitCanvas(canvas, cmds, true, payload.length));
		assertEquals(2, server.state().resources.get(canvas).canvas.visibleCommands().size());

		assertTrue("append must add to the standing frame",
				server.submitCanvas(canvas, cmds, false, payload.length));
		assertEquals(4, server.state().resources.get(canvas).canvas.visibleCommands().size());

		assertTrue("publish must replace it, not grow it",
				server.submitCanvas(canvas, cmds, true, payload.length));
		assertEquals(2, server.state().resources.get(canvas).canvas.visibleCommands().size());

		ship(server, mirror);
		assertTrue(server.state().contentEquals(mirror.state()));
	}

	@Test
	public void theAllowanceIsSpentAndRefreshedPerTick() throws Exception {
		ServerScene server = freshScene();
		int canvas = server.createCanvas(64, 64, CAP);
		server.createNode(V2Wire.NODE_CANVAS, canvas);

		assertEquals(V2Wire.MAX_SUBMIT_BYTES, server.submitBudgetRemaining());

		byte[] payload = sampleRect();
		List<CanvasCommand> cmds = BatchCodec.decodeCommandList(payload);
		int spent = 0;
		while (server.submitCanvas(canvas, cmds, false, V2Wire.MAX_SUBMIT_BYTES / 4)) {
			spent += V2Wire.MAX_SUBMIT_BYTES / 4;
			assertTrue("the allowance must be finite", spent <= V2Wire.MAX_SUBMIT_BYTES);
		}
		assertEquals("a spent tick must report nothing left", 0, server.submitBudgetRemaining());
		assertFalse("a refused submit must stay refused within the tick",
				server.submitCanvas(canvas, cmds, false, 1));

		// The batch counter clears on the seal and the tick counter on the tick — BOTH, because
		// they diverge whenever a seal happens without a tick change or the reverse.
		server.sealBatch();
		server.setCurrentTick(2);
		assertEquals("the next tick must start clean",
				V2Wire.MAX_SUBMIT_BYTES, server.submitBudgetRemaining());
		assertTrue(server.submitCanvas(canvas, cmds, false, payload.length));
	}

	@Test
	public void aRefusedSubmitChangesNothing() throws Exception {
		// Back-pressure has to be inert. If a refused call had already mutated the canvas, the
		// caller's honest retry next tick would double-apply the frame.
		ServerScene server = freshScene();
		int canvas = server.createCanvas(64, 64, CAP);
		server.createNode(V2Wire.NODE_CANVAS, canvas);
		byte[] payload = sampleRect();
		List<CanvasCommand> cmds = BatchCodec.decodeCommandList(payload);

		assertFalse(server.submitCanvas(canvas, cmds, false, V2Wire.MAX_SUBMIT_BYTES + 1));
		assertEquals(0, server.state().resources.get(canvas).canvas.visibleCommands().size());
		assertEquals("a refused submit must not spend the allowance",
				V2Wire.MAX_SUBMIT_BYTES, server.submitBudgetRemaining());
	}

	@Test
	public void anOverCapListLeavesTheCanvasAndTheAllowanceUntouched() throws Exception {
		// The cap belongs to the canvas, the allowance to the tick; overrunning the first must
		// not quietly consume the second, or a program with one oversized frame would also lose
		// the throughput for the frames it could have drawn.
		ServerScene server = freshScene();
		int canvas = server.createCanvas(64, 64, 4);
		server.createNode(V2Wire.NODE_CANVAS, canvas);

		List<CanvasCommand> tooMany = new ArrayList<CanvasCommand>();
		for (int i = 0; i < 5; i++) {
			tooMany.add(new CanvasCommand(V2Wire.OP_FILL_RECT, new double[] { i, i, 1, 1 }, null));
		}
		try {
			server.submitCanvas(canvas, tooMany, true, 128);
			fail("a frame past the canvas cap must be refused");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().toLowerCase().contains("full"));
		}
		assertEquals(0, server.state().resources.get(canvas).canvas.visibleCommands().size());
		assertEquals(V2Wire.MAX_SUBMIT_BYTES, server.submitBudgetRemaining());
	}

	@Test
	public void aCanvasTracksItsEncodedSizeThroughEveryMutation() throws Exception {
		// The running total has to be maintained at all THREE places the visible list changes --
		// append, publish and compaction's truncate -- or the scene budget it feeds drifts from
		// reality and either over-admits or wedges a scene that is actually empty.
		ServerScene server = freshScene();
		int canvas = server.createCanvas(64, 64, CAP);
		SceneCanvas c = server.state().resources.get(canvas).canvas;
		assertEquals("a fresh canvas holds nothing", 0, c.encodedBytes());

		List<CanvasCommand> cmds = BatchCodec.decodeCommandList(sampleRect());
		long one = 0;
		for (CanvasCommand cmd : cmds) {
			one += cmd.encodedBytes();
		}
		server.canvasAppend(canvas, cmds);
		assertEquals(one, c.encodedBytes());
		server.canvasAppend(canvas, cmds);
		assertEquals("append accumulates", 2 * one, c.encodedBytes());
		server.canvasPublish(canvas, cmds);
		assertEquals("publish replaces", one, c.encodedBytes());

		// Compaction: an opaque full-canvas FILL truncates the list to [SET_COLOR, FILL], and the
		// byte total must fall with it rather than keep counting commands that are gone.
		List<CanvasCommand> opaqueFill = new ArrayList<CanvasCommand>();
		opaqueFill.add(new CanvasCommand(V2Wire.OP_SET_COLOR, new double[] { 255, 255, 255, 255 }, null));
		opaqueFill.add(new CanvasCommand(V2Wire.OP_FILL, new double[0], null));
		server.canvasAppend(canvas, opaqueFill);
		assertEquals("compaction must release the bytes it dropped",
				c.visibleCommands().get(0).encodedBytes() + c.visibleCommands().get(1).encodedBytes(),
				c.encodedBytes());
		assertEquals(2, c.visibleCommands().size());

		// A copy must carry the total, or a snapshot round-trip resets the budget to zero.
		assertEquals(c.encodedBytes(), c.copy().encodedBytes());
	}

	@Test
	public void textIsCountedAtItsRealWireCost() throws Exception {
		// The commandCap bounds COUNT; only this bounds SIZE. One OP_DRAW_TEXT slot can carry
		// MAX_TEXT_CHARS, so a canvas at its command cap can still be orders of magnitude larger
		// than the same cap filled with OP_FILL -- which is exactly how the display canvas could
		// build a batch past the decoder's inflate ceiling and get itself rejected wholesale.
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 1000; i++) {
			sb.append('x');
		}
		CanvasCommand text = CanvasCommand.text(0, 0, sb.toString());
		CanvasCommand fill = CanvasCommand.of(V2Wire.OP_FILL);
		assertEquals(1, fill.encodedBytes());
		assertTrue("a text slot must cost far more than a fill slot",
				text.encodedBytes() > 1000);
		assertTrue("the estimate must never UNDERSTATE the wire cost",
				text.encodedBytes() >= 1 + 16 + 2 + sb.length());
	}

	@Test
	public void theSceneWideStandingBudgetIsEnforced() throws Exception {
		// What this bounds is the RESYNC SNAPSHOT and the save file, not the tick. Before
		// canvasSubmit existed the total was vacuously bounded: nothing could put a command into
		// a non-display canvas, so every offscreen canvas was provably empty.
		ServerScene server = freshScene();
		assertEquals(V2Wire.MAX_STANDING_COMMAND_BYTES, server.standingBudgetRemaining());

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < V2Wire.MAX_TEXT_CHARS; i++) {
			sb.append('x');
		}
		List<CanvasCommand> fat = new ArrayList<CanvasCommand>();
		for (int i = 0; i < 64; i++) {
			fat.add(CanvasCommand.text(0, i, sb.toString()));
		}

		int filled = 0;
		for (int i = 0; i < 64; i++) {
			int canvas = server.createCanvas(32, 32, CAP);
			try {
				server.canvasAppend(canvas, fat);
				filled++;
			} catch (IllegalStateException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().contains("exceed"));
				break;
			}
		}
		assertTrue("the budget must stop this well before 64 canvases", filled < 64);
		assertTrue("and must let at least one legitimate canvas through", filled >= 1);
		assertTrue("the standing total must stay inside the bound",
				server.standingCommandBytes() <= V2Wire.MAX_STANDING_COMMAND_BYTES);
	}

	@Test
	public void aPublishOverAFullCanvasIsTheWayOut() throws Exception {
		// The refusal message tells the caller to publish over a canvas. That has to actually
		// work: a publish REPLACES, so it must release the target's bytes before charging the
		// new frame, or the advice would be a dead end.
		ServerScene server = freshScene();
		int canvas = server.createCanvas(64, 64, CAP);

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < V2Wire.MAX_TEXT_CHARS; i++) {
			sb.append('x');
		}
		List<CanvasCommand> fat = new ArrayList<CanvasCommand>();
		for (int i = 0; i < 70; i++) {
			fat.add(CanvasCommand.text(0, i, sb.toString()));
		}
		server.canvasPublish(canvas, fat);
		long held = server.standingCommandBytes();
		assertTrue("the fat frame should dominate the budget",
				held > V2Wire.MAX_STANDING_COMMAND_BYTES / 2);

		server.canvasPublish(canvas, BatchCodec.decodeCommandList(sampleRect()));
		assertTrue("publishing a small frame must release the old one",
				server.standingCommandBytes() < held);
		assertTrue(server.standingBudgetRemaining() > V2Wire.MAX_STANDING_COMMAND_BYTES / 2);
	}

	@Test
	public void submittingToATextureIsRefused() throws Exception {
		ServerScene server = freshScene();
		int tex = server.createTexture(8, 8, new byte[8 * 8 * 4]);
		try {
			server.submitCanvas(tex, BatchCodec.decodeCommandList(sampleRect()), true, 32);
			fail("a texture is not a canvas");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("Not a canvas"));
		}
		try {
			server.submitCanvas(9999, BatchCodec.decodeCommandList(sampleRect()), true, 32);
			fail("an unknown id is not a canvas");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("Not a canvas"));
		}
	}

	@Test
	public void aSubmittedListRidesTheRealBatchCodec() throws Exception {
		// The submit path hands commands to the same delta encoder the immediate path uses. If a
		// command could be built by submit but not encoded, it would apply on the server and
		// vanish on the client — the exact shape of divergence the mirror exists to prevent.
		ServerScene server = freshScene();
		SceneMirror mirror = new SceneMirror(SCENE);
		int canvas = server.createCanvas(64, 64, CAP);
		server.createNode(V2Wire.NODE_CANVAS, canvas);
		int tex = server.createTexture(4, 4, new byte[4 * 4 * 4]);
		ship(server, mirror);

		byte[] payload = new Packer()
				.op(V2Wire.OP_SET_COLOR, 64, 128, 192, 255)
				.op(V2Wire.OP_PUSH)
				.op(V2Wire.OP_TRANSLATE, 3, 4)
				.op(V2Wire.OP_DRAW_TEXTURE, tex, 1, 2)
				.op(V2Wire.OP_POP)
				.text(5, 6, "submitted")
				.done();
		assertTrue(server.submitCanvas(canvas, BatchCodec.decodeCommandList(payload), true,
				payload.length));
		ship(server, mirror);

		assertTrue("a submitted frame diverged through the codec",
				server.state().contentEquals(mirror.state()));
	}
}
