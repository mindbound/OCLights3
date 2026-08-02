package opengpu.v2.scene;

import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.V2Wire;

/**
 * The single delta-application code path. ServerScene applies its own staged deltas through
 * this class and mirrors apply decoded deltas through it — convergence by construction:
 * there is no second implementation to drift.
 *
 * Throws IllegalStateException on references to unknown ids or type mismatches; mirrors
 * treat any throw as a resync trigger, the server treats it as a validation bug (its public
 * API validates before staging).
 */
public final class DeltaApplier {
	private DeltaApplier() {}

	public static void apply(SceneState state, Delta delta) {
		if (delta instanceof Delta.NodeCreate) {
			Delta.NodeCreate d = (Delta.NodeCreate) delta;
			if (!V2Wire.isKnownNodeType(d.nodeType))
				throw new IllegalStateException("Unknown node type " + d.nodeType);
			if (state.nodes.containsKey(d.nodeId))
				throw new IllegalStateException("Node " + d.nodeId + " already exists");
			if (d.ref != 0 && !state.resources.containsKey(d.ref))
				throw new IllegalStateException("Node " + d.nodeId + " references unknown resource " + d.ref);
			state.nodes.put(d.nodeId, new SceneNode(d.nodeId, d.nodeType, d.ref));
		} else if (delta instanceof Delta.NodeFree) {
			Delta.NodeFree d = (Delta.NodeFree) delta;
			if (state.nodes.remove(d.nodeId) == null)
				throw new IllegalStateException("Freeing unknown node " + d.nodeId);
		} else if (delta instanceof Delta.NodeProps) {
			Delta.NodeProps d = (Delta.NodeProps) delta;
			SceneNode node = state.nodes.get(d.nodeId);
			if (node == null)
				throw new IllegalStateException("Props for unknown node " + d.nodeId);
			int vi = 0;
			if ((d.mask & V2Wire.PROP_X) != 0)
				node.x = d.values[vi++];
			if ((d.mask & V2Wire.PROP_Y) != 0)
				node.y = d.values[vi++];
			if ((d.mask & V2Wire.PROP_ROT) != 0)
				node.rot = d.values[vi++];
			if ((d.mask & V2Wire.PROP_SX) != 0)
				node.sx = d.values[vi++];
			if ((d.mask & V2Wire.PROP_SY) != 0)
				node.sy = d.values[vi++];
			if ((d.mask & V2Wire.PROP_Z) != 0)
				node.z = (int) d.values[vi++];
			if ((d.mask & V2Wire.PROP_VISIBLE) != 0)
				node.visible = d.values[vi++] != 0;
			if ((d.mask & V2Wire.PROP_TINT) != 0)
				node.tint = (int) (long) (double) d.values[vi++];
		} else if (delta instanceof Delta.ResourceCreate) {
			Delta.ResourceCreate d = (Delta.ResourceCreate) delta;
			if (!V2Wire.isKnownResType(d.resType))
				throw new IllegalStateException("Unknown resource type " + d.resType);
			if (d.width <= 0 || d.height <= 0
					|| d.width > V2Wire.MAX_TEXTURE_DIM || d.height > V2Wire.MAX_TEXTURE_DIM)
				throw new IllegalStateException("Resource " + d.resId + " has invalid dimensions "
						+ d.width + "x" + d.height);
			if (d.resType == V2Wire.RES_TEXTURE
					&& d.sizeBytes != (long) d.width * d.height * 4L)
				throw new IllegalStateException("Resource " + d.resId + " size does not match dimensions");
			if (state.resources.containsKey(d.resId))
				throw new IllegalStateException("Resource " + d.resId + " already exists");
			ResourceInfo res = new ResourceInfo(d.resId, d.resType, d.width, d.height, d.sizeBytes);
			// A create is always version 1; the wire's hash field is that version's content
			// hash, which doubles as the client's content-addressed cache key.
			res.version = 0; // no bytes held yet on a mirror; the server sets 1 when it attaches
			res.latestVersion = 1;
			res.knownHash = d.hash;
			res.knownHashVersion = 1;
			if (d.resType == V2Wire.RES_CANVAS) {
				res.canvas = new SceneCanvas(d.width, d.height, d.commandCap);
			}
			state.resources.put(d.resId, res);
		} else if (delta instanceof Delta.TextureWrite) {
			Delta.TextureWrite d = (Delta.TextureWrite) delta;
			ResourceInfo res = state.resources.get(d.resId);
			if (res == null)
				throw new IllegalStateException("Texture write references unknown resource " + d.resId);
			if (res.type != V2Wire.RES_TEXTURE)
				throw new IllegalStateException("Texture write targets non-texture resource " + d.resId);
			// The rect must lie wholly inside the texture. The decoder cannot check this (it
			// holds no scene state), so this is the only place it can be enforced — and a
			// violation on a mirror is a divergence signal, i.e. a resync trigger.
			// LONG arithmetic: x and w are independently bounded but their sum is not, and
			// OC's checkInteger SATURATES out-of-range Lua numbers to Integer.MAX_VALUE
			// rather than rejecting them — so an int sum here wraps negative, passes the
			// check, and either throws mid-blit or writes 16 KiB at an address the caller
			// never named (which every mirror reproduces identically, so no detector fires).
			if ((long) d.x + d.w > res.width || (long) d.y + d.h > res.height)
				throw new IllegalStateException("Texture write region out of bounds for resource " + d.resId);
			// Independent divergence detector: versions advance by exactly one per write, so
			// a missed write is caught even if the sequence numbers looked continuous.
			if (d.version != res.latestVersion + 1)
				throw new IllegalStateException("Texture write version gap on resource " + d.resId
						+ ": expected " + (res.latestVersion + 1) + ", got " + d.version);
			// NO MUTATION ABOVE THIS LINE. Advancing latestVersion before the blit is what
			// turns any blit failure into a permanent freeze: version != latestVersion
			// forever, so every later write is silently skipped and no body is ever
			// acceptable again.
			// ASYMMETRY, deliberate: a mirror that does not hold this texture's bytes still
			// tracks latestVersion (so it knows it is behind and can fetch), but has nothing
			// to blit into. Only a side holding the previous version's bytes can apply the
			// pixels — and then all-or-nothing, since every bound is validated above.
			if (res.bytes != null && res.version == d.version - 1) {
				for (int row = 0; row < d.h; row++) {
					int srcOff = row * d.w * 4;
					int dstOff = ((d.y + row) * res.width + d.x) * 4;
					System.arraycopy(d.pixels, srcOff, res.bytes, dstOff, d.w * 4);
				}
				res.version = d.version;
				res.unionDirtyRect(d.x, d.y, d.w, d.h);
			}
			res.latestVersion = d.version;
		} else if (delta instanceof Delta.ResourceFree) {
			// Freeing a resource that nodes or recorded commands still reference is legal;
			// dangling references render the pending-placeholder (same as an untransferred
			// texture body). Convergence is unaffected — both sides dangle identically.
			Delta.ResourceFree d = (Delta.ResourceFree) delta;
			if (state.resources.remove(d.resId) == null)
				throw new IllegalStateException("Freeing unknown resource " + d.resId);
		} else if (delta instanceof Delta.CanvasPublish) {
			Delta.CanvasPublish d = (Delta.CanvasPublish) delta;
			validateEmbeddedRefs(state, d.commands);
			canvasOf(state, d.resId).publish(d.commands);
		} else if (delta instanceof Delta.CanvasAppend) {
			Delta.CanvasAppend d = (Delta.CanvasAppend) delta;
			validateEmbeddedRefs(state, d.commands);
			canvasOf(state, d.resId).append(d.commands);
		} else if (delta instanceof Delta.SceneProp) {
			// Reserved (Stage D); carrying it is legal, applying it is a no-op for now.
		} else {
			throw new IllegalStateException("Unknown delta " + delta.getClass());
		}
	}

	/**
	 * The doc's "any unknown-id reference" rule extends to resource ids embedded in draw
	 * commands: a DRAW_TEXTURE(_SUB) recorded against a never-created id is rejected at
	 * apply time on both sides (server: Lua error; mirror: resync trigger). Ids valid at
	 * record time may later dangle via ResourceFree — see the free note above.
	 */
	private static void validateEmbeddedRefs(SceneState state, java.util.List<CanvasCommand> commands) {
		for (CanvasCommand cmd : commands) {
			if (cmd.op == V2Wire.OP_DRAW_TEXTURE || cmd.op == V2Wire.OP_DRAW_TEXTURE_SUB) {
				int ref = (int) cmd.args[0];
				if (!state.resources.containsKey(ref))
					throw new IllegalStateException("Draw command references unknown resource " + ref);
			}
		}
	}

	private static SceneCanvas canvasOf(SceneState state, int resId) {
		ResourceInfo res = state.resources.get(resId);
		if (res == null)
			throw new IllegalStateException("Canvas op on unknown resource " + resId);
		if (res.canvas == null)
			throw new IllegalStateException("Canvas op on non-canvas resource " + resId);
		return res.canvas;
	}
}
