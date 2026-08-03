package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.Test;

import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.LegacyStructureCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneSnapshot;

/**
 * The v2 -> v3 save migration, which had NO coverage at all.
 *
 * This is the highest-consequence untested path in the project: ScenePersistence.restore
 * dispatches on the peeked version, and restoreOrFresh answers a CodecException by DELETING the
 * scene and its stored texture bodies. A migration bug therefore does not fail loudly — it
 * silently destroys pre-upgrade worlds on first chunk load.
 *
 * There is no v2 encoder (the format is read-only by design), so these build the legacy bytes by
 * hand. That is deliberate: this file is now the only executable record of the v2 layout, and a
 * decoder change that silently stops matching it fails here.
 */
public class LegacyMigrationTest {

	/** Writes the v2 persisted-structure layout, field for field. */
	private static final class V2Writer {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(bytes);

		V2Writer(String sceneId, int epoch, int seq, long tick,
				int nextResourceId, int nextNodeId) throws IOException {
			out.writeShort(LegacyStructureCodec.V2_VERSION);
			out.writeUTF(sceneId);
			out.writeInt(epoch);
			out.writeInt(seq);
			out.writeLong(tick);
			out.writeInt(nextResourceId);
			out.writeInt(nextNodeId);
		}

		V2Writer resources(int count) throws IOException {
			out.writeInt(count);
			return this;
		}

		/** v2 resource record: the ONLY difference from v3 — one hash, no version fields. */
		V2Writer texture(int id, int w, int h, long hash) throws IOException {
			out.writeInt(id);
			out.writeByte(V2Wire.RES_TEXTURE);
			out.writeInt(w);
			out.writeInt(h);
			out.writeInt(w * h * 4);
			out.writeLong(hash);
			return this;
		}

		V2Writer emptyCanvas(int id, int w, int h, int cap) throws IOException {
			out.writeInt(id);
			out.writeByte(V2Wire.RES_CANVAS);
			out.writeInt(w);
			out.writeInt(h);
			out.writeInt(0);
			out.writeLong(0L);
			out.writeInt(cap);
			out.writeInt(0); // command count
			return this;
		}

		V2Writer nodes(int count) throws IOException {
			out.writeInt(count);
			return this;
		}

