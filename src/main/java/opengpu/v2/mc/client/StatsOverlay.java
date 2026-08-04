package opengpu.v2.mc.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import opengpu.v2.stats.RenderStats;

/**
 * F3-style debug overlay for the render counters, toggled with a key.
 *
 * Exists because OpenGPU had NO performance instrumentation at all — the roadmap's "no
 * performance baselines exist" has been true since before the renderer rework, and the caps and
 * strategies since built on top of it were argued rather than measured. The immediate question
 * it answers: node interpolation turned scene re-rendering from "when a batch arrives" (≤20 Hz)
 * into "every frame while anything moves" (60–200 Hz), and nobody has ever seen what that costs.
 *
 * <h2>Rates, not totals</h2>
 * Cumulative counters answer the wrong question for a live overlay: a total that only grows
 * tells you nothing about what the scene is doing right now, which is the entire point of
 * watching it while a program animates. Everything below is sampled over a rolling window and
 * shown per second, with the raw totals available only on the second page.
 *
 * <h2>Why it costs nothing when hidden</h2>
 * The counters themselves are always live — they are long increments and one nanoTime pair per
 * scene render — but this class does no work at all until toggled on, and the sampling only
 * runs while it is visible. An overlay that perturbed the numbers it displays would be worse
 * than no overlay.
 */
public final class StatsOverlay {

	/** Sampling window. Long enough to be steady, short enough to react to a program starting. */
	private static final long WINDOW_NANOS = 500L * 1000L * 1000L;

	private static StatsOverlay instance;

	private final KeyBinding toggle;
	private boolean visible;
	private boolean detailed;

	// Rolling-window sample state.
	private long windowStart;
	private long lastPrePasses, lastFramesWithWork, lastRenders, lastInterpRenders;
	private long lastNanos, lastCommands;
	private long lastUploadBytes, lastUploads;

	// Latest computed rates, held so the overlay draws steady numbers between samples rather
	// than flickering with whatever the instantaneous delta happened to be.
	private double fps, rendersPerSec, interpPct, meanMicros, workPct;
	private double uploadKbPerSec, nanosPerCommand;
	private long maxMicros;

	private StatsOverlay(KeyBinding toggle) {
		this.toggle = toggle;
		this.windowStart = System.nanoTime();
	}

