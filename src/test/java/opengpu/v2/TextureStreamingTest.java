package opengpu.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Random;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.ServerScene;

/**
 * Mutable texture content (protocol v3): streaming convergence, the per-tick allowance, and
 * the version invariants that replace the old immutable-bytes hash identity.
 */
public class TextureStreamingTest {

	private static final String SCENE = "scene-stream";
	private static final int TEX_W = 64;
	private static final int TEX_H = 64;

	/** Server + mirror already agreeing on one texture at version 1, bytes delivered. */
	private static final class Rig {
		final ServerScene server = new ServerScene(SCENE);
		final SceneMirror mirror = new SceneMirror(SCENE);
		final int texture;

		Rig() {
			server.setCurrentTick(1);
			texture = server.createTexture(TEX_W, TEX_H, new byte[TEX_W * TEX_H * 4]);
			assertTrue(pump());
			ResourceInfo res = server.state().resources.get(texture);
			assertTrue(mirror.state().resources.get(texture).isPending());
			assertTrue(mirror.deliverResourceBody(server.epoch(), texture, res.version,
					V2Wire.contentHash(res.bytes), res.bytes));
		}

		/**
		 * Seal, round-trip through real encoded bytes, apply. Returns whether the batch
		 * applied cleanly — tests that deliberately drop a batch expect false here.
		 */
		boolean pump() {
			SceneBatch batch = server.sealBatch();
			if (batch == null) {
				return true;
			}
			try {
				return mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(batch)));
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}

