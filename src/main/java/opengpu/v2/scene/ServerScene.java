package opengpu.v2.scene;

import java.util.ArrayList;
import java.util.List;

import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;

/**
 * The authoritative server-side scene. Public mutators validate, build a delta, apply it to
 * the local state through {@link DeltaApplier} (the same code path mirrors use), and stage
 * it. {@link #sealBatch()} closes the tick's batch with the next sequence number.
 *
 * Thread contract: all access under the owner's scene lock (the component layer's job);
 * this class itself is single-threaded on purpose.
 *
 * Texture bytes are server-held state; the ResourceCreate delta carries only the metadata
 * (id/type/dims/size/hash) — body transfer to clients is a separate, later concern.
 */
public final class ServerScene {
	public final String sceneId;
	/** Incarnation stamp: nonzero, minted at creation, restored with persistence. */
	private final int epoch;
	private final SceneState state;
	private int seq;
	private long currentTick;
	/** Admission allowance consumed this TICK; reset on a tick-value change. */
	private int tickWriteBytes;
	/**
	 * Texture-write payload staged into the CURRENT UNSEALED BATCH; reset at seal.
	 *
	 * Deliberately separate from {@link #tickWriteBytes}: the two coincide in the common
	 * path but diverge whenever a tick boundary passes without a seal, or a seal happens
	 * without a tick change (saveBoundary does exactly that). One counter cannot bound both,
	 * and it is the BATCH bound that the decoder enforces — so conflating them produces
	 * batches the receiver must reject.
	 */
	private int stagedWriteBytes;
	private int writeBudgetBytes = V2Wire.MAX_WRITE_BYTES_PER_TICK;
	private int manifestGen;
	private final ArrayList<Delta> staged = new ArrayList<Delta>();

	public ServerScene(String sceneId) {
		this(sceneId, 0);
	}

	/** initialSeq is exposed for wraparound testing; a fresh epoch is minted. */
	public ServerScene(String sceneId, int initialSeq) {
		this(sceneId, initialSeq, mintEpoch(), new SceneState());
	}

	/** Persistence-restore constructor: same incarnation continues (epoch must be nonzero). */
	public ServerScene(String sceneId, int initialSeq, int epoch, SceneState state) {
		if (epoch == 0)
			throw new IllegalArgumentException("Epoch must be nonzero");
		this.sceneId = sceneId;
		this.epoch = epoch;
		this.state = state;
		this.seq = initialSeq;
	}

	/** Public: a divergent restore (degraded bodies) mints a fresh incarnation too. */
	public static int mintEpoch() {
		java.util.Random random = new java.util.Random();
		int epoch;
		do {
			epoch = random.nextInt();
		} while (epoch == 0); // 0 is the mirrors' "no epoch adopted yet" sentinel
		return epoch;
	}

	public int epoch() {
		return epoch;
	}

	public SceneState state() {
		return state;
	}

	public int currentSeq() {
		return seq;
	}

	/** True while mutations are staged for the current tick's (unsealed) batch. */
	public boolean hasStagedDeltas() {
		return !staged.isEmpty();
	}

	public void setCurrentTick(long tick) {
		beginTick(tick, V2Wire.MAX_WRITE_BYTES_PER_TICK);
	}

	/**
	 * Advance to a tick and grant that tick's texture-write allowance. The allowance resets
	 * only on a CHANGE of tick value: a mid-tick save boundary seals a batch but must not
	 * hand out a second allowance, or the per-tick cap becomes per-seal.
	 */
	public void beginTick(long tick, int writeBudgetBytes) {
		if (tick != currentTick) {
			currentTick = tick;
			tickWriteBytes = 0;
		}
		this.writeBudgetBytes = Math.max(0, writeBudgetBytes);
	}

	/**
	 * Bumped whenever a resource's advertised hash changes without a sequence advance (a body
	 * serve or a save computes knownHash lazily). The snapshot envelope cache keys on it, so
	 * a cached snapshot cannot keep advertising a stale cache hint.
	 */
	public int manifestGen() {
		return manifestGen;
	}

	public void bumpManifestGen() {
		manifestGen++;
	}

	private void applyAndStage(Delta delta) {
		DeltaApplier.apply(state, delta);
		staged.add(delta);
	}

