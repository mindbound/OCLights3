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
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import opengpu.OpenGPU;
import opengpu.v2.mc.FontMetrics;
import opengpu.v2.persist.DirectoryResourceStore;
import opengpu.v2.protocol.MessageCodec;
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

	/**
	 * Largest canvas dimension a program may ask for.
	 *
	 * NOT derived from {@link V2Wire#MAX_TEXTURE_DIM}, which bounds a different thing: a
	 * texture arrives as a wire body, so its transfer cost partly self-limits. A canvas has
	 * no body — it is a command list — so nothing self-limits it, and 8192 square would be a
	 * 256 MB client allocation from one line of Lua.
	 *
	 * A compile-time constant, never configurable: a decode-time bound that differs between
	 * two peers turns a legal batch into an apply failure, which latches needsResync, and
	 * the snapshot that would repair it carries the same over-cap resource. That is a
	 * permanent black screen for one player, not graceful degradation.
	 *
	 * The real ceiling is usually {@link #VRAM_BUDGET_BYTES}, not this: 2048 square is the
	 * whole 16 MiB budget on its own. This just stops a single dimension from running away
	 * (a 1x4194304 canvas fits any pixel budget and no GL context will allocate it).
	 */
	public static final int MAX_CANVAS_DIM = 2048;

	/**
	 * Minimum server ticks between accepted resolution changes.
	 *
	 * MAX_CANVAS_DIM bounds how big one client allocation may be; this bounds how OFTEN it
	 * is redone, which nothing else does. A resize is ~30 bytes on the wire (one free, one
	 * create) and obliges every client within subscribe range to tear down and reallocate
	 * the scene FBO and re-render the whole canvas — an amplification of roughly 500,000:1,
	 * with no per-frame budget in front of it the way texture uploads have one. Alternating
	 * between two sizes that both fit the VRAM budget would otherwise sustain that every
	 * tick, for free, against every player who merely walks past.
	 */
	private static final int RESIZE_COOLDOWN_TICKS = 20;

	/** Server tick as of the last pump, for the resize cooldown. */
	private volatile long serverTick;
	/** Deliberately below any real tick so the first resize is never throttled. */
	private long lastResizeTick = -RESIZE_COOLDOWN_TICKS - 1L;

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

	private final InputRouter inputRouter = new InputRouter();

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
		// Before the early returns: the resize cooldown reads this from a machine thread and
		// must keep advancing even on a tick where there is no scene yet, or the first
		// resize after one would compare against a stale clock.
		serverTick = tick;
		synchronized (sceneLock) {
			if (scene == null || host == null) {
				return;
			}
			inputRouter.beginTick(tick);
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
		if (!screen.isOrigin()) {
			// The screen was absorbed into a larger wall and is now a satellite: its
			// component is invisible and it displays nothing, so the binding has to move.
			//
			// FOLLOW the wall — do not drop the binding. Dropping it and "letting auto-bind
			// find the new origin" cannot work: auto-bind runs only when !bindingIsExplicit
			// (see the head of this method), and bindingIsExplicit is set by bind(), is
			// persisted, and is never cleared. So for precisely the binding a Lua program
			// established deliberately, dropping here was terminal — getScreen() returned
			// nil forever, the wall stayed dark, and it survived a world restart. Following
			// is also what the player who grew the wall plainly meant.
			TileEntityScreen2 originTile = screen.origin();
			if (originTile == null || originTile.node() == null
					|| originTile.node().address() == null) {
				return; // origin's chunk not loaded: keep the claim and retry next tick
			}
			OpenGPU.logger.info("GPU " + node.address() + ": screen " + boundScreenAddress
					+ " joined a wall; following it to origin " + originTile.node().address());
			boundScreenAddress = originTile.node().address();
			screen = originTile;
			boundScreen = screen;
			chunkDirty = true;
			// Fall through: the origin may already be driven by another live GPU, and the
			// check below is the one that arbitrates that.
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
		int[] size = resolutionLocked();
		screen.bindScene(node.address(), scene.sceneId, size[0], size[1]);
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
				// Bind the wall's ORIGIN, whichever tile happens to be adjacent: the origin
				// owns the surface, and a satellite's component is not even visible.
				TileEntityScreen2 screen = ((TileEntityScreen2) te).origin();
				// isOrigin() as well as origin(): during a reshape a tile's stored origin can
				// briefly name a tile that is itself a satellite, and claiming it here only
				// to have the check below reject it on the same tick spins a bind/release
				// loop — one log line and one chunk markDirty per policy tick, forever.
				if (screen != null && screen.isOrigin() && screen.node() != null
						&& screen.node().address() != null && screenIsAvailable(screen)) {
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
			inputRouter.evictWatcher(watcherUuid);
		}
	}

	/**
	 * Player input aimed at this GPU's scene. Rejected unless the sender is a current
	 * watcher and names the live incarnation — a client must not be able to drive a scene
	 * it cannot see, nor one that has since been replaced.
	 */
	public void onInput(String watcherUuid, EntityPlayer player, MessageCodec.Input input) {
		synchronized (sceneLock) {
			if (scene == null || host == null) {
				return;
			}
			if (!host.isSubscribed(watcherUuid) || input.epoch != scene.epoch()) {
				return;
			}
			TileEntityScreen2 screen = boundScreen;
			if (screen == null || screen.isInvalid()) {
				return; // no surface: nothing for the signal's address to be
			}
			// REACH CHECK, separate from the render subscription. Subscription answers "who
			// gets pixels" and is deliberately generous (64 blocks, no line of sight, no
			// opt-in); it must never double as "who may inject signals into these machines".
			// Without this, any player who merely walks within render range can flood every
			// computer on a stranger's network. OC's own screen gates mouse input on
			// isUseableByPlayer (8 blocks) before it sends anything, and canInteract is NOT
			// a substitute — it returns true for everyone until a machine has explicit users.
			if (!withinReach(player, screen)) {
				return;
			}
			int[] res = resolutionLocked();
			inputRouter.route(input, watcherUuid, player, screen, res[0], res[1]);
		}
	}

	/** Vanilla's interaction distance, squared — the same bound OC uses for screen input. */
	private static final double REACH_SQ = 64.0;

	/**
	 * Measured to the nearest tile of the WALL, not to the bound screen's own block. The
	 * bound screen is always the wall's ORIGIN, and the origin is sticky, so it can sit at
	 * any corner of a surface up to MAX_WALL_SPAN tiles across — measuring from it left a
	 * player standing right in front of the far end of a wide wall outside the bound, and
	 * every GUI event was then dropped with no error, no log and no client feedback. The
	 * in-world click path has no such gate, so the same wall still answered right-clicks:
	 * it read as a broken GUI rather than as a range limit.
	 */
	private static boolean withinReach(EntityPlayer player, TileEntityScreen2 screen) {
		if (player.worldObj != screen.getWorldObj()) {
			return false;
		}
		return screen.distanceSqToNearestTile(player.posX, player.posY, player.posZ) <= REACH_SQ;
	}

	/**
	 * An in-world click on the bound screen: synthesized as a press/release pair so Lua sees
	 * a complete gesture. Real in-world dragging needs continuous client-side raytracing and
	 * is deliberately not faked here.
	 */
	public void onSurfaceClick(EntityPlayer player, TileEntityScreen2 screen, int x, int y, int button) {
		synchronized (sceneLock) {
			if (scene == null) {
				return;
			}
			String key = player.getUniqueID().toString();
			int[] res = resolutionLocked();
			inputRouter.route(new MessageCodec.Input(scene.sceneId, scene.epoch(),
					MessageCodec.INPUT_POINTER_DOWN, x, y, button), key, player, screen,
					res[0], res[1]);
			inputRouter.route(new MessageCodec.Input(scene.sceneId, scene.epoch(),
					MessageCodec.INPUT_POINTER_UP, x, y, button), key, player, screen,
					res[0], res[1]);
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

	/**
	 * The scene's LIVE logical size, under sceneLock — the one number every consumer must
	 * agree on.
	 *
	 * Everything that maps a physical hit to a logical pixel has to read this rather than
	 * the defaults it used to hardcode: the renderer letterboxes against the live canvas
	 * size, so a click path frozen at DEFAULT_WIDTH x DEFAULT_HEIGHT lands on a different
	 * pixel than the one drawn under the crosshair, drifting further toward the edges.
	 * Falls back to the defaults only before the canvas exists, where no click can land yet.
	 */
	private int[] resolutionLocked() {
		ResourceInfo res = scene == null ? null : scene.state().displayCanvas();
		return res == null ? new int[] { DEFAULT_WIDTH, DEFAULT_HEIGHT }
				: new int[] { res.width, res.height };
	}

	/** The scene's live logical size, for the server-side click path in BlockScreen2. */
	int[] resolution() {
		synchronized (sceneLock) {
			return resolutionLocked();
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
				// Command slots AND pixels. Charging only the slots left the canvas as the
				// single allocation this GPU can force onto every client in subscribe range
				// that no budget bounded — the flat slot cost is the same whether the canvas
				// is 1x1 or 2048x2048, while the client's FBO is w*h*4 either way.
				used += (long) res.canvas.commandCap * CANVAS_SLOT_COST
						+ (long) res.width * (long) res.height * 4L;
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

	@Callback(direct = true, limit = 256, doc = "function(id:number, x:number, y:number[, tx:number, ty:number, w:number, h:number]) -- Draw a texture (optionally a sub-rectangle) at its own colors. The current draw color does NOT tint it (setColor affects shapes and text only).")
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

	// ------------------------------------------------------------------
	// Retained scene graph: offscreen canvases and nodes.
	//
	// Raw, id-based surface by design. DESIGN-RENDERER-V2 states the layering outright — "the
	// wrapper lib is the documented API; the raw callback surface is documented via doc= for
	// library authors" — so these exist to be wrapped into canvas/node objects by the Lua
	// library, with the handle-invalidation semantics that belong there. Deliberately NOT
	// AbstractValue wrappers on this side: legacy A-03 was a non-static inner AbstractValue
	// that OC could not reinstantiate on restore, and ids sidestep the whole class of hazard.
	//
	// NO createGroup(): NODE_GROUP is a wire type with no semantics yet. SceneNode has no
	// parent field and there is no PROP_PARENT, so a group can hold nothing — transform
	// parenting is Stage B. Exposing it now would ship a call that provably does nothing.

	@Callback(direct = true, limit = 8, doc = "function(width:number, height:number[, commandCap:number]):number -- Allocate an offscreen canvas; returns its resource id. Draw into it, or use it as a drawTexture/sprite source.")
	public Object[] createCanvas(Context context, Arguments args) throws Exception {
		int w = args.checkInteger(0), h = args.checkInteger(1);
		int cap = args.count() > 2 ? args.checkInteger(2) : CANVAS_COMMAND_CAP;
		if (w <= 0 || h <= 0 || w > MAX_CANVAS_DIM || h > MAX_CANVAS_DIM) {
			throw new Exception("canvas size out of range (1.." + MAX_CANVAS_DIM + ")");
		}
		if (cap <= 0 || cap > CANVAS_COMMAND_CAP) {
			throw new Exception("command cap out of range (1.." + CANVAS_COMMAND_CAP + ")");
		}
		synchronized (sceneLock) {
			requireScene();
			// Same two-term charge usedVramLocked applies: command slots AND pixels. A canvas
			// is a real client FBO allocation, so it is bounded by the same budget as textures.
			long cost = (long) cap * CANVAS_SLOT_COST + (long) w * (long) h * 4L;
			if (usedVramLocked() + cost > VRAM_BUDGET_BYTES) {
				throw new Exception("not enough GPU memory");
			}
			int id = scene.createCanvas(w, h, cap);
			chunkDirty = true;
			return new Object[] { id };
		}
	}

	@Callback(direct = true, limit = 32, doc = "function(id:number) -- Free an offscreen canvas. Nodes and recorded draws still referencing it render nothing.")
	public Object[] freeCanvas(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0);
		synchronized (sceneLock) {
			requireScene();
			ResourceInfo res = scene.state().resources.get(id);
			if (res == null || res.type != V2Wire.RES_CANVAS) {
				throw new Exception("invalid canvas id " + id);
			}
			if (id == implicitCanvasRes) {
				throw new Exception("cannot free the display canvas");
			}
			// Same reasoning as freeTexture: a draw buffered earlier this tick that references
			// this canvas would fail validation at flush and cost the whole frame.
			dropDrawsReferencing(recording, id);
			if (pendingPresent != null) {
				dropDrawsReferencing(pendingPresent, id);
			}
			scene.freeResource(id);
			chunkDirty = true;
		}
		return null;
	}

	/**
	 * TEXTURE refs only, deliberately.
	 *
	 * DESIGN-RENDERER-V2 says an offscreen canvas is "referenceable as a texture source by
	 * drawTexture/Sprite", but the client does not implement that yet: Canvas2dRenderer draws
	 * a sprite only when {@code res.type == RES_TEXTURE}, so a canvas-backed sprite converges
	 * perfectly and renders NOTHING. Accepting one here would ship a call that silently does
	 * nothing — the same reason there is no createGroup. Use createCanvasNode to show a canvas
	 * until the renderer grows canvas-as-texture-source.
	 */
	@Callback(direct = true, limit = 16, doc = "function(textureId:number):number -- Create a sprite node drawing a texture as a quad; returns its node id. For an offscreen canvas use createCanvasNode.")
	public Object[] createSprite(Context context, Arguments args) throws Exception {
		return createNodeLocked(V2Wire.NODE_SPRITE, args.checkInteger(0), true);
	}

	@Callback(direct = true, limit = 16, doc = "function(canvasId:number):number -- Create a node that displays an offscreen canvas as a layer; returns its node id.")
	public Object[] createCanvasNode(Context context, Arguments args) throws Exception {
		return createNodeLocked(V2Wire.NODE_CANVAS, args.checkInteger(0), false);
	}

	/**
	 * Shared node allocation: validates the referenced resource, then charges the node cap.
	 *
	 * {@code wantTexture} distinguishes the two node kinds, and the check is not pedantry —
	 * each renders only its own resource type, so a mismatched ref produces a node that
	 * converges and draws nothing.
	 */
	private Object[] createNodeLocked(byte nodeType, int ref, boolean wantTexture) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			ResourceInfo res = scene.state().resources.get(ref);
			if (res == null) {
				throw new Exception("invalid resource id " + ref);
			}
			byte want = wantTexture ? V2Wire.RES_TEXTURE : V2Wire.RES_CANVAS;
			if (res.type != want) {
				throw new Exception(wantTexture
						? "resource " + ref + " is a canvas, not a texture; use createCanvasNode"
						: "resource " + ref + " is a texture, not a canvas; use createSprite");
			}
			if (scene.state().nodes.size() >= ServerScene.MAX_NODES) {
				throw new Exception("scene node limit reached (" + ServerScene.MAX_NODES + ")");
			}
			int id = scene.createNode(nodeType, ref);
			chunkDirty = true;
			return new Object[] { id };
		}
	}

	@Callback(direct = true, limit = 32, doc = "function(nodeId:number) -- Remove a node from the scene.")
	public Object[] freeNode(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0);
		synchronized (sceneLock) {
			requireScene();
			requireNodeLocked(id);
			if (id == implicitCanvasNode) {
				throw new Exception("cannot free the display node");
			}
			scene.freeNode(id);
			chunkDirty = true;
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(nodeId:number, x:number, y:number[, rotation:number, scaleX:number, scaleY:number]) -- Set a node's transform. Rotation is radians; scale defaults to 1.")
	public Object[] setNodeTransform(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0);
		double x = args.checkDouble(1), y = args.checkDouble(2);
		double rot = args.count() > 3 ? args.checkDouble(3) : 0.0;
		double sx = args.count() > 4 ? args.checkDouble(4) : 1.0;
		double sy = args.count() > 5 ? args.checkDouble(5) : sx;
		requireFinite(x, "x"); requireFinite(y, "y"); requireFinite(rot, "rotation");
		requireFinite(sx, "scaleX"); requireFinite(sy, "scaleY");
		synchronized (sceneLock) {
			requireScene();
			requireNodeLocked(id);
			scene.setTransform(id, x, y, rot, sx, sy);
			chunkDirty = true;
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(nodeId:number, z:number) -- Set a node's draw order; higher draws later.")
	public Object[] setNodeZ(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0), z = args.checkInteger(1);
		synchronized (sceneLock) {
			requireScene();
			requireNodeLocked(id);
			scene.setZ(id, z);
			chunkDirty = true;
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(nodeId:number, visible:boolean) -- Show or hide a node without freeing it.")
	public Object[] setNodeVisible(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0);
		boolean visible = args.checkBoolean(1);
		synchronized (sceneLock) {
			requireScene();
			requireNodeLocked(id);
			scene.setVisible(id, visible);
			chunkDirty = true;
		}
		return null;
	}

	@Callback(direct = true, limit = 256, doc = "function(nodeId:number, r:number, g:number, b:number[, a:number]) -- Multiply a node's output by a colour (0-255 channels).")
	public Object[] setNodeTint(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0);
		int r = clampChannel(args.checkInteger(1));
		int g = clampChannel(args.checkInteger(2));
		int b = clampChannel(args.checkInteger(3));
		int a = args.count() > 4 ? clampChannel(args.checkInteger(4)) : 255;
		synchronized (sceneLock) {
			requireScene();
			requireNodeLocked(id);
			scene.setTint(id, (a << 24) | (r << 16) | (g << 8) | b);
			chunkDirty = true;
		}
		return null;
	}

	/** Callers hold sceneLock. */
	private void requireNodeLocked(int nodeId) throws Exception {
		if (!scene.state().nodes.containsKey(nodeId)) {
			throw new Exception("invalid node id " + nodeId);
		}
	}

	/**
	 * Rejects NaN and infinity before they reach a transform.
	 *
	 * checkDouble does NOT saturate the way checkInteger does — it hands the raw Lua number
	 * straight through, so a NaN would ride the wire, land in every mirror identically (no
	 * divergence detector fires), and poison the renderer's transform matrix for the whole
	 * scene rather than just that node.
	 */
	private static void requireFinite(double v, String name) throws Exception {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			throw new Exception(name + " must be a finite number");
		}
	}

	private static int clampChannel(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
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
			int[] size = resolutionLocked();
			return new Object[] { size[0], size[1] };
		}
	}

	@Callback(direct = true, doc = "function():number, number -- The canvas resolution in logical units.")
	public Object[] getResolution(Context context, Arguments args) throws Exception {
		// synchronized + requireScene like every other scene reader. This used to be a bare
		// return of two constants, which was safe only while it read no state: `scene` is
		// null until the first server tick resolves the node address, and a direct callback
		// runs on a machine executor thread.
		synchronized (sceneLock) {
			requireScene();
			int[] size = resolutionLocked();
			return new Object[] { size[0], size[1] };
		}
	}

	@Callback(direct = true, doc = "function():number, number -- The largest resolution setResolution will accept. Memory may bind first; see maxMemory/freeMemory.")
	public Object[] maxResolution(Context context, Arguments args) {
		return new Object[] { MAX_CANVAS_DIM, MAX_CANVAS_DIM };
	}

	@Callback(direct = true, limit = 4, doc = "function(width:number, height:number):boolean -- Set the canvas resolution. Clears the canvas. No-op if unchanged.")
	public Object[] setResolution(Context context, Arguments args) throws Exception {
		int w = args.checkInteger(0), h = args.checkInteger(1);
		if (w <= 0 || h <= 0 || w > MAX_CANVAS_DIM || h > MAX_CANVAS_DIM) {
			throw new Exception("resolution out of range (1.." + MAX_CANVAS_DIM + ")");
		}
		synchronized (sceneLock) {
			requireScene();
			ensureImplicitCanvas();
			ResourceInfo current = scene.state().resources.get(implicitCanvasRes);
			if (current == null) {
				throw new Exception("GPU is still initializing");
			}
			// The canvas Lua draws into and the canvas that defines the resolution must be
			// the same object. They are, by construction — the implicit canvas is created
			// first and so holds the lowest canvas node id — but nothing enforced it, and a
			// divergence would make this call resize something nobody is looking at while
			// reporting success. Fail loudly instead; SceneState.displayCanvas() and
			// DisplayCanvasTest carry the reasoning.
			ResourceInfo display = scene.state().displayCanvas();
			if (display == null || display.id != implicitCanvasRes) {
				throw new Exception("internal error: the drawing canvas is not the display canvas");
			}
			if (current.width == w && current.height == h) {
				return new Object[] { false }; // unchanged: do not clear the canvas for a no-op
			}
			// Cooldown AFTER the no-op check, so a program that re-asserts its current size
			// is never throttled for asking a question it already knows the answer to.
			long now = serverTick;
			if (now - lastResizeTick < RESIZE_COOLDOWN_TICKS) {
				throw new Exception("resolution changed too recently; "
						+ (RESIZE_COOLDOWN_TICKS - (now - lastResizeTick)) + " tick(s) to wait");
			}
			// Budget: REPLACE the canvas's charge, do not add to it. The pattern used by
			// createTexture — used + new > BUDGET — would refuse a SHRINK whenever the
			// canvas is what filled the budget, i.e. you could not make it smaller because
			// it was too big.
			long oldCost = (long) current.width * (long) current.height * 4L;
			long newCost = (long) w * (long) h * 4L;
			if (usedVramLocked() - oldCost + newCost > VRAM_BUDGET_BYTES) {
				throw new Exception("not enough GPU memory");
			}
			// Flush FIRST. This callback is direct, so it stages its deltas the moment Lua
			// calls it, while commands recorded earlier in the same tick are still sitting
			// in the pending buffer — without this they would be published AFTER the resize
			// and silently resurrect pre-resize drawing on the new canvas.
			flushRecordingLocked();
			// Same resource id, so the display node keeps pointing at it and the lowest-id
			// display rule is untouched. See ServerScene.recreateCanvas.
			scene.recreateCanvas(implicitCanvasRes, w, h, CANVAS_COMMAND_CAP);
			// The canvas and the pending recording are both gone, so the true net push depth
			// is zero — reset the counters that track it. present() already does exactly
			// this when IT wipes the visible list (see the comment there about a frame ending
			// mid-push charging its depth to every later frame until a false stack overflow
			// fires); this is the same wipe by a different route. DeltaApplier builds a fresh
			// SceneCanvas whose own depth restarts at 0, so leaving these alone would let the
			// two diverge — and pushDepth is persisted, so the drift would survive a save.
			pushDepth = 0;
			publishedTailDepth = 0;
			lastResizeTick = now;
			// Push the new size to the bound screen NOW rather than waiting for the policy
			// tick to re-push it. That backstop runs every 20 ticks, so screen.getResolution()
			// would contradict gpu.getResolution() for up to a second after every resize.
			// sceneId and driverAddress are unchanged, so this only refreshes the volatile
			// pair — it triggers no markDirty and no packet from this machine thread.
			if (boundScreen != null && node != null && node.address() != null) {
				boundScreen.bindScene(node.address(), scene.sceneId, w, h);
			}
			chunkDirty = true;
			return new Object[] { true };
		}
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
		if (!screen.isOrigin()) {
			// Only reachable if a satellite's address were somehow known; the surface is the
			// wall, and the wall's identity is its origin.
			throw new Exception("that screen is part of a wall; bind its origin instead");
		}
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
				int[] size = resolutionLocked();
				screen.bindScene(node.address(), scene.sceneId, size[0], size[1]);
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
