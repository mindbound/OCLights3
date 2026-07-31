package ds.mods.OCLights2.v2.scene;

import java.util.Arrays;

import ds.mods.OCLights2.v2.protocol.V2Wire;

/**
 * One scene resource. Textures carry bytes on the server (persistence + texture-space
 * queries); on a mirror the bytes may be absent until the body transfer completes — that is
 * the designed *pending* state, and referencing nodes render a placeholder until it clears.
 * Canvas resources carry a {@link SceneCanvas} (command-list backed, no bytes by design).
 */
public final class ResourceInfo {
	public final int id;
	public final byte type;
	public final int width;
	public final int height;
	public final int sizeBytes;
	public final long hash;

	/** Server: always set for textures. Mirror: null while the body transfer is pending. */
	public byte[] bytes;
	/** Set iff type == RES_CANVAS. */
	public SceneCanvas canvas;

	public ResourceInfo(int id, byte type, int width, int height, int sizeBytes, long hash) {
		this.id = id;
		this.type = type;
		this.width = width;
		this.height = height;
		this.sizeBytes = sizeBytes;
		this.hash = hash;
	}

	public boolean isPending() {
		return type == V2Wire.RES_TEXTURE && bytes == null;
	}

	public ResourceInfo copy() {
		ResourceInfo r = new ResourceInfo(id, type, width, height, sizeBytes, hash);
		r.bytes = bytes == null ? null : bytes.clone();
		r.canvas = canvas == null ? null : canvas.copy();
		return r;
	}

	public boolean contentEquals(ResourceInfo other) {
		if (id != other.id || type != other.type || width != other.width
				|| height != other.height || sizeBytes != other.sizeBytes || hash != other.hash)
			return false;
		if ((canvas == null) != (other.canvas == null))
			return false;
		if (canvas != null && !canvas.contentEquals(other.canvas))
			return false;
		// Bytes may legitimately differ (mirror pending vs server-held) — meta identity plus
		// hash is the cross-side contract; compare bytes only when both sides have them.
		if (bytes != null && other.bytes != null && !Arrays.equals(bytes, other.bytes))
			return false;
		return true;
	}
}
