package opengpu.v2.protocol;

import java.util.ArrayList;
import java.util.List;

import opengpu.v2.scene.CanvasCommand;

/**
 * One scene mutation on the wire. Concrete types map 1:1 to {@link V2Wire} DELTA_* ids.
 * Deltas are immutable value objects; equals/hashCode support codec round-trip tests.
 */
public abstract class Delta {

	public abstract byte typeId();

	public static final class NodeCreate extends Delta {
		public final int nodeId;
		public final byte nodeType;
		public final int ref;

		public NodeCreate(int nodeId, byte nodeType, int ref) {
			this.nodeId = nodeId;
			this.nodeType = nodeType;
			this.ref = ref;
		}

		@Override
		public byte typeId() {
			return V2Wire.DELTA_NODE_CREATE;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof NodeCreate))
				return false;
			NodeCreate d = (NodeCreate) o;
			return nodeId == d.nodeId && nodeType == d.nodeType && ref == d.ref;
		}

		@Override
		public int hashCode() {
			return (nodeId * 31 + nodeType) * 31 + ref;
		}
	}

	public static final class NodeFree extends Delta {
		public final int nodeId;

		public NodeFree(int nodeId) {
			this.nodeId = nodeId;
		}

		@Override
		public byte typeId() {
			return V2Wire.DELTA_NODE_FREE;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof NodeFree && ((NodeFree) o).nodeId == nodeId;
		}

		@Override
		public int hashCode() {
			return nodeId;
		}
	}

	/**
	 * Property update; {@code mask} selects PROP_* fields, {@code values} carries them in
	 * ascending mask-bit order. Visible travels as 0/1; tint as the exact double
	 * representation of the unsigned 32-bit ARGB value (recovered with (int)(long)value).
	 */
	public static final class NodeProps extends Delta {
		public final int nodeId;
		public final int mask;
		public final double[] values;

		public NodeProps(int nodeId, int mask, double[] values) {
			this.nodeId = nodeId;
			this.mask = mask;
			this.values = values;
			if ((mask & ~V2Wire.KNOWN_PROPS_MASK) != 0)
				throw new IllegalArgumentException("Unknown prop mask bits in " + mask);
			if (Integer.bitCount(mask) != values.length)
				throw new IllegalArgumentException("mask bit count != value count");
		}

		@Override
		public byte typeId() {
			return V2Wire.DELTA_NODE_PROPS;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof NodeProps))
				return false;
			NodeProps d = (NodeProps) o;
			return nodeId == d.nodeId && mask == d.mask && java.util.Arrays.equals(values, d.values);
		}

		@Override
		public int hashCode() {
			return (nodeId * 31 + mask) * 31 + java.util.Arrays.hashCode(values);
		}
	}

	public static final class ResourceCreate extends Delta {
		public final int resId;
		public final byte resType;
		public final int width;
		public final int height;
		public final int sizeBytes;
		public final long hash;
		/** Canvas resources carry their command cap; 0 otherwise. */
		public final int commandCap;

		public ResourceCreate(int resId, byte resType, int width, int height, int sizeBytes, long hash, int commandCap) {
			this.resId = resId;
			this.resType = resType;
			this.width = width;
			this.height = height;
			this.sizeBytes = sizeBytes;
			this.hash = hash;
			this.commandCap = commandCap;
		}

		@Override
		public byte typeId() {
			return V2Wire.DELTA_RES_CREATE;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof ResourceCreate))
				return false;
			ResourceCreate d = (ResourceCreate) o;
			return resId == d.resId && resType == d.resType && width == d.width && height == d.height
					&& sizeBytes == d.sizeBytes && hash == d.hash && commandCap == d.commandCap;
		}

		@Override
		public int hashCode() {
			return ((resId * 31 + resType) * 31 + width) * 31 + height;
		}
	}

	public static final class ResourceFree extends Delta {
		public final int resId;

		public ResourceFree(int resId) {
			this.resId = resId;
		}

		@Override
		public byte typeId() {
			return V2Wire.DELTA_RES_FREE;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof ResourceFree && ((ResourceFree) o).resId == resId;
		}

		@Override
		public int hashCode() {
			return resId;
		}
	}

	public static final class CanvasPublish extends Delta {
		public final int resId;
		public final List<CanvasCommand> commands;

		public CanvasPublish(int resId, List<CanvasCommand> commands) {
			this.resId = resId;
			this.commands = new ArrayList<CanvasCommand>(commands);
		}

		@Override
		public byte typeId() {
			return V2Wire.DELTA_CANVAS_PUBLISH;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof CanvasPublish))
				return false;
			CanvasPublish d = (CanvasPublish) o;
			return resId == d.resId && commands.equals(d.commands);
		}

		@Override
		public int hashCode() {
			return resId * 31 + commands.hashCode();
		}
	}

	public static final class CanvasAppend extends Delta {
		public final int resId;
		public final List<CanvasCommand> commands;

		public CanvasAppend(int resId, List<CanvasCommand> commands) {
			this.resId = resId;
			this.commands = new ArrayList<CanvasCommand>(commands);
		}

		@Override
		public byte typeId() {
			return V2Wire.DELTA_CANVAS_APPEND;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof CanvasAppend))
				return false;
			CanvasAppend d = (CanvasAppend) o;
			return resId == d.resId && commands.equals(d.commands);
		}

		@Override
		public int hashCode() {
			return resId * 31 + commands.hashCode();
		}
	}

	/**
	 * A packed-RGBA region write into a texture (v3). Always carries its pixels: there is no
	 * "invalidate and refetch" form, because that is an amplifier rather than a throttle —
	 * it would cost sizeBytes per watcher per refresh with no bound. Back-pressure at the
	 * admission point is the throttle instead.
	 *
	 * {@code version} is the version this write PRODUCES, so an applier can assert
	 * {@code version == latestVersion + 1} and catch a missed write independently of the
	 * sequence-gap detector.
	 */
	public static final class TextureWrite extends Delta {
		public final int resId;
		public final int version;
		public final int x;
		public final int y;
		public final int w;
		public final int h;
		public final byte[] pixels;

		public TextureWrite(int resId, int version, int x, int y, int w, int h, byte[] pixels) {
			if (w < 1 || h < 1)
				throw new IllegalArgumentException("Texture write region must be at least 1x1");
			if (w > V2Wire.MAX_TEXTURE_DIM || h > V2Wire.MAX_TEXTURE_DIM)
				throw new IllegalArgumentException("Texture write region exceeds MAX_TEXTURE_DIM");
			if (x < 0 || y < 0)
				throw new IllegalArgumentException("Texture write origin must be non-negative");
			if (version < 1)
				throw new IllegalArgumentException("Texture write version must be >= 1");
			if (pixels == null)
				throw new IllegalArgumentException("Texture write needs pixels");
			// Long arithmetic: w*h*4 overflows int well inside the legal dimension range.
			long expected = (long) w * (long) h * 4L;
			if (pixels.length != expected)
				throw new IllegalArgumentException(
						"Texture write payload must be w*h*4 (" + expected + "), got " + pixels.length);
			if (expected > V2Wire.MAX_WRITE_REGION_BYTES)
				throw new IllegalArgumentException(
						"Texture write region too large (max " + V2Wire.MAX_WRITE_REGION_BYTES + " bytes)");
			this.resId = resId;
			this.version = version;
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			// Deltas are immutable value objects and the caller's array is a Lua-supplied
			// buffer we do not own.
			this.pixels = pixels.clone();
		}

		@Override
		public byte typeId() {
			return V2Wire.DELTA_TEX_WRITE;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof TextureWrite))
				return false;
			TextureWrite d = (TextureWrite) o;
			return resId == d.resId && version == d.version && x == d.x && y == d.y
					&& w == d.w && h == d.h && java.util.Arrays.equals(pixels, d.pixels);
		}

		@Override
		public int hashCode() {
			int hash = ((resId * 31 + version) * 31 + x) * 31 + y;
			hash = (hash * 31 + w) * 31 + h;
			return hash * 31 + java.util.Arrays.hashCode(pixels);
		}
	}

	/** Reserved scene-level state slot (post-chain order, scene uniforms — Stage D). */
	public static final class SceneProp extends Delta {
		public final int propId;
		public final byte[] payload;

		public SceneProp(int propId, byte[] payload) {
			this.propId = propId;
			this.payload = payload == null ? new byte[0] : payload;
		}

		@Override
		public byte typeId() {
			return V2Wire.DELTA_SCENE_PROP;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof SceneProp))
				return false;
			SceneProp d = (SceneProp) o;
			return propId == d.propId && java.util.Arrays.equals(payload, d.payload);
		}

		@Override
		public int hashCode() {
			return propId * 31 + java.util.Arrays.hashCode(payload);
		}
	}
}
