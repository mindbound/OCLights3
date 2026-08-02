package opengpu.v2.scene;

import java.util.Map;
import java.util.TreeMap;

/**
 * The shared scene data model: resources + nodes + id counters. Used verbatim on both sides
 * — the server's authoritative copy and every client mirror hold a SceneState; only the
 * wrappers differ ({@link ServerScene} stages deltas, {@link SceneMirror} applies batches).
 *
 * Id counters are part of the state and persist with it: recorded canvas command lists and
 * node refs reference these ids, so a reload must never reallocate them.
 *
 * TreeMaps keep iteration deterministic (state comparison, future NBT round-trips).
 */
public final class SceneState {
	public final TreeMap<Integer, ResourceInfo> resources = new TreeMap<Integer, ResourceInfo>();
	public final TreeMap<Integer, SceneNode> nodes = new TreeMap<Integer, SceneNode>();
	public int nextResourceId = 1;
	public int nextNodeId = 1;

	/** Structure only: no texture bytes cloned. For snapshots, which strip them anyway. */
	public SceneState copyStructure() {
		SceneState s = new SceneState();
		for (Map.Entry<Integer, ResourceInfo> e : resources.entrySet()) {
			ResourceInfo res = e.getValue().copyStructure();
			// Canvases ARE their content, so they must still be deep-copied.
			res.canvas = e.getValue().canvas == null ? null : e.getValue().canvas.copy();
			s.resources.put(e.getKey(), res);
		}
		for (Map.Entry<Integer, SceneNode> e : nodes.entrySet()) {
			s.nodes.put(e.getKey(), e.getValue().copy());
		}
		s.nextResourceId = nextResourceId;
		s.nextNodeId = nextNodeId;
		return s;
	}

	/**
	 * The canvas that defines this scene's LOGICAL SIZE: the lowest-id canvas node's canvas.
	 *
	 * This is an INVARIANT, not an implementation detail, and it is stated here because it
	 * used to exist only as a comment on the client's private size lookup. The display
	 * canvas is created first (TileEntityGpu2.ensureImplicitCanvas) and node ids are handed
	 * out monotonically (ServerScene.createNode), so every later canvas — the offscreen
	 * canvases DESIGN-RENDERER-V2 specifies — sorts after it and cannot displace it.
	 *
	 * Anything that gives the display canvas a NEW node id breaks this silently: both sides
	 * would agree on the state, so there is no sequence gap, no apply failure and no log
	 * line — just an FBO allocated at some offscreen canvas's size. That is the constraint
	 * on how a resolution change may be implemented: keep the display node's id, or replace
	 * this rule with an explicit marker first.
	 *
	 * And a node's {@code ref} is IMMUTABLE — there is no NodeSetRef delta — so "keep the
	 * node, repoint it at a new canvas" is not available without a protocol change. The one
	 * id-preserving path the current delta set allows is recreating the canvas RESOURCE
	 * under its existing id, which leaves the node untouched. See DisplayCanvasTest.
	 *
	 * Returns null when no canvas node exists yet.
	 */
	public ResourceInfo displayCanvas() {
		for (SceneNode node : nodes.values()) {
			if (node.type == opengpu.v2.protocol.V2Wire.NODE_CANVAS) {
				ResourceInfo res = resources.get(node.ref);
				if (res != null && res.type == opengpu.v2.protocol.V2Wire.RES_CANVAS) {
					return res;
				}
			}
		}
		return null;
	}

	public SceneState copy() {
		SceneState s = new SceneState();
		for (Map.Entry<Integer, ResourceInfo> e : resources.entrySet()) {
			s.resources.put(e.getKey(), e.getValue().copy());
		}
		for (Map.Entry<Integer, SceneNode> e : nodes.entrySet()) {
			s.nodes.put(e.getKey(), e.getValue().copy());
		}
		s.nextResourceId = nextResourceId;
		s.nextNodeId = nextNodeId;
		return s;
	}

	/**
	 * Deep content comparison of resources and nodes (counters excluded — mirrors never
	 * allocate). Used by the convergence tests: server state and mirror state must agree
	 * after any batch sequence.
	 */
	public boolean contentEquals(SceneState other) {
		if (resources.size() != other.resources.size() || nodes.size() != other.nodes.size())
			return false;
		for (Map.Entry<Integer, ResourceInfo> e : resources.entrySet()) {
			ResourceInfo o = other.resources.get(e.getKey());
			if (o == null || !e.getValue().contentEquals(o))
				return false;
		}
		for (Map.Entry<Integer, SceneNode> e : nodes.entrySet()) {
			SceneNode o = other.nodes.get(e.getKey());
			if (o == null || !e.getValue().contentEquals(o))
				return false;
		}
		return true;
	}
}
