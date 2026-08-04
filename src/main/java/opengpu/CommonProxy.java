package opengpu;

import net.minecraft.block.material.Material;
import cpw.mods.fml.common.registry.GameRegistry;
import opengpu.v2.mc.server.BlockGpu2;
import opengpu.v2.mc.server.BlockScreen2;
import opengpu.v2.mc.server.TileEntityGpu2;
import opengpu.v2.mc.server.TileEntityScreen2;

public class CommonProxy {

	public void registerRenderInfo() {}

	/** Client-side v2 runtime bootstrap; no-op on the dedicated server. */
	public void initV2Client() {}

	/**
	 * BURNED REGISTRY NAMES — do not reclaim any of these.
	 *
	 * The Stage A cut-over deleted the legacy block/item set. Its registration names are now
	 * free, and reclaiming one would be silently destructive rather than merely confusing:
	 * legacy chunk NBT stamped with a tile-entity id would resolve SUCCESSFULLY into whatever
	 * new class took the name and be fed legacy-shaped data. Today those lookups fail loudly
	 * and Minecraft drops the entity, which is the outcome we want.
	 *
	 * Blocks/items: OCLGPU, OCLMonitor, OCLBigMonitor, OCLTTrans, OCLRAM, OCLTab,
	 *               OCLLIGHT, OCLADVLIGHT.
	 * Tile entities: OpenGPU:gpu, OpenGPU:monitor, OpenGPU:external_monitor,
	 *                OpenGPU:tablet_transceiver, and the bare aliases GPU, OCLMonitorTE,
	 *                OCLBigMonitorTE, OCLTTransTE, OCLLight, OCLAdvLight, plus their
	 *                OCLights3:* forms.
	 *
	 * In particular do NOT "tidy up" gpu_v2 -> gpu or screen_v2 -> screen. The _v2 suffix is
	 * load-bearing precisely because it is not one of the names above.
	 */
	public void registerBlocks() {
		OpenGPU.gpu2 = new BlockGpu2(Material.iron);
		GameRegistry.registerBlock(OpenGPU.gpu2, "gpu_v2");
		GameRegistry.registerTileEntity(TileEntityGpu2.class, Tags.MOD_ID + ":gpu_v2");

		OpenGPU.screen2 = new BlockScreen2(Material.iron);
		GameRegistry.registerBlock(OpenGPU.screen2, "screen_v2");
		GameRegistry.registerTileEntity(TileEntityScreen2.class, Tags.MOD_ID + ":screen_v2");
	}
}
