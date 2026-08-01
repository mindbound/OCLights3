package opengpu.v2.mc.client;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import opengpu.OpenGPU;
import opengpu.v2.mc.client.render.SceneRenderer;
import opengpu.v2.mc.net.V2Inbox;
import opengpu.v2.mc.net.V2Net;
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.FrameChunker;
import opengpu.v2.sync.ClientTransport;
import opengpu.v2.sync.MirrorClient;

/**
 * Client-side v2 driver. Owns the {@link MirrorClient}, drains inbound frames on the client
 * tick, and renders used scenes in the RenderTickEvent.START pre-pass (the one hazard-free
 * window under Angelica — surfaces never render lazily; they mark the scene used and draw
 * the cached texture).
 *
 * Thread contract: everything here runs on the client main thread. Netty delivers frames
 * into {@link V2Inbox}; the disconnect event (netty thread) only flips a volatile flag,
 * consumed on the next tick.
 */
public final class V2ClientRuntime {
	public static final int RESYNC_RETRY_TICKS = 100;

	private static final V2ClientRuntime INSTANCE = new V2ClientRuntime();

	private final MirrorClient mirrorClient = new MirrorClient(new FmlClientTransport(), RESYNC_RETRY_TICKS);
	private final FrameChunker.Reassembler reassembler = new FrameChunker.Reassembler();
	private final SceneRenderer renderer = new SceneRenderer();

	/** Scenes surfaces asked for this frame; rendered in the NEXT pre-pass. */
	private final Set<String> usedScenes = new HashSet<String>();
	private final Set<String> renderScenes = new HashSet<String>();

	private static final long CODEC_WARN_INTERVAL_TICKS = 20;

	private long tickCounter;
	// Not Long.MIN_VALUE: the subtraction below would overflow negative and suppress every
	// warning for the session's lifetime.
	private long lastCodecWarnTick = -CODEC_WARN_INTERVAL_TICKS;
	private volatile boolean pendingReset;

	private V2ClientRuntime() {}

	public static void init() {
		FMLCommonHandler.instance().bus().register(INSTANCE);
		// WorldEvent lives on the Forge bus; a dimension change never fires a disconnect.
		MinecraftForge.EVENT_BUS.register(INSTANCE);
	}

	public static V2ClientRuntime get() {
		return INSTANCE;
	}

	public MirrorClient mirrors() {
		return mirrorClient;
	}

	public SceneRenderer renderer() {
		return renderer;
	}

	/** Called by any surface (GUI, TESR, item) that wants this scene rendered. */
	public void markUsed(String sceneId) {
		if (sceneId != null) {
			usedScenes.add(sceneId);
		}
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		if (pendingReset) {
			pendingReset = false;
			resetNow();
		}
		if (Minecraft.getMinecraft().theWorld == null) {
			return;
		}
		tickCounter++;
		drainInbound();
		mirrorClient.tick(tickCounter);
	}

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		if (event.phase != TickEvent.Phase.START) {
			return;
		}
		if (pendingReset) {
			pendingReset = false;
			resetNow();
			return;
		}
		if (Minecraft.getMinecraft().theWorld == null) {
			return;
		}
		// Frames can arrive between ticks; pick them up so the pre-pass renders fresh state.
		drainInbound();
		renderScenes.clear();
		renderScenes.addAll(usedScenes);
		usedScenes.clear();
		renderer.prePass(mirrorClient, renderScenes);
	}

	@SubscribeEvent
	public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
		// Netty thread: defer the actual teardown to the main thread.
		pendingReset = true;
	}

	/**
	 * Dimension change / world swap. Without this, mirrors for scenes left behind never
	 * decay (the server unsubscribes silently, so no traffic arrives and needsResync never
	 * latches) and their FBOs leak VRAM until disconnect. Deferred to the main thread so
	 * the GL teardown happens in the established window.
	 */
	@SubscribeEvent
	public void onWorldUnload(WorldEvent.Unload event) {
		if (event.world != null && event.world.isRemote) {
			pendingReset = true;
		}
	}

	private void resetNow() {
		mirrorClient.clear();
		reassembler.clear();
		V2Inbox.clearClientQueue();
		usedScenes.clear();
		renderScenes.clear();
		renderer.disposeAll();
		tickCounter = 0;
		lastCodecWarnTick = -CODEC_WARN_INTERVAL_TICKS;
	}

	private void drainInbound() {
		byte[] frame;
		while ((frame = V2Inbox.pollToClient()) != null) {
			try {
				byte[] envelope = reassembler.accept("server", frame);
				if (envelope != null) {
					mirrorClient.onMessage(envelope);
				}
			} catch (CodecException e) {
				warnCodec("v2 inbound: " + e.getMessage());
			}
		}
	}

	private void warnCodec(String message) {
		if (tickCounter - lastCodecWarnTick >= CODEC_WARN_INTERVAL_TICKS) {
			lastCodecWarnTick = tickCounter;
			OpenGPU.logger.warn(message);
		}
	}

	private static final class FmlClientTransport implements ClientTransport {
		@Override
		public void sendToServer(byte[] envelope) {
			int transferId = nextTransferId++;
			for (byte[] frame : FrameChunker.split(transferId, envelope, FrameChunker.DEFAULT_CHUNK_SIZE)) {
				V2Net.channel.sendToServer(new V2Net.FrameToServer(frame));
			}
		}

		private static int nextTransferId;
	}
}
