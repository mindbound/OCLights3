package ds.mods.OCLights2.v2.persist;

import java.util.List;

/**
 * The out-of-band resource-body store: texture bytes are too large for TE NBT (the legacy
 * PNG-in-NBT approach is ISSUES S-02), so they live here, keyed by (sceneId, resId). The
 * Minecraft layer decides the root location (a directory under the world save); tests use
 * a temp directory.
 *
 * Contract (the four SaveHandler lifecycle gaps from the design review, closed by design):
 * writes may be asynchronous, but {@link #load} MUST observe any in-flight write for the
 * same key (wait on the pending future); {@link #delete}/{@link #deleteScene} remove bodies
 * on resource free / scene destruction (bodies must never leak — they are large);
 * {@link #flush} joins all pending writes (call on world unload / server stop so shutdown
 * never truncates a body); a load that returns null or fails validation is the caller's
 * degraded-path trigger, never an exception.
 */
public interface ResourceStore {
	/**
	 * Asynchronously persists bytes for (sceneId, resId), overwriting any previous body.
	 * Implementations MUST capture a private copy of {@code bytes} before returning —
	 * callers may reuse or mutate the array afterward (the future writeRegion op will).
	 */
	void save(String sceneId, int resId, byte[] bytes);

	/**
	 * Returns the stored bytes, or null when absent/unreadable. Waits on an in-flight save.
	 * The returned array is owned exclusively by the caller (it is attached into live scene
	 * state whose consumers rely on reference-stable, immutable bytes).
	 */
	byte[] load(String sceneId, int resId);

	/** True when a body exists (stored or write-in-flight) for the key. */
	boolean contains(String sceneId, int resId);

	/**
	 * Removes one body. Contract note: per-resource deletes belong at the NEXT structure-save
	 * boundary after the free (an eagerly deleted body may still be referenced by the last
	 * saved structure — a crash then degrades it to blank avoidably); restore-time orphan
	 * pruning is the backstop. {@link #deleteScene} on block break stays eager.
	 */
	void delete(String sceneId, int resId);

	/** Removes every body and the structure blob of a scene (scene destroyed / GPU broken). */
	void deleteScene(String sceneId);

	/** Lists the resource ids currently stored for a scene (for orphan cleanup at restore). */
	List<Integer> listResources(String sceneId);

	/** Persists an oversized structure blob (the NBT-ceiling spill path). */
	void saveStructure(String sceneId, byte[] structure);

	/** Loads the spilled structure blob, or null. Excluded from listResources/orphan pruning. */
	byte[] loadStructure(String sceneId);

	/** Joins all pending writes. Call on world unload / server stop. */
	void flush();
}
