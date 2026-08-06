package opengpu.v2.mc.server;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.DimensionManager;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import opengpu.OpenGPU;
import opengpu.v2.mc.net.V2Inbox;
import opengpu.v2.mc.net.V2Net;
import opengpu.v2.persist.DirectoryResourceStore;
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.FrameChunker;
import opengpu.v2.protocol.MessageCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.sync.SceneHost;
import opengpu.v2.sync.SceneTransport;

/**
 * Server-side v2 driver. Owns the scene-host registry (populated by GPU tile entities), the
 * per-save resource store, the inbound C->S dispatch, and the per-tick pump. Everything v2
 * on the server runs on the server tick thread under each TE's scene lock — inbound netty
 * frames are queued by {@link V2Net} and drained here.
 *
 * Subscription policy (Stage A): proximity with hysteresis — players within
 * {@link #SUBSCRIBE_RANGE} of a GPU subscribe, and unsubscribe only beyond
 * {@link #UNSUBSCRIBE_RANGE}, re-evaluated every {@link #POLICY_INTERVAL_TICKS}. The design's
 * chunk-watch discipline replaces this when surfaces arrive; range-gating a GUI viewer works
 * identically either way.
 */
public final class V2ServerRuntime {
	public static final double SUBSCRIBE_RANGE = 64.0;
	public static final double UNSUBSCRIBE_RANGE = 96.0;
	public static final int POLICY_INTERVAL_TICKS = 20;

	public static final int HEARTBEAT_INTERVAL_TICKS = 40;
	public static final int SNAPSHOT_MIN_INTERVAL_TICKS = 100;
	public static final int BODIES_PER_WATCHER_PER_TICK = 4;

	private static final V2ServerRuntime INSTANCE = new V2ServerRuntime();

	/** Server-bound traffic is only resync/resource requests — single-chunk, tens of bytes. */
	private static final int INBOUND_TRANSFERS_PER_SENDER = 2;
	private static final long INBOUND_BYTES_PER_SENDER = 64 * 1024;
	private static final long CODEC_WARN_INTERVAL_TICKS = 20;

	private final Map<String, TileEntityGpu2> hostsByScene = new LinkedHashMap<String, TileEntityGpu2>();
	// Tiny caps per FrameChunker's directional contract: the default (client-scale) caps
	// would let one hostile client park hundreds of MB of incomplete transfers until logout.
	private final FrameChunker.Reassembler reassembler =
			new FrameChunker.Reassembler(INBOUND_TRANSFERS_PER_SENDER, INBOUND_BYTES_PER_SENDER);
	private final FmlServerTransport transport = new FmlServerTransport();
	private DirectoryResourceStore store;
	private long tickCounter;
	private int transferIdCounter;
	// Not Long.MIN_VALUE: `tickCounter - lastCodecWarnTick` would overflow negative and
	// suppress every warning forever. -interval makes the first warning fire immediately.
	private long lastCodecWarnTick = -CODEC_WARN_INTERVAL_TICKS;

	private V2ServerRuntime() {}

	public static void init() {
		FMLCommonHandler.instance().bus().register(INSTANCE);
	}

	public static V2ServerRuntime get() {
		return INSTANCE;
	}

	public SceneTransport transport() {
		return transport;
	}

	public long currentTick() {
		return tickCounter;
	}

	/**
	 * The per-save out-of-band resource store, rooted inside the current save. Created
	 * lazily on first use after the save is available; closed (flushing pending writes) on
	 * server stop.
	 */
	public synchronized DirectoryResourceStore store() {
		if (store == null) {
			File root = new File(DimensionManager.getCurrentSaveRootDirectory(), "opengpu/scenes");
			if (!root.isDirectory() && !root.mkdirs()) {
				OpenGPU.logger.warn("Could not create v2 resource store at " + root);
			}
			store = new DirectoryResourceStore(root);
		}
		return store;
	}

	/** Registered by the owning TE once its scene + host exist (first server tick). */
	/**
	 * Synchronized because the debug overlay reads this map from the CLIENT RENDER THREAD in
	 * single-player, while chunk load/unload writes it from the server thread.
	 *
	 * The readers were synchronized first and the writers were not, which made the locking
	 * vacuous: a monitor only excludes other holders of it. The render thread could then
	 * iterate a LinkedHashMap mid-structural-modification, and a ConcurrentModificationException
	 * there is not a wrong number — FML's EventBus re-propagates it out of the overlay dispatch
	 * and the client dies to a crash report. Both call sites are outside sceneLock, so taking
	 * this monitor here introduces no lock-order inversion.
	 */
	public synchronized void register(TileEntityGpu2 te) {
		hostsByScene.put(te.sceneId(), te);
	}

	/** Unregistered on TE invalidate/chunk-unload; the scene lives on in NBT. */
	public synchronized void unregister(TileEntityGpu2 te) {
		TileEntityGpu2 current = hostsByScene.get(te.sceneId());
		if (current == te) {
			hostsByScene.remove(te.sceneId());
		}
	}

