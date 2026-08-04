package opengpu.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;

/**
 * Wire-level validation for DELTA_TEX_WRITE. Every check here exists because the decoder
 * holds no scene state: it must reject a hostile header before allocating anything from it.
 */
public class TextureWriteCodecTest {

	private static final String SCENE = "s";
	private static final int EPOCH = 0x5EED;

	private static byte[] pattern(int len) {
		byte[] data = new byte[len];
		for (int i = 0; i < len; i++) {
			data[i] = (byte) (i * 13 + 7);
		}
		return data;
	}

	private static SceneBatch batchOf(Delta... deltas) {
		List<Delta> list = new ArrayList<Delta>();
		for (Delta d : deltas) {
			list.add(d);
		}
		return new SceneBatch(SCENE, EPOCH, 1, 100L, list);
	}

	@Test
	public void textureWriteRoundTrips() throws Exception {
		byte[] pixels = pattern(8 * 4 * 4);
		SceneBatch batch = batchOf(new Delta.TextureWrite(3, 9, 5, 6, 8, 4, pixels));
		SceneBatch decoded = BatchCodec.decode(BatchCodec.encode(batch));
		assertEquals(1, decoded.deltas.size());
		Delta.TextureWrite w = (Delta.TextureWrite) decoded.deltas.get(0);
		assertEquals(3, w.resId);
		assertEquals(9, w.version);
		assertEquals(5, w.x);
		assertEquals(6, w.y);
		assertEquals(8, w.w);
		assertEquals(4, w.h);
		assertArrayEquals(pixels, w.pixels);
		assertEquals(batch.deltas.get(0), decoded.deltas.get(0));
	}

	@Test
	public void deltaClonesItsPixelsSoLaterMutationCannotLeakIn() {
		byte[] pixels = pattern(4 * 4 * 4);
		Delta.TextureWrite w = new Delta.TextureWrite(1, 1, 0, 0, 4, 4, pixels);
		pixels[0] = (byte) ~pixels[0];
		assertEquals("the delta must not alias a caller-owned buffer",
				(byte) (0 * 13 + 7), w.pixels[0]);
	}

	@Test
	public void oversizedRegionIsRejectedAtConstruction() {
		try {
			// 128x64 RGBA = 32768 bytes, twice the per-call cap.
			new Delta.TextureWrite(1, 1, 0, 0, 128, 64, new byte[128 * 64 * 4]);
			fail("expected the per-call cap to be enforced");
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void payloadLengthMustMatchTheRect() {
		try {
			new Delta.TextureWrite(1, 1, 0, 0, 4, 4, new byte[4 * 4 * 4 - 1]);
			fail("expected a length mismatch to be rejected");
		} catch (IllegalArgumentException expected) {
		}
	}

	/** Hand-rolled batch so the decoder sees values a legitimate producer cannot emit. */
	private static byte[] forgeWrite(int resId, int version, int x, int y, int w, int h, int payloadLen) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(SCENE);
			out.writeInt(EPOCH);
			out.writeInt(1);
			out.writeLong(100L);
			out.writeInt(1); // one delta
			out.writeByte(V2Wire.DELTA_TEX_WRITE);
			out.writeInt(resId);
			out.writeInt(version);
			out.writeInt(x);
			out.writeInt(y);
			out.writeInt(w);
			out.writeInt(h);
			out.write(new byte[Math.max(0, payloadLen)]);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		return bytes.toByteArray();
	}

	private static void expectReject(byte[] data, String why) {
		try {
			BatchCodec.decode(data);
			fail("expected rejection: " + why);
		} catch (CodecException expected) {
		}
	}

	@Test
	public void decoderRejectsHostileHeaders() {
		expectReject(forgeWrite(1, 1, 0, 0, 0, 4, 0), "zero width");
		expectReject(forgeWrite(1, 1, 0, 0, 4, 0, 0), "zero height");
		expectReject(forgeWrite(1, 1, 0, 0, V2Wire.MAX_TEXTURE_DIM + 1, 1, 0), "width over the dimension cap");
		expectReject(forgeWrite(1, 1, -1, 0, 4, 4, 4 * 4 * 4), "negative x");
		expectReject(forgeWrite(1, 1, 0, -1, 4, 4, 4 * 4 * 4), "negative y");
		expectReject(forgeWrite(1, 0, 0, 0, 4, 4, 4 * 4 * 4), "version 0");
		expectReject(forgeWrite(1, 1, 0, 0, 128, 64, 0), "payload over the per-call cap");
		// A huge claimed rect with no payload behind it: must be refused BEFORE allocating.
		expectReject(forgeWrite(1, 1, 0, 0, 64, 64, 0), "claimed length beyond available data");
	}

	@Test
	public void aggregatePerBatchCapIsEnforcedAcrossDeltasInOneBatch() throws Exception {
		// Each write is legal alone (64x32 = 8192 bytes); the aggregate across one batch is not.
		// The bound is the per-BATCH constant, deliberately larger than the per-tick one: a batch
		// accumulates from one seal to the next while the tick allowance resets at tick change,
		// so a batch legitimately carries up to two ticks' admitted payload. Bounding the decoder
		// by the per-tick number would reject traffic the producer can legally emit, losing the
		// whole batch at every receiver.
		//
		// Derived from the constant rather than hardcoded, so moving the constant moves the test
		// with it instead of silently pinning a stale threshold — which is exactly how the old
		// value survived unexamined.
		final int perWrite = 64 * 32 * 4;
		final int fits = V2Wire.MAX_WRITE_BYTES_PER_BATCH / perWrite;

		Delta[] atCap = new Delta[fits];
		for (int i = 0; i < fits; i++) {
			atCap[i] = new Delta.TextureWrite(1, i + 1, 0, 0, 64, 32, new byte[perWrite]);
		}
		BatchCodec.decode(BatchCodec.encode(batchOf(atCap)));

		Delta[] overCap = new Delta[fits + 1];
		for (int i = 0; i < overCap.length; i++) {
			overCap[i] = new Delta.TextureWrite(1, i + 1, 0, 0, 64, 32, new byte[perWrite]);
		}
		expectReject(BatchCodec.encode(batchOf(overCap)), "aggregate over the per-batch cap");
	}
}
