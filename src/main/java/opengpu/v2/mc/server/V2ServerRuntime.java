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
	public void register(TileEntityGpu2 te) {
		hostsByScene.put(te.sceneId(), te);
	}

	/** Unregistered on TE invalidate/chunk-unload; the scene lives on in NBT. */
	public void unregister(TileEntityGpu2 te) {
		TileEntityGpu2 current = hostsByScene.get(te.sceneId());
		if (current == te) {
			hostsByScene.remove(te.sceneId());
		}
	}

	/** True if a live TE currently drives this scene id (guards blind store deletes). */
	public boolean isSceneOwned(String sceneId) {
		TileEntityGpu2 te = hostsByScene.get(sceneId);
		return te != null && !te.isInvalid();
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
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
					te.onResourceRequest(senderUuid, req.resId);
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
			te.evictWatcher(uuid);
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
					host.unsubscribe(uuid);
				}
				continue;
			}
			double dist = distance(player, te);
			if (!subscribed && dist <= SUBSCRIBE_RANGE) {
				host.subscribe(uuid);
			} else if (subscribed && dist > UNSUBSCRIBE_RANGE) {
				host.unsubscribe(uuid);
			}
		}
	}

	private static double distance(EntityPlayer player, TileEntityGpu2 te) {
		double dx = player.posX - (te.xCoord + 0.5);
		double dy = player.posY - (te.yCoord + 0.5);
		double dz = player.posZ - (te.zCoord + 0.5);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
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

	private static EntityPlayerMP findPlayer(String uuid) {
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
