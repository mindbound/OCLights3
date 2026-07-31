package ds.mods.OCLights2.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import ds.mods.OCLights2.v2.protocol.CodecException;
import ds.mods.OCLights2.v2.protocol.FrameChunker;

public class FrameChunkerTest {

	private static byte[] pattern(int len) {
		byte[] data = new byte[len];
		for (int i = 0; i < len; i++) {
			data[i] = (byte) (i * 31 + 7);
		}
		return data;
	}

	@Test
	public void splitAndReassembleRoundTrips() throws Exception {
		byte[] data = pattern(100000);
		List<byte[]> frames = FrameChunker.split(1, data, 30000);
		assertEquals(4, frames.size());
		FrameChunker.Reassembler reassembler = new FrameChunker.Reassembler();
		for (int i = 0; i < frames.size() - 1; i++) {
			assertNull(reassembler.accept("player1", frames.get(i)));
		}
		assertArrayEquals(data, reassembler.accept("player1", frames.get(frames.size() - 1)));
	}

	@Test
	public void emptyPayloadStillProducesOneFrame() throws Exception {
		List<byte[]> frames = FrameChunker.split(9, new byte[0], 30000);
		assertEquals(1, frames.size());
		FrameChunker.Reassembler reassembler = new FrameChunker.Reassembler();
		assertArrayEquals(new byte[0], reassembler.accept("p", frames.get(0)));
	}

	@Test
	public void sendersAndTransfersDoNotInterleave() throws Exception {
		byte[] dataA = pattern(70000);
		byte[] dataB = pattern(50000);
		List<byte[]> framesA = FrameChunker.split(1, dataA, 30000);
		// Same transfer id from a DIFFERENT sender — the legacy P-03 bug collided these.
		List<byte[]> framesB = FrameChunker.split(1, dataB, 30000);
		FrameChunker.Reassembler reassembler = new FrameChunker.Reassembler();
		for (byte[] frame : framesA.subList(0, framesA.size() - 1)) {
			assertNull(reassembler.accept("alice", frame));
		}
		for (byte[] frame : framesB.subList(0, framesB.size() - 1)) {
			assertNull(reassembler.accept("bob", frame));
		}
		assertArrayEquals(dataB, reassembler.accept("bob", framesB.get(framesB.size() - 1)));
		assertArrayEquals(dataA, reassembler.accept("alice", framesA.get(framesA.size() - 1)));
	}

	@Test
	public void duplicateChunkDropsTheTransfer() throws Exception {
		List<byte[]> frames = FrameChunker.split(1, pattern(70000), 30000);
		FrameChunker.Reassembler reassembler = new FrameChunker.Reassembler();
		assertNull(reassembler.accept("p", frames.get(0)));
		try {
			reassembler.accept("p", frames.get(0));
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("Duplicate"));
		}
		// The transfer was dropped; a fresh complete send succeeds.
		for (int i = 0; i < frames.size() - 1; i++) {
			assertNull(reassembler.accept("p", frames.get(i)));
		}
		assertArrayEquals(pattern(70000), reassembler.accept("p", frames.get(frames.size() - 1)));
	}

	@Test
	public void evictionClearsPartialTransfers() throws Exception {
		List<byte[]> frames = FrameChunker.split(1, pattern(70000), 30000);
		FrameChunker.Reassembler reassembler = new FrameChunker.Reassembler();
		assertNull(reassembler.accept("p", frames.get(0)));
		reassembler.evict("p");
		// After eviction the same first chunk is accepted as a new transfer, not a duplicate.
		assertNull(reassembler.accept("p", frames.get(0)));
	}

	@Test
	public void malformedFramesAreRejected() {
		FrameChunker.Reassembler reassembler = new FrameChunker.Reassembler();
		try {
			reassembler.accept("p", new byte[] { 1, 2, 3 });
			fail("expected CodecException");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void capsAreMutuallyConsistent() {
		// "Legal to create" must imply "deliverable": the chunker must be able to carry the
		// largest wire-legal payload at the default chunk size.
		assertTrue((long) FrameChunker.MAX_CHUNK_COUNT * FrameChunker.DEFAULT_CHUNK_SIZE
				>= FrameChunker.MAX_TRANSFER_BYTES);
	}

	@Test
	public void slotCapEvictsOldestInsteadOfWedging() throws Exception {
		FrameChunker.Reassembler reassembler = new FrameChunker.Reassembler();
		// Park MAX incomplete transfers, then start one more: the oldest is evicted, the
		// sender keeps working (the old behavior threw forever once wedged).
		for (int id = 0; id < FrameChunker.MAX_TRANSFERS_PER_SENDER; id++) {
			assertNull(reassembler.accept("p", FrameChunker.split(id, pattern(70000), 30000).get(0)));
		}
		byte[] fresh = pattern(50000);
		List<byte[]> freshFrames = FrameChunker.split(99, fresh, 30000);
		assertNull(reassembler.accept("p", freshFrames.get(0)));
		assertArrayEquals(fresh, reassembler.accept("p", freshFrames.get(1)));
		// Transfer 0 was evicted: its remaining chunk starts a fresh partial (null), not a
		// duplicate error.
		assertNull(reassembler.accept("p", FrameChunker.split(0, pattern(70000), 30000).get(1)));
	}

	@Test
	public void aggregateSenderBudgetIsEnforced() throws Exception {
		FrameChunker.Reassembler reassembler = new FrameChunker.Reassembler(8, 100000);
		// Two incomplete transfers within budget individually, over it together.
		assertNull(reassembler.accept("p", FrameChunker.split(1, pattern(90000), 30000).get(0)));
		assertNull(reassembler.accept("p", FrameChunker.split(1, pattern(90000), 30000).get(1)));
		assertNull(reassembler.accept("p", FrameChunker.split(2, pattern(90000), 30000).get(0)));
		try {
			reassembler.accept("p", FrameChunker.split(2, pattern(90000), 30000).get(1));
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("aggregate"));
		}
	}
}
