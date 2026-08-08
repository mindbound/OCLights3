package opengpu.v2.mc.server;

import java.util.ArrayList;
import java.util.List;

import li.cil.oc.api.Network;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.FileSystem;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import opengpu.OpenGPU;
import opengpu.Tags;
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
	/**
	 * The Lua-facing API generation, reported by {@link #getVersion}. Bump it in the SAME change
	 * that adds a callback.
	 *
	 * Deliberately neither {@code PROTOCOL_VERSION} nor the mod version, because the three answer
	 * different questions — and this codebase has already paid once for one number serving several
	 * roles (see MAX_SUBMIT_BYTES and PERF-BASELINE.md):
	 * <ul>
	 * <li>{@code PROTOCOL_VERSION} is the WIRE contract between server and client. It bumps on a
	 *     layout change and implies a save migration. A Lua program cannot observe it and cannot
	 *     do anything about it.</li>
	 * <li>the mod version is human-facing and moves on every release, including releases that
	 *     change nothing a program can call.</li>
	 * <li>this is the one a PROGRAM can branch on: "does this build have everything up to N".
	 *     Monotone, never reused, and independent of both of the above.</li>
	 * </ul>
	 *
	 * 1 = the v2 surface as first shipped. 2 = adds getVersion/getLimits. 3 = adds swapVisibility.
	 * 4 = adds setFont/getFontMetrics and the optional fontId argument to getTextWidth.
	 *
	 * The "SAME change" rule above is not decoration: level 3 was late, because swapVisibility
	 * shipped in the commit after the one that introduced this constant and nobody bumped it. For
	 * that window {@code getVersion().api} reported 2 on builds that HAD swapVisibility and on
	 * builds that did not, which is precisely the distinction the field exists to make — a
	 * feature-detecting program would have concluded the callback was absent and taken the
	 * fallback path forever.
	 *
	 * Level 4 nearly repeated it verbatim: the font callbacks were written, tested and rendered
	 * in-game while this constant still said 3. Note what does NOT catch that — the whole point
	 * of the field is to be readable on a build that lacks the feature, so on the build you are
	 * developing every check passes and every test is green. Only reading this line does.
	 */
	public static final int API_LEVEL = 4;
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
	 * The component name, FROZEN (2026-08-04). This is not an interim value and does not
	 * become "ocl_gpu" at the cut-over.
	 *
	 * Reclaiming "ocl_gpu" was the earlier plan, on the theory that the name should follow the
	 * mod. It was dropped because the name would be a compatibility promise the API cannot
	 * keep: a program written against legacy ocl_gpu finds a component of the same name whose
	 * every method has different semantics — bindTexture is gone, import is gone, coordinates
	 * are logical rather than pixels. Answering to that name is worse than not answering.
	 *
	 * Freezing it now is also what makes the Lua library writable: every component.X site in
	 * it would otherwise be written against a name a later rename invalidates.
	 */
	public static final String COMPONENT_NAME = "opengpu";

	/**
	 * The jar-backed read-only filesystem carrying our Lua library and tutorial.
	 *
	 * This is the ONLY mechanism by which a mod's Lua reaches an in-game computer, and until
	 * now the v2 stack had none — the whole repo's single `FileSystem.fromClass` lives in the
	 * legacy TE. That made the Lua library undeliverable and would have made deleting the old
	 * stack silently remove the delivery path: the jar keeps shipping the files, nothing
	 * mounts them, nothing fails to build and nothing logs.
	 *
	 * Deliberately rooted at `lua/v2`, NOT the legacy `lua/` tree. The legacy contents target
	 * a dead API (`lib/gpu.lua` binds `component.ocl_gpu` and calls a dropped `import()`), so
	 * carrying them over would ship a library that cannot work against this component.
	 */
	private final ManagedEnvironment fileSystem;

	public TileEntityGpu2() {
		node = Network.newNode(this, Visibility.Network).withComponent(COMPONENT_NAME).create();
		fileSystem = FileSystem.asManagedEnvironment(
				FileSystem.fromClass(OpenGPU.class, OpenGPU.ASSET_DOMAIN, "lua/v2"), COMPONENT_NAME);
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
		// BEFORE node.remove(), because the flush resolves the target surface through this
		// node's network and there is no network left afterwards. Same ordering trick
		// BlockScreen2.breakBlock uses deliberately, and the reason the screen's own
		// onChunkUnload cannot be fixed without the same inversion there.
		//
		// This is the GPU going away while the SCREEN may well survive — a broken GPU, an
		// unloading GPU chunk next to a loaded screen. Without it, every gesture held on that
		// still-live screen is stranded with no later event able to end it: the router dies
		// with this tile entity, so even a subsequent release has nothing left to match.
		// NOTHING IS EMITTED HERE, and correctness deliberately does NOT depend on whether
		// this hook runs before or after the chunk's unload save — 1.7.10's ordering there
		// is contested, and this file is done betting on tick-order trivia. If the save
		// runs after, these moved records ride its writeToNBT; if it ran first, it already
		// persisted the same gestures, because snapshotForSave folds live activePointer
		// entries into the record list precisely so no save can miss them. Either way the
		// records reach disk exactly once and are delivered when the chunk returns.
		// Emitting here as well (as a previous design did) was the double-delivery. A map
		// move also cannot throw, which retires the try/catch the emitting version needed.
		synchronized (sceneLock) {
			inputRouter.flushAll();
		}
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
			// SYNCHRONOUS delivery, one of exactly two such sites (the other is screen
			// removal). The pending list dies with this TE and its block has no NBT to
			// come back through, so "deliver at next tick's START" would deliver never.
			// The world demonstrably continues — a player just broke a block — so a signal
			// emitted now is consumed on the next tick; the only way it is not is a crash,
			// which loses in-flight state of every kind, not just ours.
			inputRouter.flushAll();
			if (node != null) {
				int[] res = scene != null ? resolutionLocked() : new int[] { 0, 0 };
				inputRouter.flushPendingLastChance(node, res[0], res[1]);
			}
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
			// THE delivery point for stranded-gesture releases, and it must be START phase.
			// A signal emitted here is normally consumed by the machines' timeslice later
			// in this same tick — before any point at which this tick can save the world —
			// so removing a record on send does not race a save. Both previous designs
			// emitted at END phase or at the transition itself, and both lost the
			// quit-to-title case to the same mechanism: the signal sat queued across the
			// shutdown save and OC's resume destroyed it. Records created during this tick
			// (transitions run after START) wait one tick; records that never see another
			// START ride writeToNBT instead, which is the entire point.
			//
			// "Normally", not "always": emitRelease refuses to send while any reachable
			// machine is paused (OC's startup delay after a load, a neighbouring save's
			// pause), which closes the systematic windows where a queued signal could sit
			// across a save. What remains is an executor too starved to pop its queue
			// within its own tick, or a machine chunk unloading in the very tick of
			// delivery — crash-class residuals, documented in the design doc.
			if (inputRouter.pendingReleaseCount() > 0 && node != null) {
				int[] res = scene != null ? resolutionLocked() : new int[] { 0, 0 };
				long now = worldObj != null ? worldObj.getTotalWorldTime() : 0L;
				inputRouter.flushPending(node, res[0], res[1], now);
			}
		}
	}

	public void serverPump(long tick, boolean policyTick) {
		// Before the early returns: the resize cooldown reads this from a machine thread and
		// must keep advancing even on a tick where there is no scene yet, or the first
		// resize after one would compare against a stale clock.
		serverTick = tick;
		synchronized (sceneLock) {
			// All three of these run BEFORE the early return. Held-gesture records outlive
			// the scene — they are restored from NBT before one exists, and a GPU with no
			// scene still owes releases from the previous session. Gating them on `scene`
			// would mean the dirty flag never reached markDirty. (Delivery and expiry
			// themselves live in serverBeginTick, which is likewise not scene-gated.)
			inputRouter.setWorldTime(worldObj != null ? worldObj.getTotalWorldTime() : 0L);
			inputRouter.beginTick(tick);
			if (inputRouter.consumePersistenceDirty()) {
				// Gesture state is persisted now, so a chunk that is never marked modified
				// is never written: without this a press could fail to reach disk, and the
				// removal of a delivered record could fail to reach disk too — replaying
				// that release on every subsequent load.
				markDirty();
			}
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
			// The binding MOVES here without any surface being destroyed, which is why
			// releaseBoundScreenLocked never sees it. Flush BEFORE the reassignment, while
			// boundScreenAddress is still the address the held gestures were recorded against
			// — afterwards there is nothing left pointing at the old surface, so the flush
			// would match no slots and the gestures would strand silently.
			flushBoundScreenLocked();
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
			// Release before dropping the claim, exactly as the wall-follow branch does. A
			// review argued this branch could never run with gestures held — that another GPU
			// can only take the screen if OUR node is off the network, which would mean this TE
			// had already unloaded and taken its router with it. A network PARTITION defeats
			// that: cut the cable and we stay alive holding slots while, on the far side,
			// screenIsAvailable sees no claim and lets another GPU bind. Repair the cable and
			// we arrive here with a live router. The screen is reachable at this instant — the
			// condition above just resolved its driver through the network — so the release
			// goes out.
			flushBoundScreenLocked();
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
	/**
	 * Losing a surface by BINDING: {@code bind} (with the new screen), {@code unbind} (with
	 * null) and teardown pass through here.
	 *
	 * NOT the only way a gesture stops being completable, and an earlier version of this
	 * javadoc claimed it was — which is how the wall-follow transition went unhandled. The
	 * binding can also MOVE without any surface being lost ({@code resolveScreenLocked}), the
	 * screen can be removed under it ({@code onScreenRemoved}), this GPU can go away
	 * ({@code onUnload}), a gate in {@code onInput} can reject the release, and the runtime can
	 * unsubscribe a watcher who has walked away or changed dimension
	 * ({@code V2ServerRuntime.applyProximityPolicy}).
	 *
	 * To find every site rather than trusting this list, grep {@code InputRouter} for the four
	 * entry points — {@code flushScreen}, {@code flushWatcher}, {@code flushPointer},
	 * {@code flushAll} — and read their callers. A previous version told the reader to grep two
	 * helper names, which found five of the eight sites and read as if it had found all of them.
	 *
	 * Any pointer still held on the outgoing screen is RELEASED here, by emitting the
	 * monitor_up the client can no longer cause. That matters most for the case a program
	 * creates itself: a {@code monitor_down} handler that calls {@code unbind()} — an exit
	 * button — or {@code bind(other)} — a display cycler. Those callbacks are deliberately NOT
	 * direct, so they run on the server thread serialized with onInput, which makes the
	 * matching release arrive after the transition EVERY time rather than as a race. It would
	 * then be dropped by onInput's screen gate, and the program would wait forever for a
	 * button it told the server to disconnect.
	 *
	 * Flushed BEFORE clearScene, while the old node is still attached and can carry a signal.
	 */
	private void releaseBoundScreenLocked(TileEntityScreen2 except) {
		// Flushed by ADDRESS, and before resolving the tile — the gestures to end are the ones
		// recorded against boundScreenAddress, which is known even when the screen's chunk is
		// unloaded and screenAtLocked() therefore returns null. Gating the flush on resolving a
		// live TE meant an unbind while the screen was out of the world skipped it entirely.
		//
		// But ONLY when the surface actually changes. `except` is what bind() passes so that an
		// idempotent re-bind is not a teardown, and screenIsAvailable deliberately permits
		// re-binding the screen this GPU already drives. Flushing regardless made
		// `gpu.bind(gpu.getScreen())` — an ordinary line in an init routine — end a drag the
		// player was in the middle of, after which their moves hit an empty slot and were
		// dropped. Nothing is being lost in that case, so nothing needs releasing.
		String keepAddress = except != null && except.node() != null
				? except.node().address() : null;
		if (keepAddress == null || !keepAddress.equals(boundScreenAddress)) {
			flushBoundScreenLocked();
		}
		TileEntityScreen2 old = boundScreen != null ? boundScreen : screenAtLocked(boundScreenAddress);
		if (old != null && old != except) {
			old.clearScene(node != null ? node.address() : null);
		}
	}

	/**
	 * End every gesture held on the currently bound surface.
	 *
	 * Separate from releaseBoundScreenLocked because losing a surface is NOT the only way to
	 * strand a gesture — the binding can also MOVE under one. This is called from every such
	 * transition; see each call site for which.
	 */
	private void flushBoundScreenLocked() {
		if (boundScreenAddress == null) {
			return;
		}
		inputRouter.flushScreen(boundScreenAddress);
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
			// SYNCHRONOUS delivery, one of exactly two such sites (the other is GPU block
			// destruction). The screen's node address dies with the block and a re-placed
			// screen gets a fresh one, so a record parked for the delivery loop would wait
			// on an address that can never resolve again — until expiry, with the program
			// holding the button the whole time. Emitting NOW works precisely because
			// BlockScreen2.breakBlock notifies the runtime BEFORE super.breakBlock: the
			// screen TE is still valid and its node still on the network. That ordering is
			// why this call sits here rather than in invalidate().
			if (node != null) {
				inputRouter.flushScreen(screenAddress);
				// Scoped to the dying address: force-delivering the WHOLE pending list
				// mid-tick would strip unrelated records of the START-phase guarantee.
				int[] res = scene != null ? resolutionLocked() : new int[] { 0, 0 };
				inputRouter.flushPendingFor(screenAddress, node, res[0], res[1]);
			}
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
			// RELEASES, not just forgetting. An early version cleared the router's map and
			// emitted nothing, which left every machine holding the button exactly as it had
			// been — the stuck press outlives the session that caused it, because it lives
			// in Lua state that OC persists, not in the map.
			//
			// No player parameter, and no emission here, and both absences carry the lesson
			// of two failed designs. Emitting at this transition raced the shutdown save —
			// quit-to-title is logged out from networkTick inside the final tick-loop
			// iteration, so the queued signal died in OC's resume. The gestures instead
			// become pending records: delivered at the next tick's START phase if the server
			// lives (as unchecked computer.signal, needing no player), or persisted by
			// writeToNBT if it does not. See docs/dev/INPUT-GESTURE-PERSISTENCE.md.
			flushWatcherLocked(watcherUuid);
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
			// The gates below RELEASE any pointer this watcher holds rather than merely
			// dropping the event. Dropping is how the original defect worked: a POINTER_UP
			// swallowed by a gate leaves Lua waiting on a button forever, and no amount of
			// server-side tidying reaches that state — only a signal does.
			//
			// This is not the same as exempting a gate, which an earlier review rightly
			// refused: the client's coordinates are discarded and the release carries
			// Pointer.x/y, which the server accepted at press time. A disqualified sender
			// cannot choose where the click lands, and still gets no event through.
			//
			// POINTERS ONLY. Held KEYS are not released, because the key path keeps no state to
			// release from — route() emits monitor_key_down and tracks nothing, so the server
			// does not know a key is down. That is a real gap and it predates this code; it is
			// recorded in ROADMAP rather than half-addressed here.
			if (!host.isSubscribed(watcherUuid) || input.epoch != scene.epoch()) {
				flushWatcherLocked(watcherUuid);
				return;
			}
			TileEntityScreen2 screen = boundScreen;
			if (screen == null || screen.isInvalid()) {
				flushWatcherLocked(watcherUuid);
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
				// Only a RELEASE ends a gesture here, and only THAT BUTTON'S. This gate fires
				// per event, so ending on any rejected event would kill a live drag the instant
				// a player crossed the boundary — jitter at exactly 8 blocks, a shove, one step
				// back. A release is the one event meaning that gesture is over, and it is
				// precisely the one the original defect swallowed: press, get moved out of
				// reach by a walk, teleport, knockback or portal, release into nothing.
				//
				// Scoped to input.c because slots are per button and two-button drags are
				// ordinary: releasing the right button must not end the left one, which is
				// still held. Ending the whole watcher here would have re-created the very
				// dead-drag this narrowing exists to prevent, just triggered by a release.
				//
				// A player who leaves and never releases is caught by the UNSUBSCRIBE path in
				// V2ServerRuntime.applyProximityPolicy, which releases before it unsubscribes.
				// Note it is that call and not the subscription GATE here: a gate can only
				// reject what a client sends, and a silent client sends nothing. An earlier
				// version of this comment credited the gate, which was false until the flush at
				// the unsubscribe site existed.
				if (input.kind == MessageCodec.INPUT_POINTER_UP
						&& input.c >= 0 && input.c <= 2) {
					inputRouter.flushPointer(watcherUuid, input.c);
				}
				return;
			}
			// No pre-route pending flush here any more, deliberately: the delivery loop runs
			// at the START phase of every tick, before this event could even have been
			// drained, so every deliverable record is already gone by the time a press
			// routes. A pre-route flush could only retry the UNdeliverable ones, which is
			// exactly what the loop will keep doing anyway.
			int[] res = resolutionLocked();
			inputRouter.route(input, watcherUuid, player, screen, res[0], res[1]);
		}
	}

	/** End every gesture this watcher holds, wherever it holds them. Caller holds sceneLock. */
	private void flushWatcherLocked(String watcherUuid) {
		inputRouter.flushWatcher(watcherUuid);
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
			// No pre-route pending flush at either route() entry point any more: the
			// delivery loop runs at every tick's START phase, so deliverable records are
			// gone before any in-world click of this tick is processed.
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
		// The library filesystem's node address must survive too, under the same key the
		// legacy TE used. Without it the loot disk is re-minted on every chunk load, and any
		// program holding its address — or an fstab entry pointing at it — breaks on reload.
		if (fileSystem != null && fileSystem.node() != null) {
			NBTTagCompound fsTag = new NBTTagCompound();
			fileSystem.node().save(fsTag);
			tag.setTag("oc:fsnode", fsTag);
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
			// BEFORE the scene==null early return below. A GPU can hold a gesture whose
			// scene has not been restored yet this session, and returning early would drop
			// the very records this exists to keep.
			writeHeldGesturesLocked(tag);
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

	/**
	 * Persist every gesture this GPU believes is held, so it can be released on the far
	 * side of the reload.
	 *
	 * Necessary because the server's record is in-memory only while the program's is in
	 * Lua state that OpenComputers persists: without this the program wakes up holding a
	 * button that nothing on the server remembers, and no transition can ever end it.
	 * Emitting the release at shutdown instead does not work — it reaches disk and is
	 * dequeued during OC's resume without ever reaching Lua. See
	 * docs/dev/INPUT-GESTURE-PERSISTENCE.md.
	 */
	private void writeHeldGesturesLocked(NBTTagCompound tag) {
		// Unconditional, unlike the list below: ids only mean anything relative to this
		// counter, and letting it restart at 1 on reload would let a fresh press mint the
		// id a pending record still carries. See InputRouter.pointerIdCounter.
		tag.setInteger("v2ptr", inputRouter.pointerIdCounter());
		long now = worldObj != null ? worldObj.getTotalWorldTime() : 0L;
		java.util.List<InputRouter.PendingRelease> held = inputRouter.snapshotForSave(now);
		if (held.isEmpty()) {
			return;
		}
		NBTTagList list = new NBTTagList();
		for (InputRouter.PendingRelease r : held) {
			NBTTagCompound e = new NBTTagCompound();
			e.setString("w", r.watcher);
			e.setInteger("b", r.button);
			e.setInteger("i", r.id);
			e.setString("s", r.address);
			e.setInteger("x", r.x);
			e.setInteger("y", r.y);
			e.setLong("t", r.recordedAt);
			list.appendTag(e);
		}
		tag.setTag("v2held", list);
	}

	/**
	 * Under sceneLock, unlike the rest of readFromNBT. The other fields it sets are only
	 * read once this TE starts ticking, but pendingReleases is touched by serverPump and
	 * onInput, and a chunk can be read while a neighbouring GPU is already pumping.
	 */
	private void readHeldGestures(NBTTagCompound tag) {
		if (tag.hasKey("v2ptr")) {
			synchronized (sceneLock) {
				inputRouter.restorePointerIdCounter(tag.getInteger("v2ptr"));
			}
		}
		if (!tag.hasKey("v2held")) {
			synchronized (sceneLock) {
				inputRouter.restorePending(
						java.util.Collections.<InputRouter.PendingRelease>emptyList());
			}
			return;
		}
		NBTTagList list = tag.getTagList("v2held", 10);
		java.util.List<InputRouter.PendingRelease> out =
				new java.util.ArrayList<InputRouter.PendingRelease>();
		// Bounded on the way IN as well as out: this list came off disk, and nothing
		// guarantees the disk was written by us. NEWEST kept — reading from the tail —
		// because the write side evicts oldest-first and a cap that kept the head would
		// silently invert that policy on the round trip.
		int from = Math.max(0, list.tagCount() - InputRouter.MAX_PENDING_RELEASES);
		for (int i = from; i < list.tagCount(); i++) {
			NBTTagCompound e = list.getCompoundTagAt(i);
			String watcher = e.getString("w");
			String surface = e.getString("s");
			if (watcher == null || watcher.isEmpty() || surface == null || surface.isEmpty()) {
				continue; // an unnameable holder or unresolvable surface can never be sent
			}
			// Validated like the client path validates its inputs, because the disk is not
			// trusted either. Button outside 0..2 would emit a release no press can have
			// produced; ids below 1 cannot have been minted (0 is the scroll path's "no
			// gesture"); a negative recordedAt makes the expiry subtraction overflow and
			// the record immortal. Coordinates get a sanity bound rather than a scene check
			// — emitRelease clamps to the live scene at send time, but only when a scene
			// exists, and a hand-edited coordinate should not ride through that gap.
			int button = e.getInteger("b");
			int id = e.getInteger("i");
			long recordedAt = e.getLong("t");
			int x = e.getInteger("x");
			int y = e.getInteger("y");
			if (button < 0 || button > 2 || id < 1 || recordedAt < 0
					|| x < 0 || y < 0 || x > 0x3FFF || y > 0x3FFF) {
				continue;
			}
			out.add(new InputRouter.PendingRelease(watcher, button, id, surface, x, y,
					recordedAt));
		}
		synchronized (sceneLock) {
			inputRouter.restorePending(out);
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
		if (fileSystem != null && fileSystem.node() != null && tag.hasKey("oc:fsnode")) {
			fileSystem.node().load(tag.getCompoundTag("oc:fsnode"));
		}
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
		readHeldGestures(tag);
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

	/**
	 * Wire counters for this GPU's scene, or null before the sync layer exists.
	 *
	 * Read without the scene lock on purpose: the only caller is the debug overlay, and the
	 * fields behind it are plain longs. Taking sceneLock from the render thread to draw a
	 * number would be a far worse trade than showing one that is half a second stale.
	 */
	public opengpu.v2.stats.SceneStats sceneStats() {
		SceneHost h = host;
		return h == null ? null : h.stats();
	}

	@Override
	public Node node() {
		return node;
	}

	/**
	 * Attach the library filesystem to any computer that connects, and detach on the way out.
	 *
	 * Same shape as the legacy TE's, and the shape matters: the filesystem node is connected
	 * PER CONNECTING CONTEXT rather than once to the network, so a computer sees the library
	 * exactly while it is connected to this GPU. The final branch removes the fs node when the
	 * GPU's own node goes — without it the environment outlives its host and leaks.
	 */
	@Override
	public void onConnect(Node node) {
		// fileSystem is null when the jar resource is missing: FileSystem.fromClass returns
		// null for an absent path and asManagedEnvironment passes that through. A packaging
		// slip must cost the library, not the GPU — an NPE here would take down the node
		// network's connect path for every computer that touched this block.
		if (fileSystem != null && fileSystem.node() != null && node.host() instanceof Context) {
			node.connect(fileSystem.node());
		}
	}

	@Override
	public void onDisconnect(Node node) {
		if (fileSystem == null || fileSystem.node() == null) {
			return;
		}
		if (node.host() instanceof Context) {
			node.disconnect(fileSystem.node());
		} else if (node == this.node) {
			fileSystem.node().remove();
		}
	}

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

	/**
	 * Whole-canvas hard clear — and the only clear that compacts unconditionally.
	 *
	 * {@code fill()} truncates the recorded list only when the colour is fully OPAQUE, because
	 * a translucent fill blends with what is under it and replay-from-scratch would differ.
	 * A full-canvas CLEAR_RECT hard-SETS, so it compacts at any alpha — including alpha 0,
	 * which is how a program makes the surface genuinely transparent rather than tinted.
	 *
	 * Reading the canvas size server-side is the point: a program calling
	 * {@code clearRectangle(0, 0, getResolution())} has to fetch the size, and its copy goes
	 * stale the moment setResolution runs — after which the rect no longer spans the canvas
	 * and silently stops compacting.
	 *
	 * Clears the CANVAS only. Sprite and canvas nodes are siblings composited above it and are
	 * untouched by any drawing call; {@link #clearNodes} is what removes those.
	 */
	@Callback(direct = true, limit = 256, doc = "function() -- Hard-clear the whole canvas to the current color (no blending). Does not remove nodes; see clearNodes.")
	public Object[] clear(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			int[] size = resolutionLocked();
			record(CanvasCommand.of(V2Wire.OP_CLEAR_RECT, 0, 0, size[0], size[1]));
		}
		return null;
	}

	/**
	 * Free every node except the display node — "give me a blank scene".
	 *
	 * Nodes are RETAINED and PERSISTED: they outlive the program that made them, survive a
	 * computer reboot, and are written into the world save. Without this, a program that dies
	 * holding node ids — a crash, an interrupt, a reboot — orphans them permanently, and the
	 * only recovery is breaking the GPU. Every long-running program should call this at
	 * startup rather than trusting the scene to be empty.
	 */
	@Callback(direct = true, limit = 8, doc = "function():number -- Free every node except the display node; returns how many were freed. Nodes persist across reboots, so call this at startup.")
	public Object[] clearNodes(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			// Collect first: freeNode mutates the map we would otherwise be iterating.
			java.util.List<Integer> doomed = new java.util.ArrayList<Integer>();
			for (Integer id : scene.state().nodes.keySet()) {
				if (id.intValue() != implicitCanvasNode) {
					doomed.add(id);
				}
			}
			for (Integer id : doomed) {
				scene.freeNode(id.intValue());
			}
			if (!doomed.isEmpty()) {
				chunkDirty = true;
			}
			return new Object[] { doomed.size() };
		}
	}

	/**
	 * Live node ids, ascending, as a 1-based Lua table.
	 *
	 * The scene is persistent state a program may inherit rather than create, so a library
	 * needs some way to see what is already there — to adopt it, audit it, or decide to clear
	 * it. Without this the only way to learn a node exists is to have created it.
	 */
	@Callback(direct = true, limit = 8, doc = "function():table -- Live node ids, ascending, 1-based. The display node is always the first.")
	public Object[] nodes(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			// LinkedHashMap keyed 1..n: OC converts a Map to a Lua table, and the scene's
			// nodes are a TreeMap, so ascending order is free and deterministic.
			java.util.Map<Integer, Integer> out = new java.util.LinkedHashMap<Integer, Integer>();
			int i = 1;
			for (Integer id : scene.state().nodes.keySet()) {
				out.put(Integer.valueOf(i++), id);
			}
			return new Object[] { out };
		}
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

	@Callback(direct = true, limit = 128, doc = "function(fontId:number) -- Select the font for subsequent drawText. 0=unifont (8x16, full Unicode), 1=unscii8 (8x8, box drawing and braille, no CJK). Resets to 0 at the start of every canvas.")
	public Object[] setFont(Context context, Arguments args) throws Exception {
		int fontId = args.checkInteger(0);
		// REJECTED, not clamped, and this is the asymmetry with the renderer's handling. Here
		// a bad id is a program's mistake and can still be reported to the program that made
		// it; by the time a command reaches the client there is nobody left to tell, so the
		// renderer falls back to the default instead of refusing to draw.
		if (!V2Wire.isValidFont(fontId)) {
			throw new Exception("unknown font id " + fontId + " (0.." + (V2Wire.FONT_COUNT - 1) + ")");
		}
		synchronized (sceneLock) {
			record(CanvasCommand.of(V2Wire.OP_SET_FONT, fontId));
		}
		return null;
	}

	@Callback(direct = true, doc = "function(fontId:number):number, number -- Cell width and height in pixels for a font. Line height is the cell height; a double-width glyph is two cells wide.")
	public Object[] getFontMetrics(Context context, Arguments args) throws Exception {
		int fontId = args.count() > 0 ? args.checkInteger(0) : V2Wire.FONT_DEFAULT;
		if (!V2Wire.isValidFont(fontId)) {
			throw new Exception("unknown font id " + fontId + " (0.." + (V2Wire.FONT_COUNT - 1) + ")");
		}
		// Programs need this to lay out rows: the cell height is 16 for unifont and 8 for
		// unscii-8, so a hardcoded line pitch is wrong for one of them.
		return new Object[] { Integer.valueOf(FontMetrics.cellWidth(fontId)),
				Integer.valueOf(FontMetrics.glyphHeight(fontId)) };
	}

	@Callback(direct = true, doc = "function(text:string[, fontId:number]):number -- Width of the text in logical units, measured with the given font (default 0).")
	public Object[] getTextWidth(Context context, Arguments args) throws Exception {
		String text = args.checkString(0);
		// Font is an EXPLICIT argument, not a "current font" on this GPU. A stateful current
		// font would let a program measure with one font and draw with another without any
		// error — the two calls go through different paths and nothing would reconcile them.
		int fontId = args.count() > 1 ? args.checkInteger(1) : V2Wire.FONT_DEFAULT;
		if (!V2Wire.isValidFont(fontId)) {
			throw new Exception("unknown font id " + fontId + " (0.." + (V2Wire.FONT_COUNT - 1) + ")");
		}
		return new Object[] { Integer.valueOf(FontMetrics.textWidth(fontId, text)) };
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

	/**
	 * Draw into a canvas by submitting a whole packed command list in one call.
	 *
	 * The immediate-mode callbacks stay hardwired to the display canvas and are NOT
	 * generalised, which is the decision this whole design turns on. The recorder is six
	 * interacting mutable fields — recording, pendingPresent, autopresent, pushDepth,
	 * publishedTailDepth, colour — and that machine has already produced four shipped defects
	 * here: present() not resetting pushDepth, record()'s cap ignoring pendingPresent, the
	 * mode not being persisted, and setResolution needing its own wipe of the depth pair.
	 * Making it per-canvas would multiply that by N and add a new persisted format for it.
	 * Submitting a finished list instead means offscreen canvases add NO server-side mode
	 * state at all: the buffer, colour and transform depth live in the caller's own code.
	 *
	 * The target is an argument rather than a mode, because a call may execute TWICE. OC
	 * charges the call budget BEFORE entering the body, so a refused call is re-invoked
	 * verbatim on the next tick's synchronized replay — with every other computer on the
	 * network having run in between. Any "current target" a retry cannot reconstruct from its
	 * own arguments would land the replayed draw in a stranger's canvas, with both sides
	 * agreeing and nothing detecting it.
	 */
	@Callback(direct = true, limit = 32, doc = "function(canvasId:number, mode:string, commands:string[, epoch:number]):boolean -- Apply a packed command list to a canvas. mode is \"publish\" (replace the frame) or \"append\" (add, with compaction). Returns false when this tick's allowance is spent; pass epoch from getEpoch() to reject a stale handle.")
	public Object[] canvasSubmit(Context context, Arguments args) throws Exception {
		// Everything cheap first, with NO lock and NO state touched — writeRegion's order.
		// A payload that is going to be rejected must be rejected before it can half-apply.
		int canvasId = args.checkInteger(0);
		String mode = args.checkString(1);
		boolean publish;
		if ("publish".equals(mode)) {
			publish = true;
		} else if ("append".equals(mode)) {
			publish = false;
		} else {
			throw new Exception("mode must be \"publish\" or \"append\", got \"" + mode + "\"");
		}
		byte[] payload = args.checkByteArray(2);
		if (payload.length > V2Wire.MAX_SUBMIT_BYTES) {
			throw new Exception("command list too large (" + payload.length + " bytes, max "
					+ V2Wire.MAX_SUBMIT_BYTES + "); submit fewer commands or append in parts");
		}

		// FIRST lock: resolve the target only far enough to learn its command cap, so the decode
		// below can be bounded by it. The byte cap alone does NOT bound command COUNT — the
		// zero-arity ops are one byte each, so a legal 64 KiB payload can declare 65,532
		// commands, sixteen times the default canvas cap, and every one of them would be
		// allocated and scanned before anything noticed. Bounding the decode by the cap the
		// canvas will actually enforce rejects that after four bytes and zero allocation.
		//
		// This resolution is advisory: a co-tenant may free or recreate the canvas before the
		// second lock, so everything is re-checked there. It cannot go wrong in the unsafe
		// direction — a stale cap only ever bounds the decode, never admits a write.
		int commandCap;
		synchronized (sceneLock) {
			requireScene();
			if (args.count() > 3 && args.checkInteger(3) != scene.epoch()) {
				throw new Exception("stale canvas handle: the scene was re-created");
			}
			commandCap = requireSubmittableCanvasLocked(canvasId).canvas.commandCap;
		}

		List<CanvasCommand> commands;
		try {
			commands = opengpu.v2.protocol.BatchCodec.decodeCommandList(payload, commandCap);
		} catch (opengpu.v2.protocol.CodecException e) {
			throw new Exception("malformed command list: " + e.getMessage());
		}
		if (commands.isEmpty()) {
			throw new Exception("command list is empty");
		}
		// The immediate-mode path checks every coordinate; the submit path must too, or it is
		// simply the unchecked door into the same canvas. A NaN would ride the wire, land in
		// every mirror identically (so no divergence detector fires) and poison the replay.
		for (int i = 0; i < commands.size(); i++) {
			CanvasCommand cmd = commands.get(i);
			double[] a = cmd.args;
			for (int j = 0; j < a.length; j++) {
				if (Double.isNaN(a[j]) || Double.isInfinite(a[j])) {
					throw new Exception("command " + (i + 1) + " has a non-finite argument");
				}
			}
			// Font ids are validated HERE as well as in the setFont callback, because this is
			// the other door into the same command list and the decoder only checks argument
			// COUNTS, never their meaning. Rejecting here keeps the rule "a submitted command
			// list is exactly what the callbacks would have produced" — the renderer's clamp
			// is a last resort for old saves, not a licence to skip validation.
			if (cmd.op == V2Wire.OP_SET_FONT && !V2Wire.isValidFont((int) a[0])) {
				throw new Exception("command " + (i + 1) + " selects unknown font id " + (int) a[0]);
			}
		}

		synchronized (sceneLock) {
			requireScene();
			if (args.count() > 3 && args.checkInteger(3) != scene.epoch()) {
				throw new Exception("stale canvas handle: the scene was re-created");
			}
			ResourceInfo res = requireSubmittableCanvasLocked(canvasId);
			// Restate SceneCanvas's cap here rather than letting its IllegalStateException out.
			// Its message names fill()/clear()/present(), which are hardwired to the DISPLAY
			// canvas — so for the only callers that can reach it (submits are refused on the
			// display canvas) the advice is not merely unhelpful, it is impossible to follow.
			// The remedy for a wedged offscreen canvas is a publish, so say that.
			int standing = publish ? 0 : res.canvas.visibleCommands().size();
			if (standing + commands.size() > res.canvas.commandCap) {
				throw new Exception("canvas " + canvasId + " holds " + standing + " of "
						+ res.canvas.commandCap + " commands and cannot take " + commands.size()
						+ " more; submit with \"publish\" to replace the frame instead");
			}
			// Same restatement for the scene-wide standing byte budget. This one bounds what a
			// RESYNC SNAPSHOT carries to every client entering range and what the save writes to
			// disk — a total that nothing bounded before this call existed, because until now no
			// code path could put a command into a non-display canvas.
			long incoming = 0;
			for (CanvasCommand cmd : commands) {
				incoming += cmd.encodedBytes();
			}
			long freed = publish ? res.canvas.encodedBytes() : 0;
			if (scene.standingCommandBytes() - freed + incoming > V2Wire.MAX_STANDING_COMMAND_BYTES) {
				throw new Exception("this scene's canvases already hold "
						+ scene.standingCommandBytes() + " of " + V2Wire.MAX_STANDING_COMMAND_BYTES
						+ " command bytes; publish a smaller frame over a canvas, or free one");
			}
			// Resolve texture refs here for a message that names the id, rather than letting
			// DeltaApplier reject the whole batch later with a generic validation failure.
			for (CanvasCommand cmd : commands) {
				if (cmd.op == V2Wire.OP_DRAW_TEXTURE || cmd.op == V2Wire.OP_DRAW_TEXTURE_SUB) {
					int ref = (int) cmd.args[0];
					ResourceInfo src = scene.state().resources.get(ref);
					if (src == null || src.type != V2Wire.RES_TEXTURE) {
						throw new Exception("drawTexture references unknown texture " + ref);
					}
				}
			}
			if (scene.submitBudgetRemaining() < payload.length) {
				// Exactly writeRegion's shape, and for the reason its comment gives: burning the
				// call budget makes OC re-run this call next tick, transparently to Lua.
				//
				// An earlier draft returned false immediately, reasoning that replaying a whole
				// frame could publish something the program had superseded. That was wrong — the
				// program is BLOCKED on this call and has run no further code, so there is
				// nothing to supersede. It also left a refusal costing the caller nothing, which
				// made the allowance pace no work at all: a refused submit still paid the full
				// decode, and a computer could spend its whole call budget on refusals.
				//
				// consumeCallBudget is a NO-OP during the synchronized replay, so the replay must
				// answer honestly instead of falling through.
				context.consumeCallBudget(Double.MAX_VALUE);
				if (scene.submitBudgetRemaining() < payload.length) {
					return new Object[] { false, "canvas submit allowance spent this tick" };
				}
			}
			if (!scene.submitCanvas(canvasId, commands, publish, payload.length)) {
				return new Object[] { false, "canvas submit allowance spent this tick" };
			}
			chunkDirty = true;
			return new Object[] { true };
		}
	}

	/** The three checks a submit target must pass, in one place so both lock phases agree. */
	private ResourceInfo requireSubmittableCanvasLocked(int canvasId) throws Exception {
		ResourceInfo res = scene.state().resources.get(canvasId);
		if (res == null) {
			throw new Exception("unknown canvas id " + canvasId);
		}
		if (res.type != V2Wire.RES_CANVAS || res.canvas == null) {
			throw new Exception("id " + canvasId + " is a texture, not a canvas; use writeRegion");
		}
		// The display canvas belongs to the recorder, and a submit routes AROUND it. This tick's
		// immediate draws are still sitting in `recording`, and a present() puts a whole frame in
		// `pendingPresent`; flushRecordingLocked then publishes that frame over the submitted
		// one. The call would have returned true and the picture would simply never appear —
		// agreed on identically by server and every mirror, so no divergence check can see it.
		// freeCanvas refuses this id for the same reason. Nothing is lost by refusing: the
		// immediate path already coalesces a tick's draws into ONE append.
		if (canvasId == implicitCanvasRes) {
			throw new Exception("cannot submit to the display canvas; use the drawing calls, "
					+ "or submit to an offscreen canvas and show it with createCanvasNode");
		}
		return res;
	}

	/**
	 * The op alphabet canvasSubmit speaks, keyed by the immediate-mode call it mirrors.
	 *
	 * Without this a program packing a command list would have to hardcode the numeric op ids,
	 * which are wire-format internals — the one part of this API that a caller cannot discover
	 * by any other means. Hardcoding them would also make every packer silently wrong the first
	 * time an op is inserted rather than appended. The arity travels with the id for the same
	 * reason: it is the other half of the packing rule, and splitting them across a doc and a
	 * call is how the two drift.
	 */
	@Callback(direct = true, doc = "function():table -- The canvas ops canvasSubmit accepts: name -> {op = id, args = count}. drawText additionally carries a trailing writeUTF string.")
	public Object[] canvasOps(Context context, Arguments args) throws Exception {
		String[] names = {
				"fill", "plot", "line", "rectangle", "filledRectangle", "triangle",
				"filledTriangle", "oval", "filledOval", "clearRectangle", "drawText",
				"drawTexture", "drawTextureSub", "setColor", "translate", "rotate",
				"rotateAround", "scale", "push", "pop", "origin", "setFont" };
		byte[] ops = {
				V2Wire.OP_FILL, V2Wire.OP_PLOT, V2Wire.OP_LINE, V2Wire.OP_RECT,
				V2Wire.OP_FILL_RECT, V2Wire.OP_TRIANGLE, V2Wire.OP_FILL_TRIANGLE, V2Wire.OP_OVAL,
				V2Wire.OP_FILL_OVAL, V2Wire.OP_CLEAR_RECT, V2Wire.OP_DRAW_TEXT,
				V2Wire.OP_DRAW_TEXTURE, V2Wire.OP_DRAW_TEXTURE_SUB, V2Wire.OP_SET_COLOR,
				V2Wire.OP_TRANSLATE, V2Wire.OP_ROTATE, V2Wire.OP_ROTATE_AROUND, V2Wire.OP_SCALE,
				V2Wire.OP_PUSH, V2Wire.OP_POP, V2Wire.OP_ORIGIN, V2Wire.OP_SET_FONT };
		if (names.length != ops.length) {
			throw new Exception("canvasOps table is malformed");
		}
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
		for (int i = 0; i < names.length; i++) {
			java.util.Map<String, Integer> entry = new java.util.LinkedHashMap<String, Integer>();
			entry.put("op", Integer.valueOf(ops[i]));
			entry.put("args", Integer.valueOf(V2Wire.canvasOpArgCount(ops[i])));
			out.put(names[i], entry);
		}
		// Fail loudly rather than under-report. An op added to V2Wire but not listed here would
		// otherwise be a silent hole: submit accepts it, no program can discover it, and nothing
		// anywhere says so. This is deterministic, so it trips the first time anyone calls it.
		for (int op = 1; V2Wire.canvasOpArgCount(op) >= 0; op++) {
			boolean listed = false;
			for (int i = 0; i < ops.length; i++) {
				listed |= ops[i] == op;
			}
			if (!listed) {
				throw new Exception("canvas op " + op + " is missing from canvasOps");
			}
		}
		return new Object[] { out };
	}

	/**
	 * Version identity, as three independent numbers rather than one.
	 *
	 * The immediate reason a program needs this: it is the only way to tell OpenGPU v2 from the
	 * legacy OCLights2 component, which shares neither this callback nor most of the surface. The
	 * durable reason is feature detection — {@code api >= N} is the test, and it works for a
	 * program written today running against a build shipped later.
	 *
	 * Answers nothing about the scene, so it deliberately takes no lock and does not
	 * {@code requireScene()}: a program must be able to ask what it is talking to before it has
	 * done anything, including on a GPU with no screen bound.
	 */
	@Callback(direct = true, doc = "function():table -- Version identity: {api = number, protocol = number, mod = string}. Branch on `api >= N` for feature detection; protocol is the client/server wire contract and mod is human-facing.")
	public Object[] getVersion(Context context, Arguments args) throws Exception {
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
		out.put("api", Integer.valueOf(API_LEVEL));
		out.put("protocol", Integer.valueOf(V2Wire.PROTOCOL_VERSION));
		out.put("mod", Tags.MOD_VERSION);
		return new Object[] { out };
	}

	/**
	 * The caps a program must pace itself by — the structural bounds, not the live budget.
	 *
	 * Exists because the shipped Lua library was hardcoding two of these ({@code MAX_SUBMIT_BYTES}
	 * and {@code MAX_TEXT_CHARS}), which is the duplicated-wire-constant hazard {@link #canvasOps}
	 * was added to prevent. A library that guesses a cap is wrong the first time the cap moves,
	 * and in the submit case it moved.
	 *
	 * {@code submitBytes} and {@code submitBytesPerTick} are separate keys although one is
	 * currently a multiple of the other, and that is the whole point: collapsing them is exactly
	 * the conflation that made any frame over 64 KiB undeliverable. A caller CHUNKS by the first
	 * and PACES by the second, and those are different questions.
	 *
	 * The per-BATCH submit bound is deliberately absent. It is real and it is enforced, but a
	 * program cannot observe batch boundaries and so cannot act on it; publishing a number whose
	 * only possible use is to be misinterpreted is worse than withholding it. Live remaining
	 * budget is {@link #getSubmitBudget}, which is a different thing again — this is what the
	 * server will ever allow, that is what is left right now.
	 *
	 * Static constants only, so no lock and no scene required.
	 */
	@Callback(direct = true, doc = "function():table -- Structural caps, in bytes unless noted: submitBytes (per canvasSubmit call), submitBytesPerTick (per scene per tick), commandCap (commands per canvas), textChars (per drawText), writeBytes (per writeRegion call), writeBytesPerTick, textureDim (pixels), standingCommandBytes (whole scene). Chunk by submitBytes, pace by submitBytesPerTick.")
	public Object[] getLimits(Context context, Arguments args) throws Exception {
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
		out.put("submitBytes", Integer.valueOf(V2Wire.MAX_SUBMIT_BYTES));
		out.put("submitBytesPerTick", Integer.valueOf(V2Wire.MAX_SUBMIT_BYTES_PER_TICK));
		out.put("commandCap", Integer.valueOf(CANVAS_COMMAND_CAP));
		out.put("textChars", Integer.valueOf(V2Wire.MAX_TEXT_CHARS));
		out.put("writeBytes", Integer.valueOf(V2Wire.MAX_WRITE_REGION_BYTES));
		out.put("writeBytesPerTick", Integer.valueOf(V2Wire.MAX_WRITE_BYTES_PER_TICK));
		out.put("textureDim", Integer.valueOf(V2Wire.MAX_TEXTURE_DIM));
		out.put("standingCommandBytes", Integer.valueOf(V2Wire.MAX_STANDING_COMMAND_BYTES));
		return new Object[] { out };
	}

	@Callback(direct = true, doc = "function():number -- This scene's incarnation epoch. Pass it to canvasSubmit to reject a handle from a previous scene.")
	public Object[] getEpoch(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			return new Object[] { scene.epoch() };
		}
	}

	/**
	 * Live remaining allowance, and the ceiling it is measured against.
	 *
	 * The ceiling rides along as a second return because the first number is meaningless without
	 * it — "12000 left" says nothing until you know whether that is most of the allowance or the
	 * dregs. Appended rather than substituted, so existing callers reading one value are
	 * unaffected. The structural caps live on {@link #getLimits}; this is the only one that
	 * MOVES, which is why it is a separate call and not a key in that table.
	 */
	@Callback(direct = true, doc = "function():number, number -- Bytes of canvasSubmit payload still allowed this tick, and the per-tick ceiling they are measured against.")
	public Object[] getSubmitBudget(Context context, Arguments args) throws Exception {
		synchronized (sceneLock) {
			requireScene();
			return new Object[] { scene.submitBudgetRemaining(),
					Integer.valueOf(V2Wire.MAX_SUBMIT_BYTES_PER_TICK) };
		}
	}

	/**
	 * This scene's wire counters, as a Lua table.
	 *
	 * The measurement splits across two processes: render cost is a client quantity and shows
	 * in the debug overlay, while everything here is produced on the server and is the only
	 * half a program — or a dedicated server — can reach. A program can therefore measure its
	 * own network cost, which is the figure the transport caps should be argued from.
	 *
	 * Read the two averages carefully, because they answer different questions and one of them
	 * is almost always the wrong one: {@code bytesPerBatch} is how big a frame is, while
	 * {@code bytesPerTick} divides by idle ticks too. A program updating once a second has a
	 * large former and a tiny latter, and only the latter says anything about sustained load.
	 */
	@Callback(direct = true, doc = "function():table -- Wire counters for this scene: batches, deltas, bytes, bytesPerTick, bytesPerBatch, maxBatchBytes, bytesSent, idleTicks, heartbeats, snapshots, snapshotBytes, bodies, bodyBytes, maxWatchers. Client-side render cost is not here; it lives in the debug overlay.")
	public Object[] getStats(Context context, Arguments args) throws Exception {
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
		// Read every field INSIDE the lock. Reading them after releasing it would let a tick
		// land mid-table and produce a set of numbers that never described one moment -- a
		// batch counted in `batches` but its bytes missing from `bytes`, say. Nobody could tell
		// from the result, which is exactly why it matters for an instrument.
		synchronized (sceneLock) {
			requireScene();
			opengpu.v2.stats.SceneStats s = sceneStats();
			if (s == null) {
				throw new Exception("this GPU has no sync layer yet; place it and let it tick once");
			}
			out.put("ticks", Long.valueOf(s.ticks));
			out.put("batches", Long.valueOf(s.batches));
			out.put("idleTicks", Long.valueOf(s.idleTicks));
			out.put("deltas", Long.valueOf(s.deltas));
			out.put("bytes", Long.valueOf(s.batchBytes));
			out.put("bytesSent", Long.valueOf(s.batchBytesSent));
			out.put("maxBatchBytes", Long.valueOf(s.batchBytesMax));
			out.put("bytesPerBatch", Double.valueOf(s.meanBatchBytes()));
			out.put("bytesPerTick", Double.valueOf(s.meanBytesPerTick()));
			out.put("heartbeats", Long.valueOf(s.heartbeats));
			out.put("snapshots", Long.valueOf(s.snapshots));
			out.put("snapshotBytes", Long.valueOf(s.snapshotBytes));
			out.put("maxSnapshotBytes", Long.valueOf(s.snapshotBytesMax));
			out.put("bodies", Long.valueOf(s.bodies));
			out.put("bodyBytes", Long.valueOf(s.bodyBytes));
			out.put("maxWatchers", Integer.valueOf(s.watchersMax));
		}
		return new Object[] { out };
	}

	/**
	 * Zero the counters, so a program can measure one window rather than everything since load.
	 *
	 * Deliberately available to Lua: without it the only way to bound a measurement is to
	 * restart the server, and a figure that includes world load is not a figure about the
	 * program that read it.
	 */
	@Callback(direct = true, doc = "function() -- Zero this scene's wire counters, to measure a window.")
	public Object[] resetStats(Context context, Arguments args) throws Exception {
		// Under sceneLock, matching getStats. This runs on an OC machine thread while the
		// server tick thread is incrementing the same fields; zeroing them unsynchronized could
		// interleave with an onBatch and leave bytes counted against a batch total that was
		// reset out from under them.
		synchronized (sceneLock) {
			opengpu.v2.stats.SceneStats s = sceneStats();
			if (s != null) {
				s.reset();
			}
		}
		return null;
	}

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

	@Callback(direct = true, limit = 256, doc = "function(nodeId:number, x:number, y:number[, rotation:number, scaleX:number, scaleY:number, teleport:boolean]) -- Set a node's transform. Rotation is radians; scale defaults to 1. teleport=true snaps instead of interpolating, for a deliberate jump. Refuses the display node -- input reports scene coordinates and is not transformed with it -- except a reset to identity (0,0,0,1,1), which repairs a display node moved by an older build.")
	public Object[] setNodeTransform(Context context, Arguments args) throws Exception {
		int id = args.checkInteger(0);
		double x = args.checkDouble(1), y = args.checkDouble(2);
		double rot = args.count() > 3 ? args.checkDouble(3) : 0.0;
		double sx = args.count() > 4 ? args.checkDouble(4) : 1.0;
		double sy = args.count() > 5 ? args.checkDouble(5) : sx;
		// Clients interpolate a transform change over one server tick, which is right for
		// animation and wrong for a jump — a teleported sprite would crawl to its destination.
		boolean teleport = args.count() > 6 && args.checkBoolean(6);
		requireFinite(x, "x"); requireFinite(y, "y"); requireFinite(rot, "rotation");
		requireFinite(sx, "scaleX"); requireFinite(sy, "scaleY");
		synchronized (sceneLock) {
			requireScene();
			requireNodeLocked(id);
			// THE DISPLAY NODE IS THE COORDINATE SPACE, so it cannot be moved within it.
			//
			// Rendering honours every node's transform, including this one (Canvas2dRenderer's
			// beginNode applies x/y/rot/scale unconditionally). INPUT does not, and correctly so:
			// both paths report SCENE coordinates -- GuiScene.toLogical inverts the letterbox and
			// the FBO size, onSurfaceClick uses resolutionLocked() -- and nothing anywhere
			// inverts a node transform.
			//
			// For an ordinary canvas node that pairing is right and deliberate: the program
			// created the node, chose its transform, and owns the hit-testing. For THIS node it
			// is a trap, because immediate-mode drawing (fill, drawText, filledRectangle...) goes
			// to the display canvas in scene coordinates, so a transform here silently breaks the
			// identity "where I drew is where a click reports" for the surface that DEFINES that
			// space. Nothing in the API hints that the display node even has a transform, and the
			// symptom is a constant offset between the picture and its own input -- with the
			// server and every mirror agreeing perfectly, so no convergence check can see it.
			//
			// Refused rather than supported. Supporting it means inverting translate+rotate+scale
			// on BOTH input paths, handling a non-invertible scale, and making this one node
			// behave unlike every other -- to deliver pan/zoom that a canvas node already
			// provides, with the coupling explicit and the hit-testing already the program's.
			//
			// Visibility is deliberately NOT guarded the same way: hiding this node hides the
			// immediate-mode layer while other canvas nodes keep rendering, and input keeps
			// reporting correct scene coordinates for them. That is coherent and useful. Only the
			// transform decouples drawing from input.
			// IDENTITY IS PERMITTED, so the guard cannot trap a world that is already wrong.
			// Nodes persist, so a save written before this check can hold a moved display node,
			// and a blanket refusal would reject the one call that repairs it — locking in the
			// exact state being prevented. Setting it back to identity is always allowed.
			if (id == implicitCanvasNode
					&& !(x == 0 && y == 0 && rot == 0 && sx == 1 && sy == 1)) {
				throw new Exception("cannot transform the display node: input reports scene"
						+ " coordinates and is not transformed with it, so the picture and its"
						+ " clicks would disagree. Put the content on a canvas node and transform"
						+ " that instead. (Setting it back to identity is allowed.)");
			}
			scene.setTransform(id, x, y, rot, sx, sy, teleport);
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

	/**
	 * Reveal a composed frame: hide one node and show another, indivisibly.
	 *
	 * The problem it solves. A frame too large for one canvasSubmit arrives as several
	 * independent direct calls, each taking and releasing {@link #sceneLock} on its own, while the
	 * batch seals on the server thread at tick END. A seal can therefore land between two chunks
	 * and ship a batch carrying only the first — a destructive publish — so every watcher renders
	 * a half-drawn frame. That is a timing property and no byte allowance can remove it; the
	 * 2026-08-04 cap split made it rare, not impossible.
	 *
	 * The remedy is not to make the frame atomic but to compose it where nothing is looking:
	 * draw into a HIDDEN canvas node over as many calls, ticks and batches as it takes, then call
	 * this. Only the reveal has to be indivisible, and a reveal is two property deltas.
	 * {@code Canvas2dRenderer} skips invisible nodes, so the back buffer costs no client render
	 * work while it fills and its intermediate states are never drawn.
	 *
	 * {@code limit} deliberately MATCHES setNodeVisible rather than halving it for the second
	 * delta. A caller can always express this racily as two setNodeVisible calls; if the safe
	 * path were the more rationed one, programs would route around it and take the tear back.
	 *
	 * Both ids are validated before either delta is staged — see
	 * {@link opengpu.v2.scene.ServerScene#swapVisibility}, which is where the all-or-nothing
	 * property is established and tested. Refusals here change nothing at all.
	 */
	@Callback(direct = true, limit = 256, doc = "function(hideNodeId:number, showNodeId:number[, epoch:number]) -- Atomically hide one node and show another, in one batch. Compose a frame into a hidden canvas node across as many canvasSubmit calls as it needs, then swap it in: the viewer never sees a partial frame. Refuses two equal ids; pass epoch from getEpoch() to reject handles from a previous scene.")
	public Object[] swapVisibility(Context context, Arguments args) throws Exception {
		int hideId = args.checkInteger(0);
		int showId = args.checkInteger(1);
		synchronized (sceneLock) {
			requireScene();
			// Same optional epoch guard canvasSubmit carries, and needed for the same reason.
			// Node ids are scene-scoped and allocated from a counter that RESTARTS when a scene is
			// re-created, so a program holding ids across a re-creation is not holding invalid
			// ids — it is holding ids that now name DIFFERENT nodes. Without this the call
			// succeeds and swaps two strangers, on the server and on every mirror identically, so
			// no convergence check can see it. The Lua wrapper's liveness check cannot cover this
			// either: it only knows what THIS handle did, not that the scene beneath it was
			// replaced.
			//
			// Optional, matching canvasSubmit: a program that never caches ids across a
			// re-creation should not be forced to thread an epoch through every call.
			if (args.count() > 2 && args.checkInteger(2) != scene.epoch()) {
				throw new Exception("stale node handle: the scene was re-created");
			}
			requireNodeLocked(hideId);
			requireNodeLocked(showId);
			scene.swapVisibility(hideId, showId);
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
