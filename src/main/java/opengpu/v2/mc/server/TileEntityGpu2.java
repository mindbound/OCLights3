package opengpu.v2.mc.server;

import java.util.ArrayList;
import java.util.List;

import li.cil.oc.api.Network;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import opengpu.OpenGPU;
import opengpu.v2.mc.FontMetrics;
import opengpu.v2.persist.DirectoryResourceStore;
import opengpu.v2.persist.ScenePersistence;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneCanvas;
import opengpu.v2.scene.ServerScene;
import opengpu.v2.sync.SceneHost;

/**
 * The v2 GPU: an OC component whose Lua surface records Canvas2D commands into a
 * server-authoritative {@link ServerScene}, synced to clients by a {@link SceneHost}.
 * No server-side rasterization exists — mutation is cheap scene-state work under
 * {@link #sceneLock} (the design's by-construction answer to legacy T-01).
 *
 * Lifecycle (the GTNH OC pattern): node created in the constructor, joined on the first
 * server tick; scene id = the node address, so the scene is restored (or created) only once
 * the address is known. node.remove() in both invalidate() and onChunkUnload(); deliberate
 * block break additionally destroys the scene (SCENE_GONE + store delete).
 *
 * Thread contract: OC direct callbacks run on machine executor threads; every touch of
 * scene/recording state is synchronized on {@link #sceneLock}. The runtime's per-tick pump
 * and NBT persistence take the same lock on the server thread.
 */
public class TileEntityGpu2 extends TileEntity implements Environment {
	public static final int DEFAULT_WIDTH = 512;
	public static final int DEFAULT_HEIGHT = 288;
	public static final int CANVAS_COMMAND_CAP = 4096;
	public static final int PUSH_DEPTH_CAP = 16;
	/** Server-side VRAM budget in bytes (textures w*h*4 + canvas command capacity estimate). */
	public static final long VRAM_BUDGET_BYTES = 16L * 1024 * 1024;
	/** Budget estimate per canvas command slot (id + args worst case, serialized). */
	public static final int CANVAS_SLOT_COST = 32;

	protected final Object sceneLock = new Object();
	protected Node node;
	private boolean addedToNetwork;

	// Server-side scene state (null until the first server tick resolves the node address).
	private ServerScene scene;
	private SceneHost host;
	private int implicitCanvasRes;
	private int implicitCanvasNode;

	// Persisted structure carried between readFromNBT and first-tick restore.
	private byte[] pendingStructure;
	private boolean pendingSpilled;

	// Canvas recording state (guarded by sceneLock).
	private final List<CanvasCommand> recording = new ArrayList<CanvasCommand>();
	private List<CanvasCommand> pendingPresent;
	private boolean autopresent = true;
	private int pushDepth;
	/** Push depth left unbalanced by the last present()ed frame; restored if append re-arms. */
	private int publishedTailDepth;
	/** Texture ids freed since the last save, so their stored bodies can be deleted. */
	private final java.util.Set<Integer> freedSinceSave = new java.util.HashSet<Integer>();
	private int colR = 255, colG = 255, colB = 255, colA = 255;

	// Client-side mirror of the scene identity, from the description packet.
	private String clientSceneId;

	/** Address of the bound screen (persisted); resolved to a live TE each policy tick. */
	private String boundScreenAddress;
	private TileEntityScreen2 boundScreen;
	/**
	 * True once Lua has made an explicit binding decision (bind or unbind). Auto-bind is a
	 * convenience for the un-configured build, so it keeps scanning until it succeeds or
	 * until an explicit call settles the question — a scan that finds nothing must NOT
	 * consume it, or a GPU placed before its screen can never auto-bind again.
	 */
	private boolean bindingIsExplicit;

	/**
	 * Set by any scene mutation, consumed once per tick in {@link #serverPump} to call
	 * markDirty(). Callbacks must never touch the world directly — they run on OC executor
	 * threads; OC solves this the same way (mutator -> markChanged -> deferred to the tick).
	 */
	private boolean chunkDirty;

	/**
	 * Interim component name. The legacy TE still registers "ocl_gpu" with an incompatible
	 * API, and two components sharing a name on one network is an ambiguity Lua cannot
	 * resolve; this becomes "ocl_gpu" at the Stage A cut-over when the legacy block set is
	 * deleted. Component names are not save-persisted (only the node address is, under
	 * oc:node), so the eventual rename is world-compatible.
	 */
	public static final String COMPONENT_NAME = "opengpu";

	public TileEntityGpu2() {
		node = Network.newNode(this, Visibility.Network).withComponent(COMPONENT_NAME).create();
	}

	// ------------------------------------------------------------------
	// Identity

	public String sceneId() {
		return scene != null ? scene.sceneId : null;
	}

	public String clientSceneId() {
		return clientSceneId;
	}

	// ------------------------------------------------------------------
	// MC lifecycle

	@Override
	public void updateEntity() {
		if (worldObj.isRemote) {
			return;
		}
		if (!addedToNetwork) {
			addedToNetwork = true;
			Network.joinOrCreateNetwork(this);
		}
		if (scene == null && node != null && node.address() != null) {
			initScene();
		}
	}