		void assertConverged() {
			assertTrue(server.state().contentEquals(mirror.state()));
			assertArrayEquals(server.state().resources.get(texture).bytes,
					mirror.state().resources.get(texture).bytes);
			assertEquals(server.state().resources.get(texture).version,
					mirror.state().resources.get(texture).version);
		}
	}

	private static byte[] pattern(int len, int seed) {
		byte[] data = new byte[len];
		for (int i = 0; i < len; i++) {
			data[i] = (byte) (i * 31 + seed);
		}
		return data;
	}

	@Test
	public void streamingConvergesByteExactEveryTick() {
		Rig rig = new Rig();
		Random random = new Random(20260802L);
		for (int tick = 2; tick < 202; tick++) {
			rig.server.beginTick(tick, V2Wire.MAX_WRITE_BYTES_PER_TICK);
			int writes = 1 + random.nextInt(3);
			for (int i = 0; i < writes; i++) {
				int w = 1 + random.nextInt(16);
				int h = 1 + random.nextInt(16);
				int x = random.nextInt(TEX_W - w + 1);
				int y = random.nextInt(TEX_H - h + 1);
				rig.server.writeRegion(rig.texture, x, y, w, h,
						pattern(w * h * 4, tick * 7 + i));
			}
			assertTrue(rig.pump());
			// Byte-exact after EVERY tick: a torn or misplaced blit anywhere in 200 ticks
			// of pseudorandom rectangles fails here, not silently at the end.
			rig.assertConverged();
		}
		assertTrue(rig.server.state().resources.get(rig.texture).version > 200);
	}

	@Test
	public void writesPastThePerTickAllowanceAreRefused() {
		Rig rig = new Rig();
		rig.server.beginTick(2, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		// 64x64 RGBA is exactly the per-call and per-tick cap.
		rig.server.writeRegion(rig.texture, 0, 0, 64, 64, new byte[64 * 64 * 4]);
		assertEquals(0, rig.server.writeBudgetRemaining());
		try {
			rig.server.writeRegion(rig.texture, 0, 0, 1, 1, new byte[4]);
			fail("expected the per-tick allowance to be exhausted");
		} catch (IllegalStateException expected) {
		}
		// A new tick grants a fresh allowance. Capacity is the tighter of the two bounds, but the
		// batch bound is deliberately the looser one — an unsealed batch holds two tick
		// allowances, not one, so the fresh grant is usable with or without this seal. See
		// aBatchSpanningTickBoundariesStaysWithinTheDecoderCap for the unsealed path.
		rig.server.sealBatch();
		rig.server.beginTick(3, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		assertEquals(V2Wire.MAX_WRITE_BYTES_PER_TICK, rig.server.writeBudgetRemaining());
		rig.server.writeRegion(rig.texture, 0, 0, 1, 1, new byte[4]);
	}

	@Test
	public void aSaveBoundarySealDoesNotGrantASecondAllowance() {
		Rig rig = new Rig();
		rig.server.beginTick(2, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		rig.server.writeRegion(rig.texture, 0, 0, 64, 64, new byte[64 * 64 * 4]);
		rig.server.sealBatch(); // mid-tick save boundary
		// Re-entering the SAME tick must not reset the allowance, or the per-tick cap
		// silently becomes per-seal.
		rig.server.beginTick(2, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		assertEquals(0, rig.server.writeBudgetRemaining());
	}

	@Test
	public void rejectedWritesLeaveTheTextureUntouched() {
		Rig rig = new Rig();
		ResourceInfo res = rig.server.state().resources.get(rig.texture);
		byte[] before = res.bytes.clone();
		int versionBefore = res.version;

		rig.server.beginTick(2, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		expectFailure(rig, 60, 60, 8, 8, new byte[8 * 8 * 4]);   // out of bounds
		expectFailure(rig, 0, 0, 4, 4, new byte[4 * 4 * 4 - 1]); // wrong length
		expectFailure(rig, 0, 0, 0, 4, new byte[0]);             // degenerate rect

		assertArrayEquals(before, res.bytes);
		assertEquals(versionBefore, res.version);
		assertNull("a refused write must stage nothing", rig.server.sealBatch());
	}

	private static void expectFailure(Rig rig, int x, int y, int w, int h, byte[] data) {
		try {
			rig.server.writeRegion(rig.texture, x, y, w, h, data);
			fail("expected the write to be refused");
		} catch (IllegalArgumentException expected) {
		} catch (IllegalStateException expected) {
		}
	}

	/**
	 * OC's Arguments.checkInteger SATURATES an out-of-range Lua number to Integer.MAX_VALUE
	 * instead of rejecting it, so a plain Lua call can deliver x = 2^31-1. In int arithmetic
	 * x + w wraps negative and slips past a naive bounds check; the resulting blit either
	 * throws (freezing the texture forever, because latestVersion had already advanced) or
	 * silently writes at an address the caller never named — which every mirror reproduces
	 * identically, so no divergence detector fires.
	 */
	@Test
	public void saturatedCoordinatesCannotWrapPastTheBoundsCheck() {
		Rig rig = new Rig();
		rig.server.beginTick(2, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		byte[] before = rig.server.state().resources.get(rig.texture).bytes.clone();

		expectFailure(rig, Integer.MAX_VALUE, 0, 1, 1, new byte[4]);
		expectFailure(rig, 0, Integer.MAX_VALUE, 1, 1, new byte[4]);
		// The exact wrap that lands back in range: x + w == 2^31, ((y*width)+x)*4 == 0.
		expectFailure(rig, Integer.MAX_VALUE - 4095, 64, 4096, 1, new byte[4096 * 4]);

		assertArrayEquals(before, rig.server.state().resources.get(rig.texture).bytes);
		assertNull("a wrapped rect must stage nothing", rig.server.sealBatch());
	}

	@Test
	public void aWrappedRectFromTheWireIsRefusedAndLeavesVersionsConsistent() throws Exception {
		Rig rig = new Rig();
		ResourceInfo mirrored = rig.mirror.state().resources.get(rig.texture);
		byte[] before = mirrored.bytes.clone();
		int versionBefore = mirrored.version;
		int latestBefore = mirrored.latestVersion;

		SceneBatch hostile = new SceneBatch(SCENE, rig.server.epoch(), rig.mirror.lastSeq() + 1, 5,
				java.util.Collections.<Delta>singletonList(new Delta.TextureWrite(
						rig.texture, latestBefore + 1, Integer.MAX_VALUE, 0, 1, 1, new byte[4])));
		assertFalse(rig.mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(hostile))));
		assertArrayEquals(before, mirrored.bytes);
		assertEquals(versionBefore, mirrored.version);
		// version and latestVersion must stay in step: advancing latestVersion past a
		// refused write is what would freeze the resource permanently.
		assertEquals(latestBefore, mirrored.latestVersion);
	}

	/**
	 * Regression: observed in game as "Batch texture-write payload over the per-tick cap:
	 * 17408" on the client.
	 *
	 * The per-tick admission allowance and the per-batch payload are different bounds. They
	 * coincide in the common path, but a tick boundary that passes without a seal lets a
	 * single batch accumulate more than one tick's worth — and the decoder rejects it, so
	 * the whole frame is lost and the mirror resyncs.
	 */
	@Test
	public void aBatchSpanningTickBoundariesStaysWithinTheDecoderCap() throws Exception {
		Rig rig = new Rig();
		// A batch spans up to TWO tick allowances, so both must be admissible into one unsealed
		// batch. This used to fail on the second tick, because the batch was bounded by the
		// per-TICK constant — a bound tighter than the tick check it sits behind, which is a
		// refusal no caller can clear by waiting. The same shape on the canvas-submit path is
		// what made a frame over 64 KiB undeliverable; see PERF-BASELINE.md.
		rig.server.beginTick(2, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		for (int i = 0; i < 16; i++) {
			rig.server.writeRegion(rig.texture, 0, 0, 16, 16, new byte[16 * 16 * 4]);
		}
		assertEquals("tick 2 is spent", 0, rig.server.writeBudgetRemaining());

		// The tick advances with NO seal — the production ordering, since a direct callback runs
		// off the server thread and can land between the END-phase seal and the START-phase grant.
		rig.server.beginTick(3, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		assertEquals("a fresh tick must hand back a usable allowance even unsealed",
				V2Wire.MAX_WRITE_BYTES_PER_TICK, rig.server.writeBudgetRemaining());
		for (int i = 0; i < 16; i++) {
			rig.server.writeRegion(rig.texture, 0, 0, 16, 16, new byte[16 * 16 * 4]);
		}

		// But the batch bound is real and still holds at its own, larger, limit.
		rig.server.beginTick(4, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		assertEquals("a third tick is bounded by the batch, not by the tick",
				0, rig.server.writeBudgetRemaining());
		try {
			rig.server.writeRegion(rig.texture, 0, 0, 16, 16, new byte[16 * 16 * 4]);
			fail("the batch bound must hold once two tick allowances are staged");
		} catch (IllegalStateException expected) {
		}

		// Whatever was admitted must still decode: the producer can never build a batch the
		// receiver refuses. This is the check that keeps the producer and decoder constants
		// moving together — MAX_WRITE_BYTES_PER_BATCH is enforced at both ends.
		SceneBatch batch = rig.server.sealBatch();
		BatchCodec.decode(BatchCodec.encode(batch));
		// After the seal the batch bound is clear again, and the tick allowance still governs.
		rig.server.writeRegion(rig.texture, 0, 0, 16, 16, new byte[16 * 16 * 4]);
	}

	@Test
	public void writingANonTextureIsRefused() {
		Rig rig = new Rig();
		int canvas = rig.server.createCanvas(32, 32, 64);
		try {
			rig.server.writeRegion(canvas, 0, 0, 1, 1, new byte[4]);
			fail("expected a canvas target to be refused");
		} catch (IllegalStateException expected) {
		}
	}

	@Test
	public void aVersionGapIsAnIndependentDivergenceDetector() throws Exception {
		Rig rig = new Rig();
		rig.server.beginTick(2, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		rig.server.writeRegion(rig.texture, 0, 0, 4, 4, pattern(4 * 4 * 4, 1));
		SceneBatch dropped = rig.server.sealBatch();
		assertTrue(dropped != null);
		// The mirror never sees that batch, but its sequence number is not skipped: forge a
		// follow-up batch that is seq-continuous yet references a version two ahead.
		rig.server.beginTick(3, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		rig.server.writeRegion(rig.texture, 0, 0, 4, 4, pattern(4 * 4 * 4, 2));
		SceneBatch next = rig.server.sealBatch();
		SceneBatch forged = new SceneBatch(SCENE, next.epoch, rig.mirror.lastSeq() + 1,
				next.serverTick, next.deltas);
		assertFalse("a version gap must be caught even when the seq looks continuous",
				rig.mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(forged))));
		assertTrue(rig.mirror.needsResync());
	}

	@Test
	public void anOutOfBoundsWriteFromTheWireLeavesTheMirrorIntactAndResyncing() throws Exception {
		Rig rig = new Rig();
		ResourceInfo mirrored = rig.mirror.state().resources.get(rig.texture);
		byte[] before = mirrored.bytes.clone();
		int versionBefore = mirrored.version;

		// Legal on the wire (the decoder holds no scene state), illegal against this texture.
		SceneBatch hostile = new SceneBatch(SCENE, rig.server.epoch(), rig.mirror.lastSeq() + 1, 5,
				java.util.Collections.<Delta>singletonList(new Delta.TextureWrite(
						rig.texture, mirrored.latestVersion + 1, TEX_W - 2, TEX_H - 2, 8, 8,
						new byte[8 * 8 * 4])));
		assertFalse(rig.mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(hostile))));
		assertTrue(rig.mirror.needsResync());
		assertArrayEquals("the blit must be all-or-nothing", before, mirrored.bytes);
		assertEquals(versionBefore, mirrored.version);
	}

	@Test
	public void aBodyIsRefusedWhileTheMirrorNeedsResync() {
		Rig rig = new Rig();
		rig.server.beginTick(2, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		rig.server.writeRegion(rig.texture, 0, 0, 4, 4, pattern(4 * 4 * 4, 3));
		rig.server.sealBatch(); // dropped: the mirror now has a gap
		rig.server.beginTick(3, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		rig.server.writeRegion(rig.texture, 0, 0, 4, 4, pattern(4 * 4 * 4, 4));
		rig.pump();
		assertTrue(rig.mirror.needsResync());

		ResourceInfo res = rig.server.state().resources.get(rig.texture);
		assertFalse("latestVersion is only a trustworthy acceptance key on a healthy mirror",
				rig.mirror.deliverResourceBody(rig.server.epoch(), rig.texture, res.version,
						V2Wire.contentHash(res.bytes), res.bytes));
	}

	@Test
	public void aBodyForTheWrongVersionIsRefused() {
		Rig rig = new Rig();
		ResourceInfo res = rig.server.state().resources.get(rig.texture);
		// One version behind what the mirror has heard about: installing it would roll the
		// texture backwards.
		assertFalse(rig.mirror.deliverResourceBody(rig.server.epoch(), rig.texture,
				res.version + 1, V2Wire.contentHash(res.bytes), res.bytes));
		assertFalse(rig.mirror.deliverResourceBody(rig.server.epoch(), rig.texture,
				res.version - 1, V2Wire.contentHash(res.bytes), res.bytes));
	}

	@Test
	public void snapshotCarryOverKeepsBytesAndSchedulesOneRefetch() {
		Rig rig = new Rig();
		rig.server.beginTick(2, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		rig.server.writeRegion(rig.texture, 0, 0, 8, 8, pattern(8 * 8 * 4, 5));
		rig.server.sealBatch(); // dropped
		rig.server.beginTick(3, V2Wire.MAX_WRITE_BYTES_PER_TICK);
		rig.server.writeRegion(rig.texture, 8, 8, 8, 8, pattern(8 * 8 * 4, 6));
		rig.pump();
		assertTrue(rig.mirror.needsResync());

		rig.server.sealBatch();
		rig.mirror.applySnapshot(rig.server.snapshot());
		assertFalse(rig.mirror.needsResync());

		ResourceInfo mirrored = rig.mirror.state().resources.get(rig.texture);
		// Carried over: it still renders the last content it had rather than a placeholder...
		assertTrue("carry-over must keep the stale bytes", mirrored.bytes != null);
		// ...but it knows it is behind, so exactly one refetch is scheduled.
		assertTrue(mirrored.needsBody());
		assertTrue(mirrored.version < mirrored.latestVersion);

		ResourceInfo res = rig.server.state().resources.get(rig.texture);
		assertTrue(rig.mirror.deliverResourceBody(rig.server.epoch(), rig.texture, res.version,
				V2Wire.contentHash(res.bytes), res.bytes));
		rig.assertConverged();
	}
}
