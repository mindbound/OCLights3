package opengpu.v2.persist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.LegacyStructureCodec;
import opengpu.v2.protocol.SnapshotCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.ServerScene;

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
	 * Persists texture bodies whose content has changed since the last save. Bodies are NO
	 * LONGER immutable (writeRegion mutates them in place), so "already stored" is not a
	 * reason to skip — {@code persistedVersion} is. Degraded bodies are always rewritten so
	 * the on-disk corrupt bytes converge to the blank body the scene actually holds.
	 */
	/**
	 * Persist changed texture bodies as self-describing blobs.
	 *
	 * Bodies are no longer immutable, so "already stored" is not a reason to skip — the
	 * version is. {@code persistedVersion} means an unchanged texture still costs nothing,
	 * while a written one is rewritten exactly once per save.
	 */
	public static void writeBodies(ServerScene scene, ResourceStore store) {
		for (ResourceInfo res : scene.state().resources.values()) {
			if (res.type != V2Wire.RES_TEXTURE || res.bytes == null)
				continue;
			boolean stale = res.persistedVersion != res.version
					|| !store.contains(scene.sceneId, res.id);
			if (!stale && !res.degraded)
				continue;
			// Reuse the hash if a body serve already computed it for this version.
			long hash = res.knownHashVersion == res.version
					? res.knownHash : V2Wire.contentHash(res.bytes);
			if (res.knownHashVersion != res.version) {
				res.knownHash = hash;
				res.knownHashVersion = res.version;
				scene.bumpManifestGen();
			}
			store.save(scene.sceneId, res.id, frameBody(res.version, hash, res.bytes));
			res.persistedVersion = res.version;
		}
	}

	/**
	 * Self-describing body blob: magic + format + version + hash + length + payload.
	 *
	 * The structure and the bodies live in different files written at different times — the
	 * TE's NBT is deferred by Minecraft's threaded IO while bodies are fsync'd immediately —
	 * so after a hard kill they can disagree. A blob that validates against ITSELF stays
	 * usable regardless, which is what stops a stale manifest hash from blanking every
	 * texture the player had written.
	 */
	static byte[] frameBody(int version, long hash, byte[] payload) {
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(22 + payload.length);
		buf.putInt(V2Wire.PERSIST_BODY_MAGIC);
		buf.putShort(V2Wire.PERSIST_BODY_FORMAT);
		buf.putInt(version);
		buf.putLong(hash);
		buf.putInt(payload.length);
		buf.put(payload);
		return buf.array();
	}

	/** Decoded blob, or null when the bytes are not a valid self-describing body. */
	static ParsedBody parseBody(byte[] blob) {
		if (blob == null || blob.length < 22)
			return null;
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(blob);
		if (buf.getInt() != V2Wire.PERSIST_BODY_MAGIC)
			return null;
		if (buf.getShort() != V2Wire.PERSIST_BODY_FORMAT)
			return null;
		int version = buf.getInt();
		long hash = buf.getLong();
		int len = buf.getInt();
		if (version < 1 || len < 0 || len != blob.length - 22)
			return null;
		byte[] payload = new byte[len];
		buf.get(payload);
		if (V2Wire.contentHash(payload) != hash)
			return null;
		return new ParsedBody(version, hash, payload);
	}

	static final class ParsedBody {
		final int version;
		final long hash;
		final byte[] payload;

		ParsedBody(int version, long hash, byte[] payload) {
			this.version = version;
			this.hash = hash;
			this.payload = payload;
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
		// Dispatch on the persisted version BEFORE attempting a current-version decode. Doing
		// this from inside a catch would be catastrophic: restoreOrFresh answers a
		// CodecException by deleting the scene's stored bodies, so every pre-upgrade world
		// would lose its textures the first time it loaded.
		//
		// TWO KINDS OF OLD SAVE, and they need different handling:
		//   v2  — a genuinely different layout (one content hash where v3 carries three
		//         fields), so it needs its own decoder.
		//   v3  — the same layout under an older version number, so decodePersisted reads it
		//         directly. The 3 -> 4 bump appended an op and moved no field.
		// decodePersisted, not decode: the strict check belongs on the NETWORK path, where the
		// peer can be told to upgrade. Here, refusing means deleting the world's scenes.
		SceneSnapshot decoded =
				LegacyStructureCodec.peekVersion(structure) == LegacyStructureCodec.V2_VERSION
						? LegacyStructureCodec.decodeV2(structure)
						: SnapshotCodec.decodePersisted(structure);
		List<String> warnings = new ArrayList<String>();
		boolean degradedAny = false;

		for (Map.Entry<Integer, ResourceInfo> e : decoded.state.resources.entrySet()) {
			ResourceInfo res = e.getValue();
			if (res.type != V2Wire.RES_TEXTURE)
				continue;
			byte[] blob = store.load(decoded.sceneId, res.id);
			ParsedBody parsed = parseBody(blob);
			boolean legacyRaw = false;
			if (parsed == null && blob != null && blob.length == res.sizeBytes
					&& V2Wire.contentHash(blob) == res.knownHash) {
				legacyRaw = true;
				// Legacy (pre-v3) raw body: unframed, and validated against the manifest hash
				// exactly as v2 did. A framed blob is 22 + sizeBytes bytes and so can never
				// be mistaken for one. Re-framed on the next save.
				parsed = new ParsedBody(res.latestVersion, res.knownHash, blob);
			}
			if (parsed != null && parsed.payload.length == res.sizeBytes) {
				res.bytes = parsed.payload;
				res.version = parsed.version;
				res.knownHash = parsed.hash;
				res.knownHashVersion = parsed.version;
				// A legacy raw body is deliberately NOT marked persisted, so the next save
				// rewrites it in the self-describing frame. Marking it would mean the
				// migration shim could never be deleted.
				res.persistedVersion = legacyRaw ? 0 : parsed.version;
				// The blob is self-validating, so IT is the authority on which version the
				// bytes are. If the structure was written before the last body flush (or
				// after it, post-crash), reconcile rather than blanking usable content.
				if (parsed.version > res.latestVersion) {
					// The body is ahead of the structure: a crash between the (immediate)
					// body fsync and the (deferred) NBT write. The content is real, but
					// surviving mirrors were told about an older version, so this is a
					// divergent restore and must mint a fresh epoch like the rewind case.
					warnings.add("Resource " + res.id + " body is version " + parsed.version
							+ ", structure expected " + res.latestVersion
							+ "; adopting the newer body");
					res.latestVersion = parsed.version;
					degradedAny = true;
				} else if (parsed.version < res.latestVersion) {
					warnings.add("Resource " + res.id + " body is version " + parsed.version
							+ ", structure expected " + res.latestVersion
							+ "; keeping the body and rewinding");
					res.latestVersion = parsed.version;
					degradedAny = true; // mirrors must hard-reset: this is a divergent restore
				}
			} else {
				byte[] blank = new byte[res.sizeBytes];
				ResourceInfo replacement = new ResourceInfo(res.id, res.type, res.width,
						res.height, res.sizeBytes);
				replacement.bytes = blank;
				replacement.version = 1;
				replacement.latestVersion = 1;
				replacement.knownHash = V2Wire.contentHash(blank);
				replacement.knownHashVersion = 1;
				replacement.degraded = true;
				e.setValue(replacement);
				degradedAny = true;
				warnings.add("Resource " + res.id + " body "
						+ (blob == null ? "missing" : "failed validation")
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
