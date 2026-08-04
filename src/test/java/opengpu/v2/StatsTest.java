package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import opengpu.v2.stats.RenderStats;
import opengpu.v2.stats.SceneStats;

/**
 * The measurement instruments themselves.
 *
 * Worth testing rather than trusting, because their whole job is to produce numbers that will
 * be used to argue for changing transport caps and render strategy. A counter that is quietly
 * wrong does not fail — it produces a plausible figure that justifies the wrong decision, and
 * nothing downstream can tell. The two derived ratios below are the ones most likely to be read
 * carelessly, so both are pinned against hand-computed values.
 */
public class StatsTest {

	private SceneStats stats;

	@Before
	public void setUp() {
		stats = new SceneStats();
		RenderStats.reset();
	}

	@Test
	public void encodedBytesAndSentBytesAreDifferentQuantities() {
		// SceneHost encodes ONE envelope and hands the same array to every watcher, so the
		// server pays the encoded size once while the network carries it per watcher. Conflating
		// them would understate a populated server by exactly the watcher count.
		stats.onBatch(1000, 5, 4);

		assertEquals("encoded once", 1000, stats.batchBytes);
		assertEquals("carried four times", 4000, stats.batchBytesSent);
		assertEquals(1, stats.batches);
		assertEquals(5, stats.deltas);
		assertEquals(4, stats.watchersMax);
	}

	@Test
	public void perTickAndPerBatchAreNotTheSameNumber() {
		// A scene sealing one batch every twentieth tick costs a twentieth as much per tick as
		// its batch size suggests. Reading meanBatchBytes where meanBytesPerTick was meant would
		// argue for changing a cap in the wrong direction, which is precisely the mistake the
		// instrumentation exists to prevent.
		stats.onTick();
		stats.onBatch(2000, 1, 1);
		for (int i = 0; i < 19; i++) {
			stats.onTick();
			stats.onIdleTick();
		}

		assertEquals("one batch of 2000 bytes", 2000.0, stats.meanBatchBytes(), 1e-9);
		assertEquals("spread over twenty ticks", 100.0, stats.meanBytesPerTick(), 1e-9);
	}

	@Test
	public void everyTickCountsTowardTheDivisorEvenWhenNothingIsSent() {
		// The per-tick divisor used to be derived as batches + idleTicks, which looked right and
		// dropped two real cases: a tick that seals a batch with NO watchers (nothing is sent,
		// so onBatch never fires) and a tick that emits a heartbeat. Both vanished from the
		// denominator, inflating bytes-per-tick — and an inflated figure argues for raising the
		// transport caps, which is the one decision this number exists to inform.
		stats.onTick();
		stats.onBatch(1000, 1, 1);   // a normal watched tick
		stats.onTick();              // sealed a batch, but nobody watching: no outcome call
		stats.onTick();
		stats.onHeartbeat(1);        // neither a batch nor idle
		stats.onTick();
		stats.onIdleTick();

		assertEquals("all four ticks are in the divisor", 4, stats.ticks);
		assertEquals("1000 bytes over four ticks", 250.0, stats.meanBytesPerTick(), 1e-9);

		// The old derivation would have seen batches(1) + idleTicks(1) = 2 and reported double.
		assertEquals("the discarded derivation is not what is being computed",
				500.0, (double) stats.batchBytes / (stats.batches + stats.idleTicks), 1e-9);
	}

	@Test
	public void theMaximumBatchIsTrackedSeparatelyFromTheTotal() {
		// The max is what has to fit the decoder's inflate ceiling; a mean that looks healthy
		// says nothing about whether one frame blew the limit and lost the whole batch.
		stats.onBatch(100, 1, 1);
		stats.onBatch(9000, 1, 1);
		stats.onBatch(200, 1, 1);

		assertEquals(9000, stats.batchBytesMax);
		assertEquals(9300, stats.batchBytes);
		assertEquals(3100.0, stats.meanBatchBytes(), 1e-9);
	}

	@Test
	public void emptyStatsDivideToZeroRatherThanNaN() {
		// These get formatted into a chat line the moment someone runs the command, including
		// before anything has rendered. A NaN there reads as a bug in the mod.
		assertEquals(0.0, stats.meanBatchBytes(), 1e-9);
		assertEquals(0.0, stats.meanBytesPerTick(), 1e-9);
		assertEquals(0.0, RenderStats.workFraction(), 1e-9);
		assertEquals(0.0, RenderStats.interpolationFraction(), 1e-9);
		assertEquals(0.0, RenderStats.meanRenderMicros(), 1e-9);
		assertEquals(0.0, RenderStats.nanosPerCommand(), 1e-9);
	}

	@Test
	public void snapshotsAreCountedPerRecipient() {
		// One snapshot encoding served to three watchers is three deliveries of that size — the
		// cost of clients entering range, which is what the resync budget paces.
		stats.onSnapshot(50000, 3);

		assertEquals(3, stats.snapshots);
		assertEquals(150000, stats.snapshotBytes);
		assertEquals("the encoding itself is what must fit the chunker", 50000, stats.snapshotBytesMax);
	}

	@Test
	public void theWorkFractionIsWhatMeasuresInterpolationCost() {
		// framesWithWork / prePasses is the interpolation overhead stated directly. A settled
		// scene must sit near zero; if it does not, active() is failing to go false, which is a
		// regression no unit test of the interpolator itself would catch.
		for (int i = 0; i < 100; i++) {
			RenderStats.onPrePass(i < 25);
		}
		assertEquals(0.25, RenderStats.workFraction(), 1e-9);
	}

	@Test
	public void renderTimingSeparatesInterpolationFromFreshState() {
		RenderStats.onSceneRender(1_000_000L, 500, true);
		RenderStats.onSceneRender(3_000_000L, 1500, false);

		assertEquals(2, RenderStats.sceneRenders);
		assertEquals(1, RenderStats.interpolationRenders);
		assertEquals(0.5, RenderStats.interpolationFraction(), 1e-9);
		assertEquals("mean of 1ms and 3ms", 2000.0, RenderStats.meanRenderMicros(), 1e-6);
		assertEquals("the slow frame is kept", 3_000_000L, RenderStats.renderNanosMax);
		assertEquals("4ms over 2000 commands", 2000.0, RenderStats.nanosPerCommand(), 1e-6);
	}

	@Test
	public void resetClearsEverything() {
		stats.onTick();
		stats.onBatch(10, 1, 1);
		stats.onHeartbeat(1);
		stats.onBodyServed(99);
		stats.reset();
		assertEquals(0, stats.batches);
		assertEquals(0, stats.batchBytes);
		assertEquals(0, stats.heartbeats);
		assertEquals(0, stats.bodyBytes);
		assertEquals(0, stats.watchersMax);

		RenderStats.onPrePass(true);
		RenderStats.onUpload(1234);
		RenderStats.reset();
		assertEquals(0, RenderStats.prePasses);
		assertEquals(0, RenderStats.uploadBytes);
		assertTrue(RenderStats.uploads == 0);
	}
}