	private void initScene() {
		String address = node.address();
		DirectoryResourceStore store = V2ServerRuntime.get().store();
		byte[] structure;
		try {
			structure = ScenePersistence.resolveStructure(address,
					pendingSpilled ? null : pendingStructure, store);
		} catch (RuntimeException e) {
			OpenGPU.logger.warn("GPU " + address + ": could not resolve persisted scene structure", e);
			structure = null;
		}
		pendingStructure = null;
		pendingSpilled = false;
		ScenePersistence.RestoreResult result = ScenePersistence.restoreOrFresh(address, structure, store);
		for (String warning : result.warnings) {
			OpenGPU.logger.warn("GPU " + address + ": " + warning);
		}
		ServerScene restored = result.scene;
		if (!address.equals(restored.sceneId)) {
			// The scene id IS the node address; a mismatch means the node was re-addressed
			// (OC address collision on load) or the TE NBT was duplicated by a schematic.
			// Re-key under the live address with a fresh epoch so mirrors hard-reset rather
			// than blending two incarnations, and never register under a foreign id.
			OpenGPU.logger.warn("GPU " + address + ": persisted scene id " + restored.sceneId
					+ " does not match the node address; re-keying with a fresh epoch");
			restored = new ServerScene(address, restored.currentSeq(), ServerScene.mintEpoch(),
					restored.state());
		}
		synchronized (sceneLock) {
			scene = restored;
			ensureImplicitCanvas();
			host = new SceneHost(scene, V2ServerRuntime.get().transport(),
					V2ServerRuntime.HEARTBEAT_INTERVAL_TICKS,
					V2ServerRuntime.SNAPSHOT_MIN_INTERVAL_TICKS,
					V2ServerRuntime.BODIES_PER_WATCHER_PER_TICK);
		}
		V2ServerRuntime.get().register(this);
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
	}

	/** The implicit legacy-compat canvas: created fresh, or re-validated after a restore. */
	private void ensureImplicitCanvas() {
		if (implicitCanvasRes != 0) {
			ResourceInfo res = scene.state().resources.get(implicitCanvasRes);
			if (res != null && res.type == V2Wire.RES_CANVAS
					&& scene.state().nodes.containsKey(implicitCanvasNode)) {
				return;
			}
			OpenGPU.logger.warn("GPU " + scene.sceneId
					+ ": persisted implicit canvas ids are stale; creating a fresh canvas");
		}
		implicitCanvasRes = scene.createCanvas(DEFAULT_WIDTH, DEFAULT_HEIGHT, CANVAS_COMMAND_CAP);
		implicitCanvasNode = scene.createNode(V2Wire.NODE_CANVAS, implicitCanvasRes);
	}

	@Override
	public void invalidate() {
		super.invalidate();
		onUnload();
	}

	@Override
	public void onChunkUnload() {
		super.onChunkUnload();
		onUnload();
	}

	private void onUnload() {
		if (node != null) {
			node.remove();
		}
		if (worldObj != null && !worldObj.isRemote) {
			V2ServerRuntime.get().unregister(this);
		}
	}

	/**
	 * Deliberate block break (called by the block, server-side, before TE removal):
	 * the scene dies with the block — watchers get SCENE_GONE, stored bytes are deleted.
	 */
	public void onBlockDestroyed() {
		synchronized (sceneLock) {
			// Hand the screen back before this GPU disappears, so it is immediately
			// bindable by another one and stops advertising a scene that is being deleted.
			releaseBoundScreenLocked(null);
			boundScreen = null;
			boundScreenAddress = null;
			if (host != null) {
				host.destroy();
			}
			if (scene != null) {
				V2ServerRuntime.get().store().deleteScene(scene.sceneId);
			} else if (node != null && node.address() != null
					&& !V2ServerRuntime.get().isSceneOwned(node.address())) {
				// Broken before the first tick resolved the scene (place-and-break, or a
				// chunk that loads and unloads within a tick): its persisted bytes would
				// otherwise sit on disk unreachable forever. The ownership check keeps a
				// live TE that already claimed this address safe.
				V2ServerRuntime.get().store().deleteScene(node.address());
			}
		}
		V2ServerRuntime.get().unregister(this);
	}

	// ------------------------------------------------------------------
	// Per-tick pump (called by V2ServerRuntime on the server thread, tick END)

	/**
	 * Grant this tick's texture-write allowance, at tick START.
	 *
	 * This MUST run before any OC synchronized replay: a call that burned its budget late in
	 * tick T is re-run during T+1's world tick, and if the allowance had not been reset by
	 * then the replay would find the budget still spent — turning the promised transparent
	 * retry into a refusal.
	 */
	public void serverBeginTick(long tick, int writeBudget) {
		synchronized (sceneLock) {
			if (scene != null) {
				scene.beginTick(tick, writeBudget);
			}
		}
	}

	public void serverPump(long tick, boolean policyTick) {
		synchronized (sceneLock) {
			if (scene == null || host == null) {
				return;
			}
			flushRecordingLocked();
			if (chunkDirty) {
				chunkDirty = false;
				// Coalesced to at most one markDirty per tick, on the server thread.
				markDirty();
			}
			if (policyTick) {
				resolveScreenLocked();
				V2ServerRuntime.get().applyProximityPolicy(this, host);
			}
			host.tick(tick);
		}
	}

