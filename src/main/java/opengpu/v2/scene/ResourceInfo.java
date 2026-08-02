package opengpu.v2.scene;

import java.util.Arrays;

import opengpu.v2.protocol.V2Wire;

/**
 * One scene resource. Textures carry bytes on the server (persistence + texture-space
 * queries); on a mirror the bytes may be absent until the body transfer completes — that is
 * the designed *pending* state, and referencing nodes render a placeholder until it clears.
 * Canvas resources carry a {@link SceneCanvas} (command-list backed, no bytes by design).
 *
 * <h2>Texture content is MUTABLE (protocol v3)</h2>
 * This is the file where the old clone-once immutability invariant died. {@code writeRegion}
 * mutates texture bytes in place, so a content hash can no longer be the identity: hashing a
 * whole texture per write is O(size) per call, which the streaming workload cannot pay.
 *
 * Identity is instead a monotone {@code version}, and the state is DERIVED from two ints so
 * there is no flag to drift:
 * <pre>
 *   bytes == null                      -> PENDING  (render the placeholder)
 *   bytes != null, version == latest   -> CURRENT  (render the content)
 *   bytes != null, version &lt;  latest   -> STALE    (render last known, refetch scheduled)
 *   version &gt; latestVersion            -> impossible; protocol anomaly -> resync
 * </pre>
 *
 * The hash survives only where it is already free: {@code knownHash} is computed once per
 * body serve and once per version for persistence, and travels as a verifiable cache hint
 * ({@code knownHash} is the content of version {@code knownHashVersion}, not necessarily of
 * the current one). It is never recomputed on a write.
 */
public final class ResourceInfo {
	public final int id;
	public final byte type;
	public final int width;
	public final int height;
	public final int sizeBytes;

	/**
	 * The version whose content {@link #bytes} holds. 0 = none held. Server: always equal to
	 * {@link #latestVersion}. Mirror: may lag while a body is in flight.
	 */
	public int version;
	/**
	 * The newest version this side has HEARD OF, derived purely from the delta stream.
	 * Advances by exactly 1 per applied write, so a mismatch is an independent divergence
	 * detector alongside sequence gaps.
	 */
	public int latestVersion;
	/** Content hash of version {@link #knownHashVersion}; 0 when none is known. */
	public long knownHash;
	/** The version {@link #knownHash} describes; 0 = unknown. Never exceeds the version. */
	public int knownHashVersion;

	/** Server: always set for textures. Mirror: null while the body transfer is pending. */
	public byte[] bytes;
	/** Set iff type == RES_CANVAS. */
	public SceneCanvas canvas;
	/**
	 * Server-side only, never on the wire: the body was missing or failed hash validation at
	 * restore and was replaced with blank bytes (world-backup tear). Lua-queryable later.
	 */
	public boolean degraded;
	/**
	 * Server-side only, never on the wire: the version whose bytes are already in the
	 * out-of-band store, so a save can skip rewriting an unchanged texture.
	 */
	public int persistedVersion;

	// Client-side only, never on the wire and never copied: the region of the GL texture that
	// no longer matches `bytes`, so an upload can send a sub-rectangle instead of the whole
	// image. dirtyW == 0 means empty.
	public int dirtyX, dirtyY, dirtyW, dirtyH;

	public ResourceInfo(int id, byte type, int width, int height, int sizeBytes) {
		this.id = id;
		this.type = type;
		this.width = width;
		this.height = height;
		this.sizeBytes = sizeBytes;
	}

	public boolean isPending() {
		return type == V2Wire.RES_TEXTURE && bytes == null;
	}

	/** True when a body fetch would make this resource current: absent or superseded bytes. */
	public boolean needsBody() {
		return type == V2Wire.RES_TEXTURE && (bytes == null || version != latestVersion);
	}

	public void unionDirtyRect(int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (dirtyW == 0) {
			dirtyX = x;
			dirtyY = y;
			dirtyW = w;
			dirtyH = h;
			return;
		}
		int x0 = Math.min(dirtyX, x);
		int y0 = Math.min(dirtyY, y);
		int x1 = Math.max(dirtyX + dirtyW, x + w);
		int y1 = Math.max(dirtyY + dirtyH, y + h);
		dirtyX = x0;
		dirtyY = y0;
		dirtyW = x1 - x0;
		dirtyH = y1 - y0;
	}

	public void markFullDirty() {
		dirtyX = 0;
		dirtyY = 0;
		dirtyW = width;
		dirtyH = height;
	}

	public void clearDirty() {
		dirtyX = dirtyY = dirtyW = dirtyH = 0;
	}

	public ResourceInfo copy() {
		ResourceInfo r = copyStructure();
		r.bytes = bytes == null ? null : bytes.clone();
		r.canvas = canvas == null ? null : canvas.copy();
		return r;
	}

	/** Every scalar, no bytes and no canvas clone — for snapshots, which strip bodies anyway. */
	public ResourceInfo copyStructure() {
		ResourceInfo r = new ResourceInfo(id, type, width, height, sizeBytes);
		r.version = version;
		r.latestVersion = latestVersion;
		r.knownHash = knownHash;
		r.knownHashVersion = knownHashVersion;
		r.degraded = degraded;
		r.persistedVersion = persistedVersion;
		// The dirty rect is deliberately NOT carried: it describes one client's GL state.
		return r;
	}

	public boolean contentEquals(ResourceInfo other) {
		if (id != other.id || type != other.type || width != other.width
				|| height != other.height || sizeBytes != other.sizeBytes)
			return false;
		// latestVersion is derived purely from the delta stream and converges by construction,
		// so it is compared unconditionally — this catches a missed write even against a
		// mirror that is still pending its first body.
		if (latestVersion != other.latestVersion)
			return false;
		if ((canvas == null) != (other.canvas == null))
			return false;
		if (canvas != null && !canvas.contentEquals(other.canvas))
			return false;
		// Bytes may legitimately be absent on a mirror; when both sides hold content, the
		// version they hold must match and the bytes must be identical.
		if (bytes != null && other.bytes != null) {
			if (version != other.version || !Arrays.equals(bytes, other.bytes))
				return false;
		}
		return true;
	}
}
