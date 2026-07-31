package ds.mods.OCLights2.v2.scene;

/**
 * A full-state snapshot stamped with the sequence number it represents — the resync unit.
 * The stamp is what makes the ordering rules sound: a mirror that requested a snapshot
 * discards batches with seq <= this seq and resumes applying at seq + 1.
 */
public final class SceneSnapshot {
	public final String sceneId;
	public final int seq;
	public final long serverTick;
	public final SceneState state;

	public SceneSnapshot(String sceneId, int seq, long serverTick, SceneState state) {
		this.sceneId = sceneId;
		this.seq = seq;
		this.serverTick = serverTick;
		this.state = state;
	}
}
