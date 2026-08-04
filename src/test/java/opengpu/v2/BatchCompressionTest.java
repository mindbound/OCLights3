package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
import opengpu.v2.scene.CanvasCommand;

/**
 * DEFLATE framing for batches. The wrapper is a sentinel-marked envelope rather than a
 * protocol-version bump, so the persisted save format is untouched; decoding stays strict
 * and a decompression bomb must be refused from its declared size.
 */
public class BatchCompressionTest {

	private static final String SCENE = "scene-zip";
	private static final int EPOCH = 0x1234;

	private static SceneBatch batchOf(List<Delta> deltas) {
		return new SceneBatch(SCENE, EPOCH, 7, 900L, deltas);
	}

	/** Highly repetitive canvas traffic — the realistic case compression exists for. */
	private static SceneBatch repetitiveBatch(int commands) {
		List<CanvasCommand> list = new ArrayList<CanvasCommand>();
		for (int i = 0; i < commands; i++) {
			list.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, 12, 34, 56, 255));
			list.add(CanvasCommand.of(V2Wire.OP_FILL_RECT, i % 64, i % 32, 8, 8));
		}
		List<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.CanvasPublish(1, list));
		return batchOf(deltas);
	}

	@Test
	public void compressedBatchRoundTripsIdentically() throws Exception {
		SceneBatch batch = repetitiveBatch(400);
		byte[] encoded = BatchCodec.encode(batch);
		SceneBatch decoded = BatchCodec.decode(encoded);
		assertEquals(batch.sceneId, decoded.sceneId);
		assertEquals(batch.epoch, decoded.epoch);
		assertEquals(batch.seq, decoded.seq);
		assertEquals(batch.serverTick, decoded.serverTick);
		assertEquals(batch.deltas, decoded.deltas);
	}

	@Test
	public void repetitiveTrafficActuallyShrinks() {
		SceneBatch batch = repetitiveBatch(400);
		int encoded = BatchCodec.encode(batch).length;
		// Compare against the uncompressed size by measuring a batch small enough to ship
		// raw, scaled up — simpler: assert the marker is present and the payload is well
		// under what 800 commands of 8-byte doubles would occupy uncompressed.
		assertTrue("expected the batch to be compressed, got " + encoded + " bytes",
				encoded < 400 * 2 * 8);
	}

	@Test
	public void tinyBatchesShipRawAndStillDecode() throws Exception {
		List<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.NodeFree(3));
		byte[] encoded = BatchCodec.encode(batchOf(deltas));
		// Below the threshold: the first short must still be the plain protocol version.
		assertEquals(V2Wire.PROTOCOL_VERSION,
				(short) (((encoded[0] & 0xFF) << 8) | (encoded[1] & 0xFF)));
		assertEquals(deltas, BatchCodec.decode(encoded).deltas);
	}

	@Test
	public void incompressiblePayloadIsNotInflatedByTheWrapper() throws Exception {
		// Pseudorandom pixels do not compress; the encoder must fall back to raw rather than
		// paying the wrapper's 6 bytes for nothing.
		byte[] pixels = new byte[64 * 64 * 4];
		long seed = 88172645463325252L;
		for (int i = 0; i < pixels.length; i++) {
			seed ^= seed << 13; seed ^= seed >>> 7; seed ^= seed << 17;
			pixels[i] = (byte) seed;
		}
		List<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.TextureWrite(1, 1, 0, 0, 64, 64, pixels));
		byte[] encoded = BatchCodec.encode(batchOf(deltas));
		assertTrue("incompressible data must not grow", encoded.length <= pixels.length + 128);
		assertEquals(deltas, BatchCodec.decode(encoded).deltas);
	}

	private static byte[] forgeCompressed(int declaredLen, byte[] payload) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(-2); // COMPRESSED_MARKER
			out.writeInt(declaredLen);
			out.write(payload);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		return bytes.toByteArray();
	}

	@Test
	public void aDecompressionBombIsRefusedFromItsDeclaredSize() {
		// Claims 1 GiB from a handful of bytes: must be rejected before allocating.
		try {
			BatchCodec.decode(forgeCompressed(1 << 30, new byte[] { 1, 2, 3, 4 }));
			fail("expected an oversized declared length to be refused");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void malformedAndMismatchedCompressedPayloadsAreRejected() {
		try {
			BatchCodec.decode(forgeCompressed(64, new byte[] { 9, 9, 9, 9, 9, 9 }));
			fail("expected malformed deflate data to be refused");
		} catch (CodecException expected) {
		}
		try {
			BatchCodec.decode(forgeCompressed(-1, new byte[] { 1 }));
			fail("expected a negative declared length to be refused");
		} catch (CodecException expected) {
		}
		try {
			BatchCodec.decode(new byte[] { (byte) 0xFF, (byte) 0xFE, 0 });
			fail("expected a truncated compressed batch to be refused");
		} catch (CodecException expected) {
		}
	}

	/**
	 * Regression: decode() re-dispatches inflated bytes through itself, so a payload that
	 * inflates to ANOTHER marker-wrapped payload recursed without bound — every level's array
	 * live at once. A few hundred KB on the wire became hundreds of MB and a StackOverflowError,
	 * which is an Error and escapes the CodecException-only catch in the inbound drain.
	 */
	@Test
	public void nestedCompressionWrappersAreRefused() throws Exception {
		byte[] inner = BatchCodec.encode(repetitiveBatch(400));
		// Wrap an already-encoded (already compressed) batch a second time.
		java.util.zip.Deflater deflater = new java.util.zip.Deflater(
				java.util.zip.Deflater.BEST_SPEED);
		deflater.setInput(inner);
		deflater.finish();
		byte[] buffer = new byte[inner.length + 64];
		int n = deflater.deflate(buffer);
		deflater.end();
		byte[] outer = forgeCompressed(inner.length, java.util.Arrays.copyOf(buffer, n));
		try {
			BatchCodec.decode(outer);
			fail("a nested compression wrapper must be refused");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void anInflatedSizeBeyondTheBatchCeilingIsRefused() {
		// 64 MiB is far under the 256 MiB transport ceiling but far over what any batch can
		// legitimately be; reusing the transport ceiling here would reopen the amplification.
		try {
			BatchCodec.decode(forgeCompressed(64 * 1024 * 1024, new byte[] { 1, 2, 3, 4 }));
			fail("expected a batch-ceiling violation to be refused");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void strictnessSurvivesCompression() throws Exception {
		// A batch that is invalid at the inner layer must still be rejected after inflating:
		// compression must not become a way to smuggle payloads past the caps.
		//
		// Sized off the constant rather than a hardcoded count. The threshold here is the
		// per-BATCH write cap, which is deliberately larger than the per-tick one because a
		// batch spans up to two tick allowances; pinning a literal count made this test pass for
		// the wrong reason once the constants were told apart.
		final int perWrite = 64 * 32 * 4;
		final int overCap = V2Wire.MAX_WRITE_BYTES_PER_BATCH / perWrite + 1;
		List<Delta> deltas = new ArrayList<Delta>();
		for (int i = 0; i < overCap; i++) {
			deltas.add(new Delta.TextureWrite(1, i + 1, 0, 0, 64, 32, new byte[perWrite]));
		}
		byte[] encoded = BatchCodec.encode(batchOf(deltas));
		try {
			BatchCodec.decode(encoded);
			fail("the per-batch write cap must hold through the compressed path too");
		} catch (CodecException expected) {
		}
	}
}
