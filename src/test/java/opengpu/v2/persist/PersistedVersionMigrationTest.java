package opengpu.v2.persist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.SnapshotCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneSnapshot;

/**
 * The v3 -> v4 save migration: a world written before the font change must still load.
 *
 * WHY THIS IS NOT THE SAME SHAPE AS {@code LegacyMigrationTest}. v2 -> v3 changed the resource
 * record, so it needed its own decoder. v3 -> v4 changed NOTHING the structure codec reads: the
 * bump appended OP_SET_FONT to the op table and moved no field. So a v3 structure is the current
 * layout under an older version number, and the only thing rejecting it was the decoder's strict
 * version equality — which exists for the NETWORK, where a peer of the wrong vintage really is an
 * error and can be told to upgrade.
 *
 * The consequence of getting this wrong is not a crash. {@code restoreOrFresh} answers a
 * CodecException by calling {@code store.deleteScene}, so a missing migration silently DESTROYS
 * every pre-upgrade world's scenes and texture bodies on first chunk load, and the symptom is a
 * blank screen that any redraw hides. That is why {@link #restoreOrFreshMustNotDeleteAV3Scene}
 * drives the real chunk-load policy rather than the codec alone.
 *
 * The v3 STRUCTURE bytes are written by hand, deliberately: producing them with the current
 * encoder would make the test tautological — encode under v4 rules, decode under v4 rules, always
 * passes. The arities below are transcribed as v3 had them, so changing the arity of an existing
 * op (which would genuinely break old saves) fails here. Body FRAMING is not hand-written: it did
 * not change across this bump and is not what is under test, so the fixture uses the same
 * {@code frameBody} the production save path uses — which is why this test lives in this package.
 */
public class PersistedVersionMigrationTest {

	private static final short V3 = 3;

	/** v3's arities for the ops used below, transcribed rather than read back from V2Wire. */
	private static final int V3_ARGS_SET_COLOR = 4;
	private static final int V3_ARGS_FILL = 0;
	private static final int V3_ARGS_DRAW_TEXT = 2;

	private static final int TEX_ID = 2;
	private static final int TEX_DIM = 4;
	private static final int TEX_VERSION = 2;
	private static final int EPOCH = 0x0FACE;

	private File root;
	private DirectoryResourceStore store;

	@Before
	public void setUp() throws Exception {
		root = File.createTempFile("opengpu-v3-migration", "");
		assertTrue(root.delete());
		assertTrue(root.mkdirs());
		store = new DirectoryResourceStore(root);
	}

	@After
	public void tearDown() {
		store.close();
		deleteRecursively(root);
	}

