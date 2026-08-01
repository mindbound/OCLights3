package ds.mods.OCLights2.v2.persist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ds.mods.OCLights2.v2.protocol.CodecException;
import ds.mods.OCLights2.v2.protocol.SnapshotCodec;
import ds.mods.OCLights2.v2.protocol.V2Wire;
import ds.mods.OCLights2.v2.scene.ResourceInfo;
import ds.mods.OCLights2.v2.scene.SceneSnapshot;
import ds.mods.OCLights2.v2.scene.ServerScene;

/**
 * Scene persistence: structure through the snapshot codec, bodies through the
 * {@link ResourceStore}.
 *
 * The persisted STRUCTURE format IS the snapshot wire format ({@link SnapshotCodec}): one
 * codec, two duties — epoch, seq, tick, id counters, resource manifests, canvas command
 * lists (restored through the canonical publish() path), and nodes; texture bytes
 * deliberately absent. Consequence: the persistence format is versioned by
 * PROTOCOL_VERSION; a protocol bump is also a save-migration point (acceptable pre-release;
 * revisit at format freeze).
 *
 * Save flow (component layer, under the scene lock): call {@code SceneHost.saveBoundary()}
 * first — seal AND broadcast, never seal-and-discard (staged deltas are already applied to
 * server state; discarding the sealed batch silently desyncs every mirror) — then
 * {@link #persistStructure} (returns inline bytes for TE NBT, or spills to the store when
 * over {@link #STRUCTURE_NBT_CEILING}) and {@link #writeBodies}. Restore flow:
 * {@link #resolveStructure} + {@link #restore} (or the recommended
 * {@link #restoreOrFresh}), which re-attaches bodies with hash validation, degrades
 * missing/corrupt bodies to blank (warning, never a crash), prunes orphaned store entries —
 * and, when anything degraded, MINTS A FRESH EPOCH: a degraded restore is by definition a
 * divergent one, and surviving mirrors must hard-reset rather than silently keep the old
 * bytes.
 */
public final class ScenePersistence {
	private ScenePersistence() {}

	/** Structure above this must spill out-of-band (the S-02 chunk-NBT ceiling). */
	public static final int STRUCTURE_NBT_CEILING = 64 * 1024;

	public static final class RestoreResult {
		public final ServerScene scene;
		public final List<String> warnings;

		RestoreResult(ServerScene scene, List<String> warnings) {
			this.scene = scene;
			this.warnings = warnings;
		}
	}

	/** Encode structure (no bodies). Requires a batch boundary, like snapshot(). */
	public static byte[] encodeStructure(ServerScene scene) {
		return SnapshotCodec.encode(scene.snapshot());
	}

	/**
	 * Encode and place the structure: returns the bytes to inline into TE NBT when they fit
	 * under {@link #STRUCTURE_NBT_CEILING}, else spills them to the store and returns null
	 * (the TE NBT then records only a spill marker).
	 */
	public static byte[] persistStructure(ServerScene scene, ResourceStore store) {
		byte[] structure = encodeStructure(scene);
		if (structure.length <= STRUCTURE_NBT_CEILING) {
			return structure;
		}
		store.saveStructure(scene.sceneId, structure);
		return null;
	}

	/** Resolves the structure bytes from inline NBT bytes or the store's spill slot. */
	public static byte[] resolveStructure(String sceneId, byte[] inlineOrNull, ResourceStore store) {
		return inlineOrNull != null ? inlineOrNull : store.loadStructure(sceneId);
	}

	/**
	 * Persists texture bodies that the store does not already have. Bodies are immutable
	 * after creation (clone-once contract), so an existing body never needs rewriting —
	 * EXCEPT degraded ones, which are rewritten so the on-disk corrupt bytes converge to
	 * the blank body the scene actually holds.
	 */
	public static void writeBodies(ServerScene scene, ResourceStore store) {
		for (ResourceInfo res : scene.state().resources.values()) {
			if (res.type == V2Wire.RES_TEXTURE && res.bytes != null
					&& (res.degraded || !store.contains(scene.sceneId, res.id))) {
				store.save(scene.sceneId, res.id, res.bytes);
			}
		}
	}

	/**
	 * Rebuilds a scene from persisted structure + store bodies. Never throws for damaged
	 * BODIES — missing or hash-mismatched bytes become blank bodies with a recomputed hash
	 * and the degraded flag, reported in warnings, and the rebuilt scene gets a FRESH epoch
	 * (divergent restore). A clean restore continues the persisted epoch (the incarnation
	 * continues; surviving mirrors keep their seq discipline). Orphaned store entries are
	 * deleted.
	 *
	 * @throws CodecException when the STRUCTURE itself is unreadable — the caller decides
	 *         whether that means a fresh scene or a hard error ({@link #restoreOrFresh} is
	 *         the recommended chunk-load policy).
	 */
	public static RestoreResult restore(byte[] structure, ResourceStore store) throws CodecException {
		SceneSnapshot decoded = SnapshotCodec.decode(structure);
		List<String> warnings = new ArrayList<String>();
		boolean degradedAny = false;

		for (Map.Entry<Integer, ResourceInfo> e : decoded.state.resources.entrySet()) {
			ResourceInfo res = e.getValue();
			if (res.type != V2Wire.RES_TEXTURE)
				continue;
			byte[] bytes = store.load(decoded.sceneId, res.id);
			if (bytes != null && bytes.length == res.sizeBytes
					&& V2Wire.contentHash(bytes) == res.hash) {
				res.bytes = bytes;
			} else {
				byte[] blank = new byte[res.sizeBytes];
				ResourceInfo replacement = new ResourceInfo(res.id, res.type, res.width,
						res.height, res.sizeBytes, V2Wire.contentHash(blank));
				replacement.bytes = blank;
				replacement.degraded = true;
				e.setValue(replacement);
				degradedAny = true;
				warnings.add("Resource " + res.id + " body "
						+ (bytes == null ? "missing" : "failed validation")
						+ "; restored blank");
			}
		}

		for (int storedId : store.listResources(decoded.sceneId)) {
			if (!decoded.state.resources.containsKey(storedId)) {
				store.delete(decoded.sceneId, storedId);
				warnings.add("Deleted orphaned body " + storedId);
			}
		}

		int epoch = degradedAny ? ServerScene.mintEpoch() : decoded.epoch;
		ServerScene scene = new ServerScene(decoded.sceneId, decoded.seq, epoch, decoded.state);
		scene.setCurrentTick(decoded.serverTick);
		return new RestoreResult(scene, warnings);
	}

	/**
	 * The recommended chunk-load policy: restore, and on an unreadable structure fall back
	 * to a FRESH scene — deleting the scene's stored bodies first (they are unreferenced by
	 * anything and would otherwise leak) — with the failure recorded as a warning. Mirrors
	 * hard-reset correctly via the fresh scene's new epoch.
	 */
	public static RestoreResult restoreOrFresh(String sceneId, byte[] structureOrNull, ResourceStore store) {
		if (structureOrNull != null) {
			try {
				return restore(structureOrNull, store);
			} catch (CodecException e) {
				List<String> warnings = new ArrayList<String>();
				warnings.add("Structure unreadable (" + e.getMessage() + "); starting fresh");
				store.deleteScene(sceneId);
				return new RestoreResult(new ServerScene(sceneId), warnings);
			}
		}
		store.deleteScene(sceneId);
		return new RestoreResult(new ServerScene(sceneId), new ArrayList<String>());
	}
}
