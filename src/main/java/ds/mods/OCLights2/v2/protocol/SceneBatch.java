package ds.mods.OCLights2.v2.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * One sealed per-scene, per-tick delta batch: the unit of sync in protocol v2.
 * sceneId is the owning GPU's OC node address (persistent, chunk-reload-stable);
 * seq is a 32-bit per-scene sequence compared wraparound-safely; serverTick feeds the
 * client's interpolation clock.
 */
public final class SceneBatch {
	public final String sceneId;
	public final int seq;
	public final long serverTick;
	public final List<Delta> deltas;

	public SceneBatch(String sceneId, int seq, long serverTick, List<Delta> deltas) {
		this.sceneId = sceneId;
		this.seq = seq;
		this.serverTick = serverTick;
		this.deltas = new ArrayList<Delta>(deltas);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof SceneBatch))
			return false;
		SceneBatch b = (SceneBatch) o;
		return sceneId.equals(b.sceneId) && seq == b.seq && serverTick == b.serverTick
				&& deltas.equals(b.deltas);
	}

	@Override
	public int hashCode() {
		return sceneId.hashCode() * 31 + seq;
	}
}
