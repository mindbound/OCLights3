package ds.mods.OCLights2.v2.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * One sealed per-scene, per-tick delta batch: the unit of sync in protocol v2.
 * sceneId is the owning GPU's OC node address (persistent, chunk-reload-stable);
 * epoch is the scene's incarnation stamp (nonzero, minted at creation, restored with
 * persistence — mirrors hard-reset on mismatch, closing the sceneId-reuse-at-regressed-seq
 * hole); seq is a 32-bit per-scene sequence compared wraparound-safely; serverTick feeds
 * the client's interpolation clock.
 */
public final class SceneBatch {
	public final String sceneId;
	public final int epoch;
	public final int seq;
	public final long serverTick;
	public final List<Delta> deltas;

	public SceneBatch(String sceneId, int epoch, int seq, long serverTick, List<Delta> deltas) {
		this.sceneId = sceneId;
		this.epoch = epoch;
		this.seq = seq;
		this.serverTick = serverTick;
		this.deltas = new ArrayList<Delta>(deltas);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof SceneBatch))
			return false;
		SceneBatch b = (SceneBatch) o;
		return sceneId.equals(b.sceneId) && epoch == b.epoch && seq == b.seq
				&& serverTick == b.serverTick && deltas.equals(b.deltas);
	}

	@Override
	public int hashCode() {
		return sceneId.hashCode() * 31 + seq;
	}
}