	public int createCanvas(int width, int height, int commandCap) {
		validateDimensions(width, height);
		if (commandCap <= 0 || commandCap > V2Wire.MAX_COMMANDS - 2)
			throw new IllegalArgumentException("Command cap out of range: " + commandCap);
		int id = allocateResourceId();
		applyAndStage(new Delta.ResourceCreate(id, V2Wire.RES_CANVAS, width, height, 0, 0, commandCap));
		return id;
	}

	public int createTexture(int width, int height, byte[] bytes) {
		validateDimensions(width, height);
		if (bytes == null)
			throw new IllegalArgumentException("Texture bytes required server-side");
		// Long arithmetic: w*h*4 in int silently wraps at 32768x32768, defeating both this
		// check and any byte-budget charge computed from the declared size.
		if (bytes.length != (long) width * height * 4L)
			throw new IllegalArgumentException("Texture byte length must be w*h*4");
		int id = allocateResourceId();
		applyAndStage(new Delta.ResourceCreate(id, V2Wire.RES_TEXTURE, width, height,
				bytes.length, V2Wire.contentHash(bytes), 0));
		// Clone: an aliased caller buffer mutated later would desync the server's own state
		// from the version the mirrors were told about.
		ResourceInfo res = state.resources.get(id);
		res.bytes = bytes.clone();
		res.version = 1; // the server always holds the latest version's content
		return id;
	}

	/**
	 * Write packed RGBA pixels into a texture region. The pixels always travel with the
	 * delta — there is no invalidate-and-refetch form, because that costs sizeBytes per
	 * watcher per refresh with no bound.
	 *
	 * Admission is a hard per-tick byte allowance. The caller is expected to translate
	 * refusal into back-pressure (OC's LimitReachedException → next-tick replay), never into
	 * a degraded path.
	 *
	 * @throws IllegalStateException if the per-tick allowance is exhausted
	 */
	public void writeRegion(int resId, int x, int y, int w, int h, byte[] data) {
		ResourceInfo res = state.resources.get(resId);
		if (res == null)
			throw new IllegalStateException("Unknown resource " + resId);
		if (res.type != V2Wire.RES_TEXTURE)
			throw new IllegalStateException("Resource " + resId + " is not a texture");
		if (w < 1 || h < 1)
			throw new IllegalArgumentException("Region must be at least 1x1");
		// Long arithmetic: OC saturates out-of-range Lua integers to Integer.MAX_VALUE, so
		// an int sum wraps negative and slips past this check.
		if (x < 0 || y < 0 || (long) x + w > res.width || (long) y + h > res.height)
			throw new IllegalArgumentException("Region out of bounds");
		long len = (long) w * (long) h * 4L;
		if (data == null || data.length != len)
			throw new IllegalArgumentException("Data length must be w*h*4 (" + len + ")");
		if (len > V2Wire.MAX_WRITE_REGION_BYTES)
			throw new IllegalArgumentException(
					"Region too large (max " + V2Wire.MAX_WRITE_REGION_BYTES + " bytes per call)");
		if (res.latestVersion == Integer.MAX_VALUE)
			throw new IllegalStateException("Texture version space exhausted; free and recreate it");
		if (tickWriteBytes + len > writeBudgetBytes)
			throw new IllegalStateException("Per-tick texture write allowance exhausted");
		// The batch bound is what the decoder checks, so it must hold independently of the
		// tick allowance — a batch spanning a tick boundary would otherwise be rejected by
		// every receiver, costing the whole frame and a resync.
		if (stagedWriteBytes + len > V2Wire.MAX_WRITE_BYTES_PER_TICK)
			throw new IllegalStateException("Batch texture write payload exhausted");
		tickWriteBytes += (int) len;
		stagedWriteBytes += (int) len;
		applyAndStage(new Delta.TextureWrite(resId, res.latestVersion + 1, x, y, w, h, data));
	}

	/** Bytes of texture-write payload still admissible this tick. */
	public int writeBudgetRemaining() {
		// The tighter of the two bounds: a caller pacing itself by this number must never be
		// refused by the other one.
		return Math.max(0, Math.min(writeBudgetBytes - tickWriteBytes,
				V2Wire.MAX_WRITE_BYTES_PER_TICK - stagedWriteBytes));
	}

	private static void validateDimensions(int width, int height) {
		if (width <= 0 || height <= 0
				|| width > V2Wire.MAX_TEXTURE_DIM || height > V2Wire.MAX_TEXTURE_DIM)
			throw new IllegalArgumentException("Dimensions out of range: " + width + "x" + height);
	}