	/** The GPU driving a scene, or null when its chunk is not loaded. */
	public TileEntityGpu2 gpuForScene(String sceneId) {
		return hostsByScene.get(sceneId);
	}

	/**
	 * Scene ids with a live host, for the debug overlay.
	 *
	 * A copy taken under the monitor that {@link #register}/{@link #unregister} also hold. Both
	 * halves are load-bearing: the copy stops the caller retaining a live view, and the shared
	 * monitor stops the copy itself racing a structural modification. Copying alone would not
	 * help, which is what the first version of this comment wrongly claimed.
	 */
	public synchronized java.util.List<String> sceneIds() {
		return new ArrayList<String>(hostsByScene.keySet());
	}

	/** Wire counters for one scene, or null if it has no host or no sync layer yet. */
	public synchronized opengpu.v2.stats.SceneStats statsFor(String sceneId) {
		TileEntityGpu2 te = hostsByScene.get(sceneId);
		return te == null ? null : te.sceneStats();
	}

	/** True if a live TE currently drives this scene id (guards blind store deletes). */
	public boolean isSceneOwned(String sceneId) {
		TileEntityGpu2 te = hostsByScene.get(sceneId);
		return te != null && !te.isInvalid();
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.START) {
			// Grant write allowances BEFORE machines run this tick, so an OC synchronized
			// replay (a call that burned its budget last tick) always finds a fresh budget.
			for (TileEntityGpu2 te : new ArrayList<TileEntityGpu2>(hostsByScene.values())) {
				te.serverBeginTick(tickCounter + 1, V2Wire.MAX_WRITE_BYTES_PER_TICK);
			}
			return;
		}
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		tickCounter++;
		drainInbound();
		boolean policyTick = tickCounter % POLICY_INTERVAL_TICKS == 0;
		// Copy: a TE can unregister (chunk unload) from inside the loop via world interactions.
		List<TileEntityGpu2> tes = new ArrayList<TileEntityGpu2>(hostsByScene.values());
		for (TileEntityGpu2 te : tes) {
			te.serverPump(tickCounter, policyTick);
		}
	}

	private void drainInbound() {
		V2Inbox.ServerBound entry;
		while ((entry = V2Inbox.pollToServer()) != null) {
			try {
				byte[] envelope = reassembler.accept(entry.senderUuid, entry.frame);
				if (envelope != null) {
					dispatch(entry.senderUuid, envelope);
				}
			} catch (CodecException e) {
				warnCodec("v2 inbound from " + entry.senderUuid + ": " + e.getMessage());
			}
		}
	}

	private void dispatch(String senderUuid, byte[] envelope) throws CodecException {
		byte kind = MessageCodec.kindOf(envelope);
		byte[] payload = MessageCodec.payloadOf(envelope);
		switch (kind) {
			case MessageCodec.MSG_RESYNC_REQUEST: {
				MessageCodec.ResyncRequest req = MessageCodec.decodeResyncRequest(payload);
				TileEntityGpu2 te = hostsByScene.get(req.sceneId);
				if (te != null) {
					te.onResyncRequest(senderUuid);
				}
				break;
			}
			case MessageCodec.MSG_RESOURCE_REQUEST: {
				MessageCodec.ResourceRequest req = MessageCodec.decodeResourceRequest(payload);
				TileEntityGpu2 te = hostsByScene.get(req.sceneId);
				if (te != null) {
					te.onResourceRequest(senderUuid, req.epoch, req.resId);
				}
				break;
			}
			case MessageCodec.MSG_INPUT: {
				MessageCodec.Input input = MessageCodec.decodeInput(payload);
				TileEntityGpu2 te = hostsByScene.get(input.sceneId);
				if (te != null) {
					EntityPlayer player = findPlayer(senderUuid);
					if (player != null) {
						te.onInput(senderUuid, player, input);
					}
				}
				break;
			}
			default:
				// Only request kinds are legal server-bound; anything else is protocol noise.
				warnCodec("v2 inbound: unexpected kind " + kind + " from " + senderUuid);
		}
	}

	private void warnCodec(String message) {
		// One warning per second at most: malformed traffic must not become a log flood.
		if (tickCounter - lastCodecWarnTick >= CODEC_WARN_INTERVAL_TICKS) {
			lastCodecWarnTick = tickCounter;
			OpenGPU.logger.warn(message);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		String uuid = event.player.getUniqueID().toString();
		for (TileEntityGpu2 te : hostsByScene.values()) {
			// The player object is passed along because eviction now EMITS the pending
			// releases rather than merely forgetting them, and a checked signal needs someone
			// to check. This event is the last point at which they can still be named.
			//
			// Guarded PER HOST. Eviction used to be map bookkeeping that could not throw; it
			// now reaches into the OC network, and one bad host must not stop every later host
			// in this map from evicting the same departing player.
			try {
				te.evictWatcher(uuid, event.player);
			} catch (RuntimeException e) {
				OpenGPU.logger.warn("v2: evicting " + uuid + " from a scene failed", e);
			}
		}
		reassembler.evict(uuid);
	}

	/** Called from the mod's ServerStoppedEvent hook: flush the store, drop all state. */
	public synchronized void onServerStopped() {
		hostsByScene.clear();
		V2Inbox.clearServerQueue();
		reassembler.clear();
		if (store != null) {
			store.close();
			store = null;
		}
		tickCounter = 0;
		// The INSTANCE is static and survives an integrated-server stop/start cycle.
		lastCodecWarnTick = -CODEC_WARN_INTERVAL_TICKS;
	}

	/**
	 * Subscription policy for one host TE, run on policy ticks under the scene lock.
	 * subscribe() is guarded by isSubscribed so re-evaluation never re-sends the
	 * subscribe-time heartbeat to existing watchers.
	 */
	void applyProximityPolicy(TileEntityGpu2 te, SceneHost host) {
		MinecraftServer server = MinecraftServer.getServer();
		if (server == null) {
			return;
		}
		@SuppressWarnings("unchecked")
		List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
		for (EntityPlayerMP player : players) {
			String uuid = player.getUniqueID().toString();
			boolean subscribed = host.isSubscribed(uuid);
			if (player.worldObj != te.getWorldObj() || player.dimension != te.getWorldObj().provider.dimensionId) {
				if (subscribed) {
					// Release before unsubscribing, while the player is still resolvable and
					// the surface still reachable. Once unsubscribed, onInput's subscription
					// gate rejects everything they send, so this is the last moment anything
					// can end a gesture they are still holding — and a player who changed
					// dimension mid-press is never sending a release themselves.
					te.evictWatcher(uuid, player);
					host.unsubscribe(uuid);
				}
				continue;
			}
			// Distance to the nearest *surface* of this scene, not just the GPU: a screen
			// on a far wall must keep its viewers subscribed even when the GPU is out of
			// range (and the GUI viewer keeps the GPU itself relevant).
			double dist = distance(player, te.xCoord, te.yCoord, te.zCoord);
			int[] screen = te.boundScreenPosition();
			if (screen != null) {
				dist = Math.min(dist, distance(player, screen[0], screen[1], screen[2]));
			}
			if (!subscribed && dist <= SUBSCRIBE_RANGE) {
				host.subscribe(uuid);
			} else if (subscribed && dist > UNSUBSCRIBE_RANGE) {
				// Same as the dimension case above: this is the last point at which a held
				// gesture can be ended. A comment on the reach gate used to credit "the
				// subscription gate" with catching a player who leaves without releasing —
				// which was false until this line existed, because unsubscribing merely made
				// that gate start rejecting their events rather than ending anything.
				te.evictWatcher(uuid, player);
				host.unsubscribe(uuid);
			}
		}
	}

	private static double distance(EntityPlayer player, int x, int y, int z) {
		double dx = player.posX - (x + 0.5);
		double dy = player.posY - (y + 0.5);
		double dz = player.posZ - (z + 0.5);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** A screen block was broken: tell whichever GPU was driving it. */
	public void onScreenRemoved(TileEntityScreen2 screen) {
		String driver = screen.driverAddress();
		if (driver == null || screen.node() == null || screen.node().address() == null) {
			return;
		}
		String screenAddress = screen.node().address();
		for (TileEntityGpu2 te : hostsByScene.values()) {
			// Guarded per host for the same reason as the logout loop: this notification now
			// emits pending releases through the OC network, and a throw from one host would
			// leave every host after it still believing the screen exists.
			try {
				te.onScreenRemoved(screenAddress);
			} catch (RuntimeException e) {
				OpenGPU.logger.warn("v2: notifying a GPU of screen " + screenAddress
						+ " removal failed", e);
			}
		}
	}

	/**
	 * Outbound transport: chunk the envelope and send each frame in order on the tick
	 * thread. Netty per-connection ordering + in-order sends here = the strict per-watcher
	 * FIFO the SceneTransport contract requires. A missing player is simply skipped —
	 * PlayerLoggedOutEvent handles the eviction.
	 */
	private final class FmlServerTransport implements SceneTransport {
		@Override
		public void sendToWatcher(String watcherKey, byte[] envelope) {
			EntityPlayerMP player = findPlayer(watcherKey);
			if (player == null) {
				return;
			}
			List<byte[]> frames = FrameChunker.split(nextTransferId(), envelope, FrameChunker.DEFAULT_CHUNK_SIZE);
			for (byte[] frame : frames) {
				V2Net.channel.sendTo(new V2Net.FrameToClient(frame), player);
			}
		}
	}

	private int nextTransferId() {
		return transferIdCounter++;
	}

	/** Package-private so the input router's release flush resolves watchers the same way. */
	static EntityPlayerMP findPlayer(String uuid) {
		MinecraftServer server = MinecraftServer.getServer();
		if (server == null) {
			return null;
		}
		@SuppressWarnings("unchecked")
		List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
		for (EntityPlayerMP player : players) {
			if (uuid.equals(player.getUniqueID().toString())) {
				return player;
			}
		}
		return null;
	}
}
