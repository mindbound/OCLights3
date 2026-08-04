package opengpu.client;

import cpw.mods.fml.client.registry.ClientRegistry;
import opengpu.CommonProxy;

public class ClientProxy extends CommonProxy {

	@Override
	public void initV2Client() {
		opengpu.v2.mc.client.V2ClientRuntime.init();

		// Debug overlay for the render counters. Unbound by default (key code 0): it is a
		// developer tool, and claiming a key on every player's keyboard to show frame timings
		// would be rude. Bind it in Controls under "OpenGPU" when measuring.
		net.minecraft.client.settings.KeyBinding toggle =
				new net.minecraft.client.settings.KeyBinding("key.opengpu.stats", 0, "key.categories.opengpu");
		cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(toggle);
		opengpu.v2.mc.client.StatsOverlay.init(toggle);
	}

	/**
	 * The v2 screen's TESR bind — the ONLY thing this method still does, and it must keep doing
	 * it. Deleting the method wholesale during the cut-over would compile, launch, boot clean
	 * and leave every screen in the world rendering nothing: the base implementation in
	 * CommonProxy is an empty no-op, so nothing anywhere would complain. Neither the test suite
	 * (v2 logic only, no Minecraft classes) nor CI (server-side smoke, no client) can see it.
	 */
	@Override
	public void registerRenderInfo() {
		ClientRegistry.bindTileEntitySpecialRenderer(opengpu.v2.mc.server.TileEntityScreen2.class,
				new opengpu.v2.mc.client.render.ScreenRenderer());
	}
}
