package ds.mods.OCLights2.v2.scene;

import ds.mods.OCLights2.v2.protocol.Delta;
import ds.mods.OCLights2.v2.protocol.V2Wire;

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
			ResourceInfo res = new ResourceInfo(d.resId, d.resType, d.width, d.height, d.sizeBytes, d.hash);
			if (d.resType == V2Wire.RES_CANVAS) {
				res.canvas = new SceneCanvas(d.width, d.height, d.commandCap);
			}
			state.resources.put(d.resId, res);
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