	/**
	 * Keep the bound screen reference live and its scene id current. Re-resolved on the
	 * policy tick rather than cached forever: the screen's TE object is replaced on every
	 * chunk reload, and the OC node graph is the only authority on where it went.
	 */
	private void resolveScreenLocked() {
		if (boundScreenAddress == null && !bindingIsExplicit) {
			autoBindAdjacentLocked();
		}
		if (boundScreenAddress == null) {
			return;
		}
		if (node == null || node.network() == null) {
			boundScreen = null;
			return;
		}
		TileEntityScreen2 screen = screenAtLocked(boundScreenAddress);
		if (screen == null && !bindingIsExplicit) {
			// An auto binding is advisory, never a lock. If the address stopped resolving,
			// drop it and re-scan: a screen broken while this GPU's chunk was unloaded
			// never delivered onScreenRemoved, and would otherwise pin the GPU to a dead
			// address forever with auto-bind gated off. The re-scan re-finds a merely
			// unloaded neighbour (getTileEntity loads it), so a live binding survives.
			boundScreenAddress = null;
			autoBindAdjacentLocked();
			screen = screenAtLocked(boundScreenAddress);
			chunkDirty = true;
		}
		boundScreen = screen;
		if (boundScreenAddress == null) {
			return;
		}
		if (screen == null || screen.isInvalid()) {
			return; // merely unloaded: keep the claim — reconcileDriver() agrees
		}
		String driver = screen.driverAddress();
		if (driver != null && !driver.equals(node.address())
				&& node.network().node(driver) != null) {
			// Another LIVE GPU owns this screen: it took over while we were unloaded,
			// which screenIsAvailable() deliberately permits. That rule is only sound if
			// the displaced GPU yields — otherwise both re-push every policy tick and the
			// screen thrashes between two scenes forever, and reconcileDriver() cannot
			// arbitrate because whichever wrote last genuinely claims it. Drop our stale
			// claim locally; never clearScene(), that would tear down the real owner's.
			OpenGPU.logger.info("GPU " + node.address() + ": screen " + boundScreenAddress
					+ " is now driven by " + driver + "; dropping the stale binding");
			boundScreen = null;
			boundScreenAddress = null;
			chunkDirty = true;
			return;
		}
		screen.bindScene(node.address(), scene.sceneId);
	}

	/**
	 * Convenience default for the simple GPU-next-to-screen build. Deterministic: the six
	 * neighbours are scanned in fixed order, so the same build always binds the same screen.
	 */
	private void autoBindAdjacentLocked() {
		if (worldObj == null) {
			return;
		}
		final int[][] offsets = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
		for (int[] o : offsets) {
			TileEntity te = worldObj.getTileEntity(xCoord + o[0], yCoord + o[1], zCoord + o[2]);
			if (te instanceof TileEntityScreen2) {
				TileEntityScreen2 screen = (TileEntityScreen2) te;
				if (screen.node() != null && screen.node().address() != null
						&& screenIsAvailable(screen)) {
					boundScreenAddress = screen.node().address();
					chunkDirty = true;
					return;
				}
			}
		}
	}

	/** Resolve a screen by node address, or null if it is not on our network right now. */
	private TileEntityScreen2 screenAtLocked(String address) {
		if (address == null || node == null || node.network() == null) {
			return null;
		}
		Node found = node.network().node(address);
		return found != null && found.host() instanceof TileEntityScreen2
				? (TileEntityScreen2) found.host() : null;
	}

	/**
	 * Release the currently bound screen's driver lock. Resolves by ADDRESS rather than
	 * trusting the cached {@link #boundScreen}: that field is transient, is not restored
	 * from NBT, and is nulled whenever the screen's chunk is unloaded — so releasing
	 * through it silently leaks the lock exactly when the screen is not loaded, leaving a
	 * screen no GPU can ever claim.
	 */
	private void releaseBoundScreenLocked(TileEntityScreen2 except) {
		TileEntityScreen2 old = boundScreen != null ? boundScreen : screenAtLocked(boundScreenAddress);
		if (old != null && old != except) {
			old.clearScene(node != null ? node.address() : null);
		}
	}

	/**
	 * A screen is bindable when nothing drives it, we already drive it, or its recorded
	 * driver is no longer on the network. Without that last case a GPU broken (or unloaded)
	 * without unbinding would lock its screen out of every other GPU forever, and the
	 * lockout would survive world reload.
	 */
	private boolean screenIsAvailable(TileEntityScreen2 screen) {
		String driver = screen.driverAddress();
		if (driver == null || (node != null && driver.equals(node.address()))) {
			return true;
		}
		return node == null || node.network() == null || node.network().node(driver) == null;
	}

	/** The address this GPU believes it drives, for the screen's divergence check. */
	public String boundScreenAddress() {
		synchronized (sceneLock) {
			return boundScreenAddress;
		}
	}

	/** World position of the bound screen, or null — used by the subscription policy. */
	public int[] boundScreenPosition() {
		synchronized (sceneLock) {
			TileEntityScreen2 screen = boundScreen;
			if (screen == null || screen.isInvalid()) {
				return null;
			}
			return new int[] { screen.xCoord, screen.yCoord, screen.zCoord };
		}
	}

	/**
	 * Called when a bound screen block is broken. Clears the ADDRESS too, not just the
	 * cached instance: leaving it set pins the GPU to a dead address forever — auto-bind
	 * stays suppressed (it only runs while unbound) and getScreen() keeps naming a screen
	 * that no longer exists, so replacing the screen block in place never reconnects.
	 */
	public void onScreenRemoved(String screenAddress) {
		synchronized (sceneLock) {
			if (screenAddress != null && screenAddress.equals(boundScreenAddress)) {
				boundScreen = null;
				boundScreenAddress = null;
				chunkDirty = true;
			}
		}
	}

	/** Seal this tick's recording into the scene per the presentation semantics. */
	private void flushRecordingLocked() {
		try {
			if (pendingPresent != null) {
				scene.canvasPublish(implicitCanvasRes, pendingPresent);
				pendingPresent = null;
			}
			if (autopresent && !recording.isEmpty()) {
				scene.canvasAppend(implicitCanvasRes, new ArrayList<CanvasCommand>(recording));
				recording.clear();
			}
		} catch (RuntimeException e) {
			// Belt-and-braces: record-time checks should make this unreachable; a scene
			// exception at flush time has no Lua call to error into, so drop and log.
			OpenGPU.logger.warn("GPU " + scene.sceneId + ": dropped a frame at flush", e);
			pendingPresent = null;
			recording.clear();
		}
	}