		V2Writer node(int id, byte type, int ref, double x, double y, int z, int tint)
				throws IOException {
			out.writeInt(id);
			out.writeByte(type);
			out.writeInt(ref);
			out.writeDouble(x);
			out.writeDouble(y);
			out.writeDouble(0);   // rot
			out.writeDouble(1);   // sx
			out.writeDouble(1);   // sy
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

	/** A representative v2 world: one texture, one canvas, a canvas node and a sprite. */
	private static byte[] sampleV2() throws IOException {
		V2Writer w = new V2Writer("gpu-addr", 0x5EED, 42, 1234L, 3, 3);
		w.resources(2).emptyCanvas(1, 512, 288, 4096).texture(2, 8, 8, 0xABCDEF12L);
		w.nodes(2)
				.node(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0xFFFFFFFF)
				.node(2, V2Wire.NODE_SPRITE, 2, 10.5, -4.25, 3, 0x80FF0000);
		return w.done();
	}

	@Test
	public void aV2StructureMigratesWithItsIdentityIntact() throws Exception {
		SceneSnapshot snap = LegacyStructureCodec.decodeV2(sampleV2());

		assertEquals("gpu-addr", snap.sceneId);
		assertEquals("the incarnation must CONTINUE, not be reminted", 0x5EED, snap.epoch);
		assertEquals(42, snap.seq);
		assertEquals(1234L, snap.serverTick);
		assertEquals(2, snap.state.resources.size());
		assertEquals(2, snap.state.nodes.size());
		assertEquals("id counters must survive — persisted lists reference these",
				3, snap.state.nextResourceId);
		assertEquals(3, snap.state.nextNodeId);
	}

	@Test
	public void theV2HashBecomesTheV3VersionedCacheHint() throws Exception {
		// The whole point of the migration. v2 carried ONE hash; v3 carries
		// (version, latestVersion, knownHashVersion, knownHash). Getting this derivation wrong
		// would not throw — it would make every restored texture fail its body validation and
		// degrade to blank, i.e. quietly wipe the pictures in every pre-upgrade world.
		SceneSnapshot snap = LegacyStructureCodec.decodeV2(sampleV2());
		ResourceInfo tex = snap.state.resources.get(2);
		assertNotNull(tex);
		assertEquals(V2Wire.RES_TEXTURE, tex.type);
		assertEquals(8, tex.width);
		assertEquals(8 * 8 * 4, tex.sizeBytes);
		assertEquals("everything in a v2 world is version-1 content", 1, tex.latestVersion);
		assertEquals("no body attached yet, so held version is 0", 0, tex.version);
		assertEquals("the v2 hash must become the v1 cache hint", 0xABCDEF12L, tex.knownHash);
		assertEquals(1, tex.knownHashVersion);
	}

	@Test
	public void nodesAndCanvasesSurviveIntact() throws Exception {
		SceneSnapshot snap = LegacyStructureCodec.decodeV2(sampleV2());

		ResourceInfo canvas = snap.state.resources.get(1);
		assertEquals(V2Wire.RES_CANVAS, canvas.type);
		assertNotNull("a migrated canvas must carry its command list", canvas.canvas);
		assertEquals(512, canvas.width);

		SceneNode sprite = snap.state.nodes.get(2);
		assertEquals(V2Wire.NODE_SPRITE, sprite.type);
		assertEquals(2, sprite.ref);
		assertEquals(10.5, sprite.x, 1e-9);
		assertEquals(-4.25, sprite.y, 1e-9);
		assertEquals(3, sprite.z);
		assertEquals(0x80FF0000, sprite.tint);
		assertTrue(sprite.visible);
	}

	@Test
	public void peekVersionDispatchesWithoutDecoding() throws Exception {
		// restore() MUST dispatch on the peeked version rather than from inside a catch,
		// because the caller answers a CodecException by deleting the scene's bodies.
		assertEquals(LegacyStructureCodec.V2_VERSION,
				LegacyStructureCodec.peekVersion(sampleV2()));
		assertEquals("a v3 structure must not be peeked as v2",
				V2Wire.PROTOCOL_VERSION, LegacyStructureCodec.peekVersion(
						new byte[] { (byte) (V2Wire.PROTOCOL_VERSION >> 8),
								(byte) V2Wire.PROTOCOL_VERSION, 0, 0 }));
		assertEquals(-1, LegacyStructureCodec.peekVersion(null));
		assertEquals(-1, LegacyStructureCodec.peekVersion(new byte[] { 7 }));
		assertEquals(-1, LegacyStructureCodec.peekVersion(new byte[0]));
	}

	private static void expectReject(byte[] data, String fragment) {
		try {
			LegacyStructureCodec.decodeV2(data);
			fail("expected a rejection mentioning: " + fragment);
		} catch (CodecException expected) {
			assertTrue("wrong message: " + expected.getMessage(),
					expected.getMessage().toLowerCase().contains(fragment));
		}
	}

	@Test
	public void corruptV2StructuresAreRejectedRatherThanHalfRead() throws Exception {
		// Each of these degrades to a fresh scene, which is bad but survivable; half-reading
		// one into a live scene would be worse.
		byte[] good = sampleV2();

		byte[] truncated = new byte[good.length / 2];
		System.arraycopy(good, 0, truncated, 0, truncated.length);
		expectReject(truncated, "truncated");

		byte[] trailing = new byte[good.length + 1];
		System.arraycopy(good, 0, trailing, 0, good.length);
		expectReject(trailing, "trailing");

		V2Writer zeroEpoch = new V2Writer("s", 0, 0, 0L, 1, 1);
		zeroEpoch.resources(0).nodes(0);
		expectReject(zeroEpoch.done(), "epoch");

		V2Writer dupRes = new V2Writer("s", 1, 0, 0L, 9, 1);
		dupRes.resources(2).texture(1, 2, 2, 0L).texture(1, 2, 2, 0L);
		dupRes.nodes(0);
		expectReject(dupRes.done(), "duplicate");

		V2Writer badDims = new V2Writer("s", 1, 0, 0L, 9, 1);
		badDims.resources(1).texture(1, 0, 4, 0L);
		badDims.nodes(0);
		expectReject(badDims.done(), "dimensions");

		// A counter behind the highest id would let a later allocation collide with live state.
		V2Writer staleCounter = new V2Writer("s", 1, 0, 0L, 1, 1);
		staleCounter.resources(1).texture(5, 2, 2, 0L);
		staleCounter.nodes(0);
		expectReject(staleCounter.done(), "nextresourceid");
	}

	@Test
	public void aV3StructureIsNotAcceptedByTheV2Decoder() throws Exception {
		// The dispatch is one-way on purpose: the network decoders stay strict at the current
		// version, and only the persistence path tolerates the old layout.
		byte[] v3ish = new byte[] { (byte) (V2Wire.PROTOCOL_VERSION >> 8),
				(byte) V2Wire.PROTOCOL_VERSION, 0, 0, 0, 0 };
		expectReject(v3ish, "not a v2 structure");
	}
}