	private int allocateResourceId() {
		if (state.nextResourceId == Integer.MAX_VALUE)
			throw new IllegalStateException("Scene resource id space exhausted; recreate the scene");
		return state.nextResourceId++;
	}

	public void freeResource(int resId) {
		if (!state.resources.containsKey(resId))
			throw new IllegalStateException("Freeing unknown resource " + resId);
		applyAndStage(new Delta.ResourceFree(resId));
	}

	public int createNode(byte nodeType, int ref) {
		if (ref != 0 && !state.resources.containsKey(ref))
			throw new IllegalStateException("Node references unknown resource " + ref);
		if (state.nextNodeId == Integer.MAX_VALUE)
			throw new IllegalStateException("Scene node id space exhausted; recreate the scene");
		int id = state.nextNodeId++;
		applyAndStage(new Delta.NodeCreate(id, nodeType, ref));
		return id;
	}

	public void freeNode(int nodeId) {
		if (!state.nodes.containsKey(nodeId))
			throw new IllegalStateException("Freeing unknown node " + nodeId);
		applyAndStage(new Delta.NodeFree(nodeId));
	}

	public void setTransform(int nodeId, double x, double y, double rot, double sx, double sy) {
		requireNode(nodeId);
		int mask = V2Wire.PROP_X | V2Wire.PROP_Y | V2Wire.PROP_ROT | V2Wire.PROP_SX | V2Wire.PROP_SY;
		applyAndStage(new Delta.NodeProps(nodeId, mask, new double[] { x, y, rot, sx, sy }));
	}

	public void setZ(int nodeId, int z) {
		requireNode(nodeId);
		applyAndStage(new Delta.NodeProps(nodeId, V2Wire.PROP_Z, new double[] { z }));
	}

	public void setVisible(int nodeId, boolean visible) {
		requireNode(nodeId);
		applyAndStage(new Delta.NodeProps(nodeId, V2Wire.PROP_VISIBLE, new double[] { visible ? 1 : 0 }));
	}

	public void setTint(int nodeId, int argb) {
		requireNode(nodeId);
		applyAndStage(new Delta.NodeProps(nodeId, V2Wire.PROP_TINT,
				new double[] { (double) (argb & 0xFFFFFFFFL) }));
	}

	public void canvasAppend(int resId, List<CanvasCommand> commands) {
		applyAndStage(new Delta.CanvasAppend(resId, commands));
	}

	public void canvasPublish(int resId, List<CanvasCommand> commands) {
		applyAndStage(new Delta.CanvasPublish(resId, commands));
	}

	private void requireNode(int nodeId) {
		if (!state.nodes.containsKey(nodeId))
			throw new IllegalStateException("Unknown node " + nodeId);
	}

	/** Seals and returns the tick's batch, or null when nothing was staged. */
	public SceneBatch sealBatch() {
		if (staged.isEmpty())
			return null;
		if (staged.size() > V2Wire.MAX_DELTAS)
			throw new IllegalStateException("Staged delta count exceeds wire cap: " + staged.size());
		seq++;
		SceneBatch batch = new SceneBatch(sceneId, epoch, seq, currentTick, staged);
		staged.clear();
		stagedWriteBytes = 0;
		return batch;
	}

	/**
	 * Sync snapshot: deep-copied state stamped with the current sequence number and tick,
	 * with texture bytes STRIPPED per the manifest-only snapshot contract (clients request
	 * bodies they lack; stripped textures arrive in the pending state). Snapshots are
	 * batch-boundary artifacts: taking one with staged-but-unsealed deltas would stamp
	 * state from batch N+1 with seq N, so it is refused. Call under the scene lock; the
	 * returned copy may be handed to any thread. Persistence DELIBERATELY rides this method
	 * (one codec, two duties — same batch-boundary and byte-stripping rules); bodies go to
	 * the ResourceStore separately.
	 */
	public SceneSnapshot snapshot() {
		if (!staged.isEmpty())
			throw new IllegalStateException("Seal the pending batch before snapshotting");
		// copyStructure, not copy: snapshots strip texture bytes anyway, so a deep copy would
		// clone every texture's megabytes purely to drop them on the next line — real waste
		// that streaming makes worse by taking snapshots more often.
		return new SceneSnapshot(sceneId, epoch, seq, currentTick, state.copyStructure());
	}
}