	// Delegates used by the runtime dispatch (server thread).

	public void onResyncRequest(String watcherUuid) {
		synchronized (sceneLock) {
			if (host != null) {
				host.onResyncRequest(watcherUuid);
			}
		}
	}

	public void onResourceRequest(String watcherUuid, int epoch, int resId) {
		synchronized (sceneLock) {
			if (host != null) {
				host.onResourceRequest(watcherUuid, epoch, resId);
			}
		}
	}

	public void evictWatcher(String watcherUuid) {
		synchronized (sceneLock) {
			if (host != null) {
				host.evictWatcher(watcherUuid);
			}
		}
	}

	// ------------------------------------------------------------------
	// Persistence

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		super.writeToNBT(tag);
		if (node != null && node.host() == this) {
			NBTTagCompound nodeTag = new NBTTagCompound();
			node.save(nodeTag);
			tag.setTag("oc:node", nodeTag);
		}
		if (worldObj == null || worldObj.isRemote) {
			return; // stray client-side call: never touch scene persistence
		}
		// MUST happen before sceneLock is taken. Machine.run() holds the machine's own
		// monitor for its whole timeslice, and direct callbacks inside that slice take
		// sceneLock — so the global order is machine -> sceneLock. Taking sceneLock first
		// and then blocking in pause() inverts it and hangs the server thread permanently.
		// (OC's TextBuffer.save pauses with no callback-shared lock held, for this reason.)
		pauseConnectedMachines();
		synchronized (sceneLock) {
			if (scene == null) {
				// Not yet initialized this session: pass the untouched restore payload through.
				if (pendingStructure != null) {
					tag.setByteArray("v2scene", pendingStructure);
				} else if (pendingSpilled) {
					tag.setBoolean("v2sceneSpilled", true);
				}
				writeImplicitIds(tag);
				return;
			}
			flushRecordingLocked();
			host.saveBoundary();
			DirectoryResourceStore store = V2ServerRuntime.get().store();
			byte[] inline = ScenePersistence.persistStructure(scene, store);
			if (inline != null) {
				tag.setByteArray("v2scene", inline);
			} else {
				tag.setBoolean("v2sceneSpilled", true);
			}
			ScenePersistence.writeBodies(scene, store);
			// The structure just written no longer references these ids, so deleting their
			// bodies here cannot orphan a live reference. Without this, a GPU in a
			// permanently-loaded chunk grows the store forever (pruning only runs on load).
			for (Integer freed : freedSinceSave) {
				if (!scene.state().resources.containsKey(freed)) {
					store.delete(scene.sceneId, freed);
				}
			}
			freedSinceSave.clear();
			writeImplicitIds(tag);
		}
	}

	private void writeImplicitIds(NBTTagCompound tag) {
		tag.setInteger("v2implicitRes", implicitCanvasRes);
		tag.setInteger("v2implicitNode", implicitCanvasNode);
		if (boundScreenAddress != null) {
			tag.setString("v2screen", boundScreenAddress);
		}
		// An explicit bind/unbind is sticky across reloads; auto-bind keeps trying until
		// then, so a screen placed after its GPU is still picked up.
		tag.setBoolean("v2explicitBind", bindingIsExplicit);
		// Presentation mode is sticky state (first present() switches the canvas to manual);
		// losing it across a reload silently reverts a present()-mode program to append.
		tag.setBoolean("v2autopresent", autopresent);
		tag.setInteger("v2pushDepth", pushDepth);
		tag.setInteger("v2color", (colA & 0xFF) << 24 | (colR & 0xFF) << 16 | (colG & 0xFF) << 8 | (colB & 0xFF));
	}

	/**
	 * The design's unconditional save discipline (OC's TextBuffer.save "happy thread
	 * synchronization hack"): pausing every connected machine blocks until in-flight direct
	 * callbacks complete, so the persisted scene is consistent with what Lua observed.
	 */
	private void pauseConnectedMachines() {
		if (node == null || node.network() == null) {
			return;
		}
		for (Node n : node.network().nodes()) {
			if (n.host() instanceof Machine) {
				Machine machine = (Machine) n.host();
				if (!machine.isPaused()) {
					machine.pause(0.1);
				}
			}
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		if (node != null && node.host() == this && tag.hasKey("oc:node")) {
			node.load(tag.getCompoundTag("oc:node"));
		}
		if (tag.hasKey("v2scene")) {
			pendingStructure = tag.getByteArray("v2scene");
			pendingSpilled = false;
		} else {
			pendingStructure = null;
			pendingSpilled = tag.getBoolean("v2sceneSpilled");
		}
		implicitCanvasRes = tag.getInteger("v2implicitRes");
		implicitCanvasNode = tag.getInteger("v2implicitNode");
		boundScreenAddress = tag.hasKey("v2screen") ? tag.getString("v2screen") : null;
		bindingIsExplicit = tag.getBoolean("v2explicitBind");
		// Absent key = fresh placement or a pre-v2 save: append mode is the documented default.
		autopresent = !tag.hasKey("v2autopresent") || tag.getBoolean("v2autopresent");
		pushDepth = Math.max(0, Math.min(PUSH_DEPTH_CAP, tag.getInteger("v2pushDepth")));
		if (tag.hasKey("v2color")) {
			int packed = tag.getInteger("v2color");
			colA = packed >>> 24 & 0xFF;
			colR = packed >>> 16 & 0xFF;
			colG = packed >>> 8 & 0xFF;
			colB = packed & 0xFF;
		}
	}

	// ------------------------------------------------------------------
	// Description packet: identity/geometry only, never bulk state.

	@Override
	public Packet getDescriptionPacket() {
		NBTTagCompound tag = new NBTTagCompound();
		if (scene != null) {
			tag.setString("sceneId", scene.sceneId);
		}
		return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 2, tag);
	}

	@Override
	public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
		NBTTagCompound tag = pkt.func_148857_g();
		clientSceneId = tag.hasKey("sceneId") ? tag.getString("sceneId") : null;
	}

	// ------------------------------------------------------------------
	// OC Environment

	@Override
	public Node node() {
		return node;
	}

	@Override
	public void onConnect(Node node) {}

	@Override
	public void onDisconnect(Node node) {}

	@Override
	public void onMessage(Message message) {}

	// ------------------------------------------------------------------
	// Recording helpers (all called with sceneLock held via record())

	private void requireScene() throws Exception {
		if (scene == null) {
			throw new Exception("GPU is still initializing");
		}
	}

	private void record(CanvasCommand command) throws Exception {
		requireScene();
		// Project against the canvas as it will look AFTER the pending publish: the flush
		// publishes pendingPresent before appending the recording. publish() copies
		// verbatim (no compaction), so its size is the exact post-publish visible count.
		int visible;
		if (pendingPresent != null) {
			visible = pendingPresent.size();
		} else {
			ResourceInfo res = scene.state().resources.get(implicitCanvasRes);
			SceneCanvas canvas = res != null ? res.canvas : null;
			visible = canvas != null ? canvas.visibleCommands().size() : 0;
		}
		int projected = recording.size() + 1 + (autopresent ? visible : 0);
		if (projected > CANVAS_COMMAND_CAP) {
			throw new Exception("canvas command list full; fill()/clearRectangle() the canvas or use present()");
		}
		recording.add(command);
		chunkDirty = true;
	}

	private static double checkFinite(double v, String name) throws Exception {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			throw new Exception(name + " must be a finite number");
		}
		return v;
	}

	private long usedVramLocked() {
		long used = 0;
		for (ResourceInfo res : scene.state().resources.values()) {
			if (res.type == V2Wire.RES_TEXTURE) {
				used += res.sizeBytes;
			} else if (res.type == V2Wire.RES_CANVAS && res.canvas != null) {
				used += (long) res.canvas.commandCap * CANVAS_SLOT_COST;
			}
		}
		return used;
	}

	// ------------------------------------------------------------------
	// Lua callbacks — Canvas2D recording

	@Callback(direct = true, limit = 256, doc = "function(r:number, g:number, b:number[, a:number]) -- Set the current draw color (0-255 channels).")
	public Object[] setColor(Context context, Arguments args) throws Exception {
		int r = args.checkInteger(0), g = args.checkInteger(1), b = args.checkInteger(2);
		int a = args.count() > 3 ? args.checkInteger(3) : 255;
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_SET_COLOR, r, g, b, a));
			colR = clamp255(r); colG = clamp255(g); colB = clamp255(b); colA = clamp255(a);
		}
		return null;
	}

	private static int clamp255(int v) {
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	@Callback(direct = true, doc = "function():number, number, number, number -- The current draw color.")
	public Object[] getColor(Context context, Arguments args) {
		synchronized (sceneLock) {
			return new Object[] { colR, colG, colB, colA };
		}
	}

	@Callback(direct = true, limit = 256, doc = "function() -- Fill the whole canvas with the current color.")
	public Object[] fill(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_FILL));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(x:number, y:number) -- Plot one point.")
	public Object[] plot(Context context, Arguments args) throws Exception {
		double x = checkFinite(args.checkDouble(0), "x"), y = checkFinite(args.checkDouble(1), "y");
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_PLOT, x, y));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(x1:number, y1:number, x2:number, y2:number) -- Draw a line.")
	public Object[] line(Context context, Arguments args) throws Exception {
		double[] a = quad(args);
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_LINE, a[0], a[1], a[2], a[3]));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(x:number, y:number, w:number, h:number) -- Outline a rectangle.")
	public Object[] rectangle(Context context, Arguments args) throws Exception {
		double[] a = quad(args);
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_RECT, a[0], a[1], a[2], a[3]));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(x:number, y:number, w:number, h:number) -- Fill a rectangle.")
	public Object[] filledRectangle(Context context, Arguments args) throws Exception {
		double[] a = quad(args);
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_FILL_RECT, a[0], a[1], a[2], a[3]));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(x:number, y:number, w:number, h:number) -- Hard-set a rectangle to the current color (no blending).")
	public Object[] clearRectangle(Context context, Arguments args) throws Exception {
		double[] a = quad(args);
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_CLEAR_RECT, a[0], a[1], a[2], a[3]));
		}
		return null;
	}

	private static double[] quad(Arguments args) throws Exception {
		return new double[] {
				checkFinite(args.checkDouble(0), "arg1"), checkFinite(args.checkDouble(1), "arg2"),
				checkFinite(args.checkDouble(2), "arg3"), checkFinite(args.checkDouble(3), "arg4") };
	}

	@Callback(direct = true, limit = 256, doc = "function(x1,y1,x2,y2,x3,y3) -- Outline a triangle.")
	public Object[] triangle(Context context, Arguments args) throws Exception {
		double[] a = six(args);
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_TRIANGLE, a[0], a[1], a[2], a[3], a[4], a[5]));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(x1,y1,x2,y2,x3,y3) -- Fill a triangle.")
	public Object[] filledTriangle(Context context, Arguments args) throws Exception {
		double[] a = six(args);
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_FILL_TRIANGLE, a[0], a[1], a[2], a[3], a[4], a[5]));
		}
		return null;
	}

	private static double[] six(Arguments args) throws Exception {
		double[] a = new double[6];
		for (int i = 0; i < 6; i++) {
			a[i] = checkFinite(args.checkDouble(i), "arg" + (i + 1));
		}
		return a;
	}

	@Callback(direct = true, limit = 256, doc = "function(cx:number, cy:number, w:number, h:number) -- Outline a center-anchored oval.")
	public Object[] oval(Context context, Arguments args) throws Exception {
		double[] a = quad(args);
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_OVAL, a[0], a[1], a[2], a[3]));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(cx:number, cy:number, w:number, h:number) -- Fill a center-anchored oval.")
	public Object[] filledOval(Context context, Arguments args) throws Exception {
		double[] a = quad(args);
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_FILL_OVAL, a[0], a[1], a[2], a[3]));
		}
		return null;
	}

	@Callback(direct = true, limit = 128, doc = "function(text:string, x:number, y:number) -- Draw text with the built-in font.")
	public Object[] drawText(Context context, Arguments args) throws Exception {
		String text = args.checkString(0);
		if (text.length() > V2Wire.MAX_TEXT_CHARS) {
			throw new Exception("text too long (max " + V2Wire.MAX_TEXT_CHARS + " characters)");
		}
		double x = checkFinite(args.checkDouble(1), "x"), y = checkFinite(args.checkDouble(2), "y");
		synchronized (sceneLock) {
			record(CanvasCommand.text(x, y, text));
		}
		return null;
	}

	@Callback(direct = true, doc = "function(text:string):number -- Width of the text in logical units.")
	public Object[] getTextWidth(Context context, Arguments args) throws Exception {
		return new Object[] { FontMetrics.get().textWidth(args.checkString(0)) };
	}

	@Callback(direct = true, limit = 256, doc = "function(id:number, x:number, y:number[, tx:number, ty:number, w:number, h:number]) -- Draw a texture (optionally a sub-rectangle), tinted by the current color.")
	public Object[] drawTexture(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0);
		double x = checkFinite(args.checkDouble(1), "x"), y = checkFinite(args.checkDouble(2), "y");
		synchronized (sceneLock) {
			requireScene();
			ResourceInfo res = scene.state().resources.get(id);
			if (res == null || res.type != V2Wire.RES_TEXTURE) {
				throw new Exception("invalid texture id " + id);
			}
			if (args.count() > 3) {
				double tx = checkFinite(args.checkDouble(3), "tx"), ty = checkFinite(args.checkDouble(4), "ty");
				double w = checkFinite(args.checkDouble(5), "w"), h = checkFinite(args.checkDouble(6), "h");
				record(CanvasCommand.of(V2Wire.OP_DRAW_TEXTURE_SUB, id, x, y, tx, ty, w, h));
			} else {
				record(CanvasCommand.of(V2Wire.OP_DRAW_TEXTURE, id, x, y));
			}
		}
		return null;
	}

	// Transforms

	@Callback(direct = true, limit = 256, doc = "function(dx:number, dy:number) -- Translate subsequent draws.")
	public Object[] translate(Context context, Arguments args) throws Exception {
		double dx = checkFinite(args.checkDouble(0), "dx"), dy = checkFinite(args.checkDouble(1), "dy");
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_TRANSLATE, dx, dy));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(angle:number) -- Rotate subsequent draws (radians).")
	public Object[] rotate(Context context, Arguments args) throws Exception {
		double angle = checkFinite(args.checkDouble(0), "angle");
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_ROTATE, angle));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(angle:number, x:number, y:number) -- Rotate around a point (radians).")
	public Object[] rotateAround(Context context, Arguments args) throws Exception {
		double angle = checkFinite(args.checkDouble(0), "angle");
		double x = checkFinite(args.checkDouble(1), "x"), y = checkFinite(args.checkDouble(2), "y");
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_ROTATE_AROUND, angle, x, y));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(sx:number, sy:number) -- Scale subsequent draws.")
	public Object[] scale(Context context, Arguments args) throws Exception {
		double sx = checkFinite(args.checkDouble(0), "sx"), sy = checkFinite(args.checkDouble(1), "sy");
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_SCALE, sx, sy));
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function() -- Push the current transform.")
	public Object[] push(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			if (pushDepth >= PUSH_DEPTH_CAP) {
				throw new Exception("transform stack overflow (max depth " + PUSH_DEPTH_CAP + ")");
			}
			record(CanvasCommand.of(V2Wire.OP_PUSH));
			pushDepth++;
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function() -- Pop the transform stack.")
	public Object[] pop(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			if (pushDepth <= 0) {
				throw new Exception("transform stack underflow (pop without push)");
			}
			record(CanvasCommand.of(V2Wire.OP_POP));
			pushDepth--;
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function() -- Reset the transform to identity.")
	public Object[] origin(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_ORIGIN));
		}
		return null;
	}

	// Presentation

	@Callback(direct = true, doc = "function(enabled:boolean) -- Toggle per-tick auto-presentation (append mode). Disabling switches to explicit present().")
	public Object[] autopresent(Context context, Arguments args) throws Exception {
		boolean enabled = args.checkBoolean(0);
		synchronized (sceneLock) {
			if (enabled && !autopresent) {
				// Re-arming append mode: the new recording continues the presented list, so
				// the presented frame's unbalanced depth comes back into scope.
				pushDepth += publishedTailDepth;
			}
			publishedTailDepth = 0;
			autopresent = enabled;
			chunkDirty = true;
		}
		return null;
	}

	@Callback(direct = true, limit = 64, doc = "function() -- Publish the recorded commands as the whole frame (replace mode).")
	public Object[] present(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			autopresent = false;
			pendingPresent = new ArrayList<CanvasCommand>(recording);
			recording.clear();
			// The next frame records from scratch, so its push depth starts at zero —
			// otherwise a frame ending mid-push charges its depth to every later frame
			// until a false "stack overflow" fires.
			publishedTailDepth = pushDepth;
			pushDepth = 0;
			chunkDirty = true;
		}
		return null;
	}

	// Resources

	@Callback(direct = true, limit = 8, doc = "function(width:number, height:number):number -- Create a blank RGBA texture; returns its id.")
	public Object[] createTexture(Context context, Arguments args) throws Exception {
		int w = args.checkInteger(0), h = args.checkInteger(1);
		if (w <= 0 || h <= 0 || w > V2Wire.MAX_TEXTURE_DIM || h > V2Wire.MAX_TEXTURE_DIM) {
			throw new Exception("texture size out of range (1.." + V2Wire.MAX_TEXTURE_DIM + ")");
		}
		synchronized (sceneLock) {
			requireScene();
			long bytes = (long) w * h * 4L;
			if (usedVramLocked() + bytes > VRAM_BUDGET_BYTES) {
				throw new Exception("not enough GPU memory");
			}
			int id = scene.createTexture(w, h, new byte[(int) bytes]);
			freedSinceSave.remove(id); // id reuse must not schedule a delete of live bytes
			chunkDirty = true;
			return new Object[] { id };
		}
	}

	@Callback(direct = true, limit = 8, doc = "function(width:number, height:number, data:string):number -- Create a texture from packed RGBA bytes (width*height*4); returns its id.")
	public Object[] createTextureFrom(Context context, Arguments args) throws Exception {
		int w = args.checkInteger(0), h = args.checkInteger(1);
		byte[] data = args.checkByteArray(2);
		if (w <= 0 || h <= 0 || w > V2Wire.MAX_TEXTURE_DIM || h > V2Wire.MAX_TEXTURE_DIM) {
			throw new Exception("texture size out of range (1.." + V2Wire.MAX_TEXTURE_DIM + ")");
		}
		long expected = (long) w * h * 4L;
		if (data.length != expected) {
			throw new Exception("data length must be width*height*4 (expected " + expected
					+ ", got " + data.length + ")");
		}
		synchronized (sceneLock) {
			requireScene();
			if (usedVramLocked() + expected > VRAM_BUDGET_BYTES) {
				throw new Exception("not enough GPU memory");
			}
			int id = scene.createTexture(w, h, data);
			freedSinceSave.remove(id); // id reuse must not schedule a delete of live bytes
			chunkDirty = true;
			return new Object[] { id };
		}
	}

	@Callback(direct = true, limit = 64, doc = "function(id:number, x:number, y:number, w:number, h:number, data:string):boolean -- Write packed RGBA bytes (w*h*4, row-major, top-left origin) into a texture region. Max 16384 bytes per call and per tick; over-budget calls retry on the next tick, and return false only if another computer on this GPU also exhausted that tick's allowance.")
	public Object[] writeRegion(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0);
		int x = args.checkInteger(1), y = args.checkInteger(2);
		int w = args.checkInteger(3), h = args.checkInteger(4);
		byte[] data = args.checkByteArray(5);
		if (w < 1 || h < 1) {
			throw new Exception("region must be at least 1x1");
		}
		long expected = (long) w * h * 4L;
		if (expected > V2Wire.MAX_WRITE_REGION_BYTES) {
			throw new Exception("region too large (max " + V2Wire.MAX_WRITE_REGION_BYTES
					+ " bytes per call, e.g. 64x64 RGBA); split the write");
		}
		if (data.length != expected) {
			throw new Exception("data length must be w*h*4 (expected " + expected
					+ ", got " + data.length + ")");
		}
		synchronized (sceneLock) {
			requireScene();
			ResourceInfo res = scene.state().resources.get(id);
			if (res == null) {
				throw new Exception("invalid texture id " + id);
			}
			if (res.type != V2Wire.RES_TEXTURE) {
				throw new Exception("writeRegion is only valid on textures "
						+ "(canvases have no pixel bytes; draw into them)");
			}
			// Long arithmetic: Arguments.checkInteger SATURATES an out-of-range Lua number
			// to Integer.MAX_VALUE instead of rejecting it, so `x + w` in int wraps negative
			// and passes. This is the outermost of three guards, all of which must be long.
			if (x < 0 || y < 0 || (long) x + w > res.width || (long) y + h > res.height) {
				throw new Exception("region out of bounds");
			}
			if (res.latestVersion == Integer.MAX_VALUE) {
				throw new Exception("texture version space exhausted; free and recreate the texture");
			}
			if (scene.writeBudgetRemaining() < expected) {
				// First pass: burn the call budget so OC raises LimitReachedException and
				// re-runs this call on the next tick, transparently to Lua.
				//
				// consumeCallBudget is a NO-OP during that synchronized replay
				// (Machine: `if (architecture.isInitialized && !inSynchronizedCall)`), so on
				// the replay we must not fall through to ServerScene.writeRegion — it would
				// throw and surface as a hard Lua error, contradicting this method's own
				// contract. The allowance is granted at tick START precisely so the replay
				// normally finds room; if another computer on the same GPU spent it first,
				// report the refusal honestly instead of throwing or silently dropping.
				context.consumeCallBudget(Double.MAX_VALUE);
				if (scene.writeBudgetRemaining() < expected) {
					return new Object[] { false, "write allowance exhausted this tick" };
				}
			}
			scene.writeRegion(id, x, y, w, h, data);
			chunkDirty = true;
		}
		return new Object[] { true };
	}

	@Callback(direct = true, doc = "function():number, number -- Remaining and total writeRegion bytes for this tick.")
	public Object[] getWriteBudget(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			return new Object[] { scene.writeBudgetRemaining(), V2Wire.MAX_WRITE_BYTES_PER_TICK };
		}
	}

	@Callback(direct = true, limit = 32, doc = "function(id:number) -- Free a texture.")
	public Object[] freeTexture(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0);
		synchronized (sceneLock) {
			requireScene();
			ResourceInfo res = scene.state().resources.get(id);
			if (res == null || res.type != V2Wire.RES_TEXTURE) {
				throw new Exception("invalid texture id " + id);
			}
			// Buffered draws referencing this texture would fail validation at flush and
			// cost the WHOLE frame. Freeing a texture drawn earlier in the same tick is a
			// legal call, so strip the now-dangling draws instead — visually identical to
			// the placeholder semantics, and the rest of the frame survives.
			dropDrawsReferencing(recording, id);
			if (pendingPresent != null) {
				dropDrawsReferencing(pendingPresent, id);
			}
			scene.freeResource(id);
			freedSinceSave.add(id);
			chunkDirty = true;
		}
		return null;
	}

	private static void dropDrawsReferencing(List<CanvasCommand> commands, int resId) {
		for (java.util.Iterator<CanvasCommand> it = commands.iterator(); it.hasNext();) {
			CanvasCommand cmd = it.next();
			if ((cmd.op == V2Wire.OP_DRAW_TEXTURE || cmd.op == V2Wire.OP_DRAW_TEXTURE_SUB)
					&& (int) cmd.args[0] == resId) {
				it.remove();
			}
		}
	}

	@Callback(direct = true, doc = "function([id:number]):number, number -- Size of a texture, or of the canvas without an id.")
	public Object[] getSize(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			if (args.count() > 0) {
				ResourceInfo res = scene.state().resources.get(args.checkInteger(0));
				if (res == null) {
					throw new Exception("invalid resource id");
				}
				return new Object[] { res.width, res.height };
			}
			return new Object[] { DEFAULT_WIDTH, DEFAULT_HEIGHT };
		}
	}

	@Callback(direct = true, doc = "function():number, number -- The canvas resolution in logical units.")
	public Object[] getResolution(Context context, Arguments args) {
		return new Object[] { DEFAULT_WIDTH, DEFAULT_HEIGHT };
	}

	// Screen binding. NOT direct: these walk the node network, which is not thread-safe off
	// the server thread (OC's own gpu.bind is likewise non-direct).

	@Callback(doc = "function(address:string) -- Bind this GPU's scene to a screen.")
	public Object[] bind(Context context, Arguments args) throws Exception {
		String address = args.checkString(0);
		if (node == null || node.network() == null) {
			throw new Exception("GPU is not connected to a network");
		}
		Node target = node.network().node(address);
		if (target == null || !(target.host() instanceof TileEntityScreen2)) {
			throw new Exception("no screen with address " + address);
		}
		TileEntityScreen2 screen = (TileEntityScreen2) target.host();
		if (!screenIsAvailable(screen)) {
			// One driving GPU per surface: the old scene keeps living on its own GPU.
			throw new Exception("screen is already driven by GPU " + screen.driverAddress());
		}
		synchronized (sceneLock) {
			releaseBoundScreenLocked(screen);
			boundScreenAddress = address;
			boundScreen = screen;
			bindingIsExplicit = true;
			chunkDirty = true;
			if (scene != null) {
				screen.bindScene(node.address(), scene.sceneId);
			}
		}
		return new Object[] { true };
	}

	@Callback(doc = "function() -- Unbind the current screen; the scene stays on this GPU.")
	public Object[] unbind(Context context, Arguments args) {
		synchronized (sceneLock) {
			releaseBoundScreenLocked(null);
			boundScreen = null;
			boundScreenAddress = null;
			bindingIsExplicit = true; // an explicit unbind must not be undone by auto-bind
			chunkDirty = true;
		}
		return null;
	}

	@Callback(direct = true, doc = "function():string -- Address of the bound screen, or nil.")
	public Object[] getScreen(Context context, Arguments args) {
		synchronized (sceneLock) {
			return new Object[] { boundScreenAddress };
		}
	}

	// Memory accounting

	@Callback(direct = true, doc = "function():number -- Total GPU memory in bytes.")
	public Object[] getTotalMemory(Context context, Arguments args) {
		return new Object[] { VRAM_BUDGET_BYTES };
	}

	@Callback(direct = true, doc = "function():number -- Used GPU memory in bytes.")
	public Object[] getUsedMemory(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			return new Object[] { usedVramLocked() };
		}
	}

	@Callback(direct = true, doc = "function():number -- Free GPU memory in bytes.")
	public Object[] getFreeMemory(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			return new Object[] { VRAM_BUDGET_BYTES - usedVramLocked() };
		}
	}
}