	public static void init(KeyBinding toggle) {
		if (instance == null) {
			instance = new StatsOverlay(toggle);
			cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(instance);
			net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(instance);
		}
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END || toggle == null) {
			return;
		}
		// isPressed() consumes the press, so this must be polled exactly once per tick and
		// nowhere else, or the toggle silently misses every other keypress.
		while (toggle.isPressed()) {
			if (!visible) {
				visible = true;
				detailed = false;
				resetWindow();
			} else if (!detailed) {
				detailed = true;
			} else {
				visible = false;
			}
		}
	}

	private void resetWindow() {
		windowStart = System.nanoTime();
		lastPrePasses = RenderStats.prePasses;
		lastFramesWithWork = RenderStats.framesWithWork;
		lastRenders = RenderStats.sceneRenders;
		lastInterpRenders = RenderStats.interpolationRenders;
		lastNanos = RenderStats.renderNanos;
		lastCommands = RenderStats.commandsReplayed;
		lastUploadBytes = RenderStats.uploadBytes;
		lastUploads = RenderStats.uploads;
	}

	private void sample() {
		long now = System.nanoTime();
		long elapsed = now - windowStart;
		if (elapsed < WINDOW_NANOS) {
			return;
		}
		double seconds = elapsed / 1.0e9;
		long prePasses = RenderStats.prePasses - lastPrePasses;
		long framesWithWork = RenderStats.framesWithWork - lastFramesWithWork;
		long renders = RenderStats.sceneRenders - lastRenders;
		long interp = RenderStats.interpolationRenders - lastInterpRenders;
		long nanos = RenderStats.renderNanos - lastNanos;
		long commands = RenderStats.commandsReplayed - lastCommands;
		long uploadBytes = RenderStats.uploadBytes - lastUploadBytes;

		fps = prePasses / seconds;
		rendersPerSec = renders / seconds;
		interpPct = renders == 0 ? 0.0 : 100.0 * interp / renders;
		workPct = prePasses == 0 ? 0.0 : 100.0 * framesWithWork / prePasses;
		meanMicros = renders == 0 ? 0.0 : nanos / (double) renders / 1000.0;
		nanosPerCommand = commands == 0 ? 0.0 : nanos / (double) commands;
		uploadKbPerSec = uploadBytes / 1024.0 / seconds;
		maxMicros = RenderStats.renderNanosMax / 1000L;   // all-time; labelled as such below

		resetWindow();
	}

	@SubscribeEvent
	public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
		if (!visible || event.type != RenderGameOverlayEvent.ElementType.TEXT) {
			return;
		}
		sample();
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.fontRenderer == null) {
			return;
		}

		List<String> lines = new ArrayList<String>();
		lines.add("§b[OpenGPU]§r render");
		lines.add(String.format("  pre-pass %.0f/s   scene renders %.0f/s", fps, rendersPerSec));
		// The headline number: what fraction of re-renders exist only because a node is still
		// gliding. High here with nothing moving means active() is not going false.
		lines.add(String.format("  interpolation-driven %.0f%%   frames doing work %.0f%%",
				interpPct, workPct));
		// Microseconds, not milliseconds: a scene render under 1 ms truncated to "0 ms", which
		// is every healthy frame. And the worst figure is an all-time high-water mark rather
		// than a windowed one, so it is labelled instead of sitting next to a per-second number
		// as though it were one.
		lines.add(String.format("  render %.0f us mean (worst ever %d us)", meanMicros, maxMicros));
		lines.add(String.format("  %.0f ns/command   upload %.1f KiB/s", nanosPerCommand,
				uploadKbPerSec));

		if (detailed) {
			lines.add("§8  totals since load:§r");
			lines.add(String.format("    pre-passes %d, renders %d (%d interp)",
					RenderStats.prePasses, RenderStats.sceneRenders,
					RenderStats.interpolationRenders));
			lines.add(String.format("    commands replayed %d, uploads %d (%.1f MiB)",
					RenderStats.commandsReplayed, RenderStats.uploads,
					RenderStats.uploadBytes / 1048576.0));
			lines.add(String.format("    textures deferred for budget %dx",
					RenderStats.texturesDeferred));
			appendServerLines(lines);
		}

		int y = 2 + 10 * 12;   // below vanilla's F3 block and Forge's own HUD text list
		for (String line : lines) {
			mc.fontRenderer.drawStringWithShadow(line, 2, y, 0xFFFFFF);
			y += 10;
		}
	}

	/**
	 * Server-side wire counters, but ONLY in single-player.
	 *
	 * The two halves of the measurement live in different processes and normally cannot be read
	 * together — bytes/tick is produced on the server, render cost on the client. An integrated
	 * server shares the JVM, which is the configuration a baseline is actually taken in, so the
	 * numbers can be shown side by side there. On a dedicated server this section is absent
	 * rather than wrong, and the Lua callback is the channel instead.
	 *
	 * These are plain long reads off the server thread. A torn read shows one stale figure for
	 * half a second in a debug overlay, which is an acceptable trade for not taking a lock on
	 * the render thread.
	 */
	private static void appendServerLines(List<String> lines) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || !mc.isSingleplayer()) {
			lines.add("§8  (server counters: single-player only; use gpu.getStats() otherwise)§r");
			return;
		}
		opengpu.v2.mc.server.V2ServerRuntime runtime = opengpu.v2.mc.server.V2ServerRuntime.get();
		if (runtime == null) {
			return;
		}
		lines.add("§8  server, per scene:§r");
		int shown = 0;
		for (String sceneId : runtime.sceneIds()) {
			opengpu.v2.stats.SceneStats s = runtime.statsFor(sceneId);
			if (s == null) {
				continue;
			}
			lines.add(String.format("    %s  %.0f B/tick, %d batches, max %d B, sent %.1f KiB",
					sceneId.length() > 8 ? sceneId.substring(0, 8) : sceneId,
					s.meanBytesPerTick(), s.batches, s.batchBytesMax,
					s.batchBytesSent / 1024.0));
			if (++shown >= 4) {
				lines.add("    ...");
				break;
			}
		}
	}
}
