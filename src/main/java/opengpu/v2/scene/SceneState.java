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
