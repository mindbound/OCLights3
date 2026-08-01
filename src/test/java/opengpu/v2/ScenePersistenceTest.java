package opengpu.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import opengpu.v2.persist.DirectoryResourceStore;
import opengpu.v2.persist.ScenePersistence;
import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.ServerScene;

public class ScenePersistenceTest {

	private static final String SCENE = "scene-persist";

	private File root;
	private DirectoryResourceStore store;

	@Before
	public void setUp() throws Exception {
		root = File.createTempFile("ocl3-persist-test", "");
		assertTrue(root.delete());
		assertTrue(root.mkdirs());
		store = new DirectoryResourceStore(root);
	}

	@After
	public void tearDown() {
		store.close();
		File[] children = root.listFiles();
		if (children != null) {
			for (File scene : children) {
				File[] files = scene.listFiles();
				if (files != null) {
					for (File f : files) {
						f.delete();
					}
				}
				scene.delete();
			}
		}
		root.delete();
	}

	private static ServerScene buildScene() {
		ServerScene scene = new ServerScene(SCENE);
		int canvas = scene.createCanvas(64, 48, 512);
		byte[] pixels = new byte[8 * 8 * 4];
		for (int i = 0; i < pixels.length; i++) {
			pixels[i] = (byte) (i * 7);
		}
		int texture = scene.createTexture(8, 8, pixels);
		int node = scene.createNode(V2Wire.NODE_CANVAS, canvas);
		int sprite = scene.createNode(V2Wire.NODE_SPRITE, texture);
		List<CanvasCommand> cmds = new ArrayList<CanvasCommand>();
		cmds.add(CanvasCommand.of(V2Wire.OP_PUSH));
		cmds.add(CanvasCommand.of(V2Wire.OP_TRANSLATE, 3, 3));
		cmds.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, 200, 100, 50, 255));
		cmds.add(CanvasCommand.text(1, 1, "persisted"));
		cmds.add(CanvasCommand.of(V2Wire.OP_DRAW_TEXTURE, texture, 0, 0));
		scene.canvasAppend(canvas, cmds);
		scene.setTransform(sprite, 10, 20, 0.5, 2, 2);
		scene.setTint(node, 0xCC102030);
		scene.setCurrentTick(1234);
		scene.sealBatch();
		return scene;
	}

	@Test
	public void fullRoundTripRestoresEverything() throws Exception {
		ServerScene original = buildScene();
		byte[] structure = ScenePersistence.encodeStructure(original);
		ScenePersistence.writeBodies(original, store);

		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertTrue(result.warnings.toString(), result.warnings.isEmpty());
		ServerScene restored = result.scene;

		assertEquals(original.sceneId, restored.sceneId);
		assertEquals(original.epoch(), restored.epoch()); // same incarnation continues
		assertEquals(original.currentSeq(), restored.currentSeq());
		assertTrue(original.state().contentEquals(restored.state()));
		assertEquals(original.state().nextResourceId, restored.state().nextResourceId);
		assertEquals(original.state().nextNodeId, restored.state().nextNodeId);
		// Texture bytes reattached and hash-valid.
		assertArrayEquals(original.state().resources.get(2).bytes,
				restored.state().resources.get(2).bytes);
		assertFalse(restored.state().resources.get(2).degraded);
	}

	@Test
	public void restoredSceneKeepsSyncDisciplineWithLiveMirrors() throws Exception {
		ServerScene original = buildScene();
		// A mirror fully caught up with the original incarnation.
		SceneMirror mirror = new SceneMirror(SCENE);
		mirror.applySnapshot(original.snapshot());
		assertEquals(original.currentSeq(), mirror.lastSeq());

		// Chunk unload/reload: the scene restores; the SAME epoch continues, so the mirror
		// needs no hard reset and the next batch applies in order.
		byte[] structure = ScenePersistence.encodeStructure(original);
		ScenePersistence.writeBodies(original, store);
		ServerScene restored = ScenePersistence.restore(structure, store).scene;

		restored.setVisible(2, false); // node 2 = the sprite
		mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(restored.sealBatch())));
		assertTrue(restored.state().contentEquals(mirror.state()));
		assertFalse(mirror.needsResync());
	}

	@Test
	public void compactionStateSurvivesTheRoundTrip() throws Exception {
		ServerScene original = buildScene(); // canvas has PUSH recorded -> pushDepth latched
		byte[] structure = ScenePersistence.encodeStructure(original);
		ScenePersistence.writeBodies(original, store);
		ServerScene restored = ScenePersistence.restore(structure, store).scene;

		// ORIGIN under a non-empty push stack must not re-arm compaction — identically on
		// the original and the restored scene.
		List<CanvasCommand> next = new ArrayList<CanvasCommand>();
		next.add(CanvasCommand.of(V2Wire.OP_ORIGIN));
		next.add(CanvasCommand.of(V2Wire.OP_FILL));
		original.canvasAppend(1, next);
		List<CanvasCommand> next2 = new ArrayList<CanvasCommand>();
		next2.add(CanvasCommand.of(V2Wire.OP_ORIGIN));
		next2.add(CanvasCommand.of(V2Wire.OP_FILL));
		restored.canvasAppend(1, next2);
		assertTrue(original.state().contentEquals(restored.state()));
	}

	@Test
	public void missingBodyDegradesToBlankWithWarningAndFreshEpoch() throws Exception {
		ServerScene original = buildScene();
		byte[] structure = ScenePersistence.encodeStructure(original);
		// Bodies never written (crash before save): restore must not throw.
		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals(1, result.warnings.size());
		opengpu.v2.scene.ResourceInfo texture = result.scene.state().resources.get(2);
		assertTrue(texture.degraded);
		assertEquals(8 * 8 * 4, texture.bytes.length);
		// Hash is consistent with the blank bytes, so mirrors/body-transfer stay coherent.
		assertEquals(V2Wire.contentHash(texture.bytes), texture.hash);
		// A degraded restore is a DIVERGENT restore: fresh epoch, so surviving mirrors
		// hard-reset instead of silently keeping the old texture forever.
		assertTrue(result.scene.epoch() != original.epoch());
	}

	@Test
	public void corruptBodyDegradesToBlankWithWarning() throws Exception {
		ServerScene original = buildScene();
		byte[] structure = ScenePersistence.encodeStructure(original);
		ScenePersistence.writeBodies(original, store);
		// Corrupt the stored body (backup tear).
		store.save(SCENE, 2, new byte[8 * 8 * 4]); // right size, wrong content/hash...
		byte[] wrong = new byte[8 * 8 * 4];
		wrong[0] = 42;
		store.save(SCENE, 2, wrong);
		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals(1, result.warnings.size());
		assertTrue(result.scene.state().resources.get(2).degraded);
	}

	@Test
	public void cleanRestoreAndOrphanPruningKeepTheEpoch() throws Exception {
		ServerScene original = buildScene();
		byte[] structure = ScenePersistence.encodeStructure(original);
		ScenePersistence.writeBodies(original, store);
		store.save(SCENE, 999, new byte[16]); // orphan only — manifest untouched
		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals(original.epoch(), result.scene.epoch());
	}

	@Test
	public void corruptStructureThrowsCodecExceptionOnly() throws Exception {
		ServerScene original = buildScene();
		byte[] structure = ScenePersistence.encodeStructure(original);
		// Zeroed epoch field (bit rot): must be CodecException, never IllegalArgumentException.
		byte[] zeroedEpoch = structure.clone();
		int epochOffset = 2 + 2 + SCENE.length(); // short version + UTF header + id bytes
		for (int i = 0; i < 4; i++) {
			zeroedEpoch[epochOffset + i] = 0;
		}
		try {
			ScenePersistence.restore(zeroedEpoch, store);
			throw new AssertionError("expected CodecException");
		} catch (opengpu.v2.protocol.CodecException expected) {
		}
		// Truncation: same contract.
		try {
			ScenePersistence.restore(java.util.Arrays.copyOf(structure, structure.length / 2), store);
			throw new AssertionError("expected CodecException");
		} catch (opengpu.v2.protocol.CodecException expected) {
		}
	}

	@Test
	public void restoreOrFreshFallsBackAndCleansTheStore() throws Exception {
		ServerScene original = buildScene();
		ScenePersistence.writeBodies(original, store);
		store.flush();
		// Unreadable structure: fresh scene, new epoch, store cleared (no body leak).
		ScenePersistence.RestoreResult result =
				ScenePersistence.restoreOrFresh(SCENE, new byte[] { 1, 2, 3 }, store);
		assertEquals(1, result.warnings.size());
		assertTrue(result.scene.epoch() != original.epoch());
		assertTrue(result.scene.state().resources.isEmpty());
		assertTrue(store.listResources(SCENE).isEmpty());
	}

	@Test
	public void structureSpillRoundTripsThroughTheStore() throws Exception {
		ServerScene original = buildScene();
		byte[] structure = ScenePersistence.encodeStructure(original);
		ScenePersistence.writeBodies(original, store);
		// Small scenes inline...
		assertTrue(ScenePersistence.persistStructure(original, store) != null);
		// ...and the spill slot round-trips and is invisible to body listing.
		store.saveStructure(SCENE, structure);
		byte[] resolved = ScenePersistence.resolveStructure(SCENE, null, store);
		assertArrayEquals(structure, resolved);
		assertFalse(store.listResources(SCENE).contains(-1)); // no fake body id from the blob
		ScenePersistence.RestoreResult result = ScenePersistence.restore(resolved, store);
		assertTrue(result.warnings.toString(), result.warnings.isEmpty());
		assertTrue(result.scene.state().contentEquals(original.state()));
	}

	@Test
	public void writeBodiesSkipsAlreadyStoredBodies() throws Exception {
		ServerScene original = buildScene();
		ScenePersistence.writeBodies(original, store);
		store.flush();
		File bodyFile = findBodyFile();
		long firstWrite = bodyFile.lastModified();
		assertTrue(bodyFile.setLastModified(firstWrite - 60000));
		// Second save pass: the immutable body must not be rewritten.
		ScenePersistence.writeBodies(original, store);
		store.flush();
		assertEquals(firstWrite - 60000, findBodyFile().lastModified());
	}

	private File findBodyFile() {
		for (File dir : root.listFiles()) {
			File candidate = new File(dir, "2.bin");
			if (candidate.isFile()) {
				return candidate;
			}
		}
		throw new AssertionError("body file not found");
	}

	@Test
	public void orphanedBodiesAreCleanedAtRestore() throws Exception {
		ServerScene original = buildScene();
		byte[] structure = ScenePersistence.encodeStructure(original);
		ScenePersistence.writeBodies(original, store);
		store.save(SCENE, 999, new byte[16]); // orphan: never part of the structure
		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals(1, result.warnings.size());
		assertTrue(result.warnings.get(0).contains("orphan"));
		assertFalse(store.listResources(SCENE).contains(999));
	}

	@Test
	public void encodeRefusesStagedDeltas() {
		ServerScene scene = new ServerScene(SCENE);
		scene.createCanvas(16, 16, 64);
		try {
			ScenePersistence.encodeStructure(scene);
			throw new AssertionError("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("Seal"));
		}
		assertTrue(scene.hasStagedDeltas());
		scene.sealBatch();
		assertFalse(scene.hasStagedDeltas());
		ScenePersistence.encodeStructure(scene); // batch boundary: fine
	}

	@Test
	public void structureStaysUnderTheNbtCeilingForTypicalScenes() throws Exception {
		byte[] structure = ScenePersistence.encodeStructure(buildScene());
		assertTrue("structure bytes: " + structure.length,
				structure.length < ScenePersistence.STRUCTURE_NBT_CEILING);
	}
}
