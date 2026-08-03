package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;

public class BatchCodecTest {

	private static SceneBatch sampleBatch() {
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.ResourceCreate(1, V2Wire.RES_CANVAS, 512, 288, 0, 0, 4096));
		deltas.add(new Delta.ResourceCreate(2, V2Wire.RES_TEXTURE, 16, 16, 1024, 0x123456789abcdefL, 0));
		deltas.add(new Delta.NodeCreate(1, V2Wire.NODE_CANVAS, 1));
		deltas.add(new Delta.NodeCreate(2, V2Wire.NODE_SPRITE, 2));
		deltas.add(new Delta.NodeProps(2, V2Wire.PROP_X | V2Wire.PROP_Y | V2Wire.PROP_TINT,
				new double[] { 12.5, -3.25, (double) (0xFF00FF00L) }));
		ArrayList<CanvasCommand> cmds = new ArrayList<CanvasCommand>();
		cmds.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, 255, 128, 0, 255));
		cmds.add(CanvasCommand.of(V2Wire.OP_FILL));
		cmds.add(CanvasCommand.of(V2Wire.OP_LINE, 0, 0, 100, 50));
		cmds.add(CanvasCommand.text(4, 8, "héllo wörld"));
		cmds.add(CanvasCommand.of(V2Wire.OP_DRAW_TEXTURE_SUB, 2, 0, 0, 4, 4, 8, 8));
		cmds.add(CanvasCommand.of(V2Wire.OP_PUSH));
		cmds.add(CanvasCommand.of(V2Wire.OP_ROTATE, 0.5));
		cmds.add(CanvasCommand.of(V2Wire.OP_POP));
		deltas.add(new Delta.CanvasAppend(1, cmds));
		deltas.add(new Delta.CanvasPublish(1, cmds));
		deltas.add(new Delta.NodeFree(2));
		deltas.add(new Delta.ResourceFree(2));
		deltas.add(new Delta.SceneProp(7, new byte[] { 1, 2, 3 }));
		return new SceneBatch("aaaa-bbbb-cccc-dddd", 5, 41, 123456789L, deltas);
	}

	@Test
	public void roundTripPreservesEverything() throws Exception {
		SceneBatch batch = sampleBatch();
		SceneBatch decoded = BatchCodec.decode(BatchCodec.encode(batch));
		assertEquals(batch, decoded);
		assertEquals(5, decoded.epoch);
		assertEquals(41, decoded.seq);
		assertEquals(123456789L, decoded.serverTick);
	}

	@Test
	public void rejectsWrongProtocolVersion() {
		byte[] data = BatchCodec.encode(sampleBatch());
		data[1] = (byte) (data[1] + 1); // bump the version short's low byte
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("version"));
		}
	}

	@Test
	public void rejectsTruncation() {
		byte[] data = BatchCodec.encode(sampleBatch());
		for (int cut = 1; cut < data.length; cut += 7) {
			try {
				BatchCodec.decode(Arrays.copyOf(data, cut));
				fail("expected CodecException at cut " + cut);
			} catch (CodecException expected) {
				// every truncation point must fail cleanly
			}
		}
	}

	@Test
	public void rejectsGarbageWithoutHugeAllocation() {
		byte[] data = BatchCodec.encode(sampleBatch());
		// Corrupt the delta count to a huge value; decode must throw, not OOM.
		// Header: short version + UTF(2+19) + int epoch + int seq + long tick = 39 bytes in.
		int countOffset = 2 + 2 + 19 + 4 + 4 + 8;
		data[countOffset] = (byte) 0x7F;
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void rejectsUnknownDeltaType() throws Exception {
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.NodeFree(1));
		byte[] data = BatchCodec.encode(new SceneBatch("s", 3, 1, 0L, deltas));
		// Delta type byte: short + UTF("s": 2+1) + int epoch + int seq + long tick + int count.
		int typeOffset = 2 + 3 + 4 + 4 + 8 + 4;
		data[typeOffset] = 99;
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("delta type"));
		}
	}

	// Header for sceneId "s": short(2) + UTF(3) + epoch(4) + seq(4) + tick(8) + count(4) = 25.
	private static final int FIRST_DELTA_OFFSET = 25;

	private static byte[] singleDelta(Delta delta) {
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(delta);
		return BatchCodec.encode(new SceneBatch("s", 3, 1, 0L, deltas));
	}

	@Test
	public void rejectsTrailingGarbage() {
		byte[] data = BatchCodec.encode(sampleBatch());
		byte[] extended = Arrays.copyOf(data, data.length + 100);
		Arrays.fill(extended, data.length, extended.length, (byte) 0xEE);
		try {
			BatchCodec.decode(extended);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("Trailing"));
		}
	}

	@Test
	public void rejectsUnknownNodeType() {
		byte[] data = singleDelta(new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0));
		data[FIRST_DELTA_OFFSET + 1 + 4] = 99; // [type byte][int nodeId][byte nodeType]
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("node type"));
		}
	}

	@Test
	public void rejectsUnknownResourceType() {
		byte[] data = singleDelta(new Delta.ResourceCreate(1, V2Wire.RES_TEXTURE, 4, 4, 64, 1L, 0));
		data[FIRST_DELTA_OFFSET + 1 + 4] = 77; // [type byte][int resId][byte resType]
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("resource type"));
		}
	}

	@Test
	public void rejectsUnknownPropMaskBits() {
		byte[] data = singleDelta(new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 1 }));
		// [type byte][int nodeId][int mask]: set a bit above KNOWN_PROPS_MASK in the mask.
		// Byte index 2 of the big-endian mask covers bits 15..8, so 0x02 sets bit 9 — the
		// lowest bit still unknown now that PROP_TELEPORT claimed bit 8 and widened
		// KNOWN_PROPS_MASK to 0x1FF. (This line previously wrote 1 here and claimed it made
		// the mask 0x00010001; it actually set bit 8, which is why widening the mask turned
		// this into a truncation failure rather than the mask rejection it is testing.)
		data[FIRST_DELTA_OFFSET + 1 + 4 + 2] = 2; // mask becomes 0x00000201
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("mask"));
		}
	}

	@Test
	public void rejectsNegativeDeltaCount() {
		byte[] data = BatchCodec.encode(sampleBatch());
		int countOffset = 2 + 2 + 19 + 4 + 4 + 8;
		data[countOffset] = (byte) 0x80;
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void epochZeroIsRejected() {
		byte[] data = singleDelta(new Delta.NodeFree(1));
		// Epoch int sits right after [short version][UTF "s"]: offsets 5..8.
		for (int i = 5; i <= 8; i++) {
			data[i] = 0;
		}
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("Epoch"));
		}
	}

	@Test
	public void emptyBatchRoundTrips() throws Exception {
		// The codec allows zero deltas; the MIRROR rejects empty in-order batches — that rule
		// lives in SceneMirror, pinned by MirrorOrderingTest.
		SceneBatch empty = new SceneBatch("s", 9, 7, 3L, new ArrayList<Delta>());
		assertEquals(empty, BatchCodec.decode(BatchCodec.encode(empty)));
	}

	@Test
	public void tintSignEdgeValuesRoundTripExactly() throws Exception {
		long[] edges = { 0x80000000L, 0xFF000000L, 0xFFFFFFFFL, 0x00000001L, 0x7FFFFFFFL };
		for (long tint : edges) {
			SceneBatch batch = new SceneBatch("s", 2, 1, 0L, java.util.Collections.<Delta>singletonList(
					new Delta.NodeProps(1, V2Wire.PROP_TINT, new double[] { (double) tint })));
			SceneBatch decoded = BatchCodec.decode(BatchCodec.encode(batch));
			double value = ((Delta.NodeProps) decoded.deltas.get(0)).values[0];
			assertEquals((int) tint, (int) (long) value);
		}
	}
}