	private static void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		file.delete();
	}

	/** The texture bytes the fixture stores, and whose hash the structure must claim. */
	private static byte[] textureBody() {
		byte[] body = new byte[TEX_DIM * TEX_DIM * 4];
		for (int i = 0; i < body.length; i++) {
			body[i] = (byte) (i * 7);
		}
		return body;
	}

	/** Writes the v3 persisted-structure layout, field for field, under a chosen version short. */
	private static final class StructureWriter {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(bytes);

		StructureWriter(short version, String sceneId, int epoch, int seq, long tick,
				int nextResourceId, int nextNodeId) throws IOException {
			out.writeShort(version);
			out.writeUTF(sceneId);
			out.writeInt(epoch);
			out.writeInt(seq);
			out.writeLong(tick);
			out.writeInt(nextResourceId);
			out.writeInt(nextNodeId);
		}

		StructureWriter resources(int count) throws IOException {
			out.writeInt(count);
			return this;
		}

		/** v3 resource record: three version/hash fields where v2 carried a single hash. */
		StructureWriter texture(int id, int w, int h, int latestVersion, int knownHashVersion,
				long hash) throws IOException {
			out.writeInt(id);
			out.writeByte(V2Wire.RES_TEXTURE);
			out.writeInt(w);
			out.writeInt(h);
			out.writeInt(w * h * 4);
			out.writeInt(latestVersion);
			out.writeInt(knownHashVersion);
			out.writeLong(hash);
			return this;
		}

		/**
		 * A canvas holding ops a v3 world could actually contain. Text is included on purpose:
		 * DRAW_TEXT is what the font change was about, and a v3 save carries one with no
		 * OP_SET_FONT in front of it — the case the renderer's per-canvas reset to FONT_DEFAULT
		 * is what makes well-defined.
		 */
		StructureWriter canvasWithContent(int id, int w, int h, int cap) throws IOException {
			out.writeInt(id);
			out.writeByte(V2Wire.RES_CANVAS);
			out.writeInt(w);
			out.writeInt(h);
			out.writeInt(0);
			out.writeInt(1);   // canvases are written as version 1
			out.writeInt(0);   // no hash version
			out.writeLong(0L); // no hash
			out.writeInt(cap);
			out.writeInt(3);   // command count
			op(V2Wire.OP_SET_COLOR, V3_ARGS_SET_COLOR, 10, 20, 30, 255);
			op(V2Wire.OP_FILL, V3_ARGS_FILL);
			out.writeByte(V2Wire.OP_DRAW_TEXT);
			for (int i = 0; i < V3_ARGS_DRAW_TEXT; i++) {
				out.writeDouble(i == 0 ? 4 : 6);
			}
			out.writeUTF("pre-font world");
			return this;
		}

		private void op(byte code, int argc, double... args) throws IOException {
			assertEquals("transcribed arity disagrees with the args given", argc, args.length);
			out.writeByte(code);
			for (double a : args) {
				out.writeDouble(a);
			}
		}

		StructureWriter nodes(int count) throws IOException {
			out.writeInt(count);
			return this;
		}

		StructureWriter node(int id, byte type, int ref, double x, double y, int z, int tint)
				throws IOException {
			out.writeInt(id);
			out.writeByte(type);
			out.writeInt(ref);
			out.writeDouble(x);
			out.writeDouble(y);
			out.writeDouble(0); // rot
			out.writeDouble(1); // sx
			out.writeDouble(1); // sy
			out.writeInt(z);
			out.writeBoolean(true);
			out.writeInt(tint);
			return this;
		}

		byte[] done() throws IOException {
			out.flush();
			return bytes.toByteArray();
		}
	}

	/** A representative pre-font world: a drawn canvas, a texture, and a node for each. */
	private static byte[] sample(short version) throws IOException {
		StructureWriter w = new StructureWriter(version, "gpu-addr", EPOCH, 7, 900L, 3, 3);
		w.resources(2)
				.canvasWithContent(1, 512, 288, 4096)
				.texture(TEX_ID, TEX_DIM, TEX_DIM, TEX_VERSION, TEX_VERSION,
						V2Wire.contentHash(textureBody()));
		w.nodes(2)
				.node(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0xFFFFFFFF)
				.node(2, V2Wire.NODE_SPRITE, TEX_ID, 3.5, -1.25, 2, 0x80FF00FF);
		return w.done();
	}

	@Test
	public void aV3StructureDecodesThroughThePersistencePath() throws Exception {
		SceneSnapshot snap = SnapshotCodec.decodePersisted(sample(V3));

		assertEquals("gpu-addr", snap.sceneId);
		assertEquals("the incarnation must CONTINUE — nothing here is degraded", EPOCH, snap.epoch);
		assertEquals(7, snap.seq);
		assertEquals(900L, snap.serverTick);
		assertEquals(3, snap.state.nextResourceId);
		assertEquals(3, snap.state.nextNodeId);

		ResourceInfo canvas = snap.state.resources.get(1);
		assertNotNull("the canvas must survive", canvas);
		assertNotNull("with its command list", canvas.canvas);
		assertEquals("every v3 command decodes under the v4 arity table",
				3, canvas.canvas.visibleCommands().size());
		assertEquals("the text op keeps its string", "pre-font world",
				canvas.canvas.visibleCommands().get(2).text);

		ResourceInfo tex = snap.state.resources.get(TEX_ID);
		assertEquals(V2Wire.contentHash(textureBody()), tex.knownHash);
		assertEquals(TEX_VERSION, tex.latestVersion);
		assertEquals(TEX_VERSION, tex.knownHashVersion);

		SceneNode sprite = snap.state.nodes.get(2);
		assertEquals(3.5, sprite.x, 1e-9);
		assertEquals(0x80FF00FF, sprite.tint);
	}

	@Test
	public void theNetworkDecoderStillRefusesAV3Payload() throws Exception {
		// The asymmetry IS the design. A peer of the wrong vintage disagrees about the op table,
		// so decoding its payload risks reading one op's argument as another's — and it can be
		// told to upgrade. A save on disk can do neither, and refusing it destroys it.
		try {
			SnapshotCodec.decode(sample(V3));
			fail("the network decoder must stay strict at PROTOCOL_VERSION");
		} catch (CodecException expected) {
			assertTrue("the error should name the version, got: " + expected.getMessage(),
					expected.getMessage().toLowerCase().contains("version"));
		}
	}

	@Test
	public void anUnknownVersionIsRefusedOnBOTHPaths() throws Exception {
		// Tolerating v3 must not become tolerating anything. An unvetted version could have a
		// different layout, and misreading one yields a plausible scene assembled from misaligned
		// bytes — strictly worse than starting fresh, which is at least visible.
		byte[] alien = sample((short) 99);
		for (boolean persisted : new boolean[] { false, true }) {
			try {
				if (persisted) {
					SnapshotCodec.decodePersisted(alien);
				} else {
					SnapshotCodec.decode(alien);
				}
				fail("version 99 must be refused on the " + (persisted ? "persistence" : "network")
						+ " path");
			} catch (CodecException expected) {
				assertTrue(expected.getMessage().toLowerCase().contains("version"));
			}
		}
	}

	@Test
	public void restoreOrFreshMustNotDeleteAV3Scene() throws Exception {
		// The actual data-loss path, driven through the real chunk-load policy rather than the
		// codec alone: before the fix this returned a fresh scene AND wiped the stored bodies.
		String sceneId = "gpu-addr";
		byte[] body = textureBody();
		store.save(sceneId, TEX_ID,
				ScenePersistence.frameBody(TEX_VERSION, V2Wire.contentHash(body), body));
		store.flush();

		ScenePersistence.RestoreResult result =
				ScenePersistence.restoreOrFresh(sceneId, sample(V3), store);

		assertTrue("a clean v3 restore must report no warnings, got: " + result.warnings,
				result.warnings.isEmpty());
		assertEquals("the scene must keep its identity, not be replaced by a fresh one",
				EPOCH, result.scene.epoch());
		assertEquals("both resources must survive", 2, result.scene.state().resources.size());
		assertNotNull("the stored texture body must still be on disk", store.load(sceneId, TEX_ID));
		assertEquals("and it must be the same bytes, not a blank degrade",
				body.length, result.scene.state().resources.get(TEX_ID).bytes.length);
		assertEquals("byte 3 survived", body[3],
				result.scene.state().resources.get(TEX_ID).bytes[3]);
	}

	@Test
	public void aTrulyUnreadableStructureStillDegradesToFresh() throws Exception {
		// Accepting v3 must not turn a corrupt structure into an exception escaping through chunk
		// load. Version 3 in the header, garbage after it.
		ScenePersistence.RestoreResult result =
				ScenePersistence.restoreOrFresh("gpu-addr", new byte[] { 0, 3, 9, 9 }, store);
		assertNotNull(result.scene);
		assertTrue("the failure must be reported", !result.warnings.isEmpty());
	}

	/**
	 * The guard that makes the NEXT bump decide, instead of silently repeating this bug.
	 *
	 * Nothing else can catch the omission. A persisted-version gap is invisible on the build that
	 * introduces it — every save that build writes is current-version, so every test is green and
	 * every in-game load works. It only appears on a world written by the PREVIOUS build, which
	 * nothing in CI has. This fails the moment PROTOCOL_VERSION moves, next to the instructions.
	 */
	private static final short VERSION_THIS_TEST_WAS_WRITTEN_FOR = 4;

	@Test
	public void aProtocolBumpMustDecideWhatHappensToTheOutgoingFormat() {
		assertEquals("PROTOCOL_VERSION moved to " + V2Wire.PROTOCOL_VERSION + ". Decide, in the"
				+ " SAME edit, what happens to saves written as "
				+ VERSION_THIS_TEST_WAS_WRITTEN_FOR + ": if the structure layout is unchanged, add"
				+ " it to SnapshotCodec.LAYOUT_COMPATIBLE_PERSISTED_VERSIONS; if any field moved,"
				+ " write a decoder for the old layout as LegacyStructureCodec does for v2. Doing"
				+ " NEITHER deletes every existing world's scenes on first chunk load. Then update"
				+ " this constant and add a case above.",
				VERSION_THIS_TEST_WAS_WRITTEN_FOR, V2Wire.PROTOCOL_VERSION);
	}
}
