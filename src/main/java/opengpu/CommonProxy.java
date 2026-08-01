package opengpu;

import java.io.File;

import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;
import opengpu.block.BlockExternalMonitor;
import opengpu.block.BlockGPU;
import opengpu.block.BlockMonitor;
import opengpu.block.BlockTabletTransceiver;
import opengpu.block.tileentity.TileEntityExternalMonitor;
import opengpu.block.tileentity.TileEntityGPU;
import opengpu.block.tileentity.TileEntityMonitor;
import opengpu.block.tileentity.TileEntityTTrans;
import opengpu.item.ItemRAM;
import opengpu.item.ItemTablet;
import opengpu.v2.mc.server.BlockGpu2;
import opengpu.v2.mc.server.BlockScreen2;
import opengpu.v2.mc.server.TileEntityGpu2;
import opengpu.v2.mc.server.TileEntityScreen2;

public class CommonProxy {
	public static int modelID;
	
	public  void registerRenderInfo(){};

	/** Client-side v2 runtime bootstrap; no-op on the dedicated server. */
	public void initV2Client() {}
	
	public void registerBlocks()
	{	
		boolean gpu = false, monitor = false, monitorBig = false, light = false, advancedlight = false, ttrans = false, ram = false, tablet = false;
			OpenGPU.gpu = new BlockGPU(Material.iron);
			
			GameRegistry.registerBlock(OpenGPU.gpu, "OCLGPU");
			GameRegistry.registerTileEntityWithAlternatives(TileEntityGPU.class, Tags.MOD_ID + ":gpu", "GPU", "OCLights3:gpu");
			gpu = true;

			OpenGPU.monitor = new BlockMonitor(Material.iron);
			
			GameRegistry.registerBlock(OpenGPU.monitor, "OCLMonitor");
			GameRegistry.registerTileEntityWithAlternatives(TileEntityMonitor.class, Tags.MOD_ID + ":monitor", "OCLMonitorTE", "OCLights3:monitor");
			
			monitor = true;

			OpenGPU.monitorBig = new BlockExternalMonitor(Material.iron);
			
			GameRegistry.registerBlock(OpenGPU.monitorBig, "OCLBigMonitor");
			GameRegistry.registerTileEntityWithAlternatives(TileEntityExternalMonitor.class, Tags.MOD_ID + ":external_monitor", "OCLBigMonitorTE", "OCLights3:external_monitor");
			
			monitorBig = true;

		/*
			OpenGPU.light = new BlockColorLight(Config.light, Material.iron);
																				
			GameRegistry.registerBlock(OpenGPU.light, "OCLLIGHT");
			GameRegistry.registerTileEntity(TileEntityColorLight.class, "OCLLight");
			light = true;

			OpenGPU.advancedlight = new BlockAdvancedLight(Config.advlight, Material.iron);
			
			GameRegistry.registerBlock(OpenGPU.advancedlight, "OCLADVLIGHT");
			GameRegistry.registerTileEntity(TileEntityAdvancedlight.class, "OCLAdvLight");
			
			advancedlight = true;
		*/
			
			OpenGPU.ttrans = new BlockTabletTransceiver(Material.iron);

			GameRegistry.registerBlock(OpenGPU.ttrans, "OCLTTrans");
			GameRegistry.registerTileEntityWithAlternatives(TileEntityTTrans.class, Tags.MOD_ID + ":tablet_transceiver", "OCLTTransTE", "OCLights3:tablet_transceiver");

			ttrans = true;

			// v2 GPU (Stage A): new block, new TE — coexists with the legacy GPU during
			// the transition; the legacy block set dies at the Stage A cut-over.
			OpenGPU.gpu2 = new BlockGpu2(Material.iron);
			GameRegistry.registerBlock(OpenGPU.gpu2, "gpu_v2");
			GameRegistry.registerTileEntity(TileEntityGpu2.class, Tags.MOD_ID + ":gpu_v2");

			OpenGPU.screen2 = new BlockScreen2(Material.iron);
			GameRegistry.registerBlock(OpenGPU.screen2, "screen_v2");
			GameRegistry.registerTileEntity(TileEntityScreen2.class, Tags.MOD_ID + ":screen_v2");

			OpenGPU.ram = new ItemRAM();
			
			GameRegistry.registerItem(OpenGPU.ram, "OCLRAM");
			
			ram = true;

			OpenGPU.tablet = new ItemTablet();
			
			GameRegistry.registerItem(OpenGPU.tablet, "OCLTab");
			
			tablet = true;
		
		if (Config.Vanilla) {
			registerVanillaRecipes(gpu, monitor, monitorBig, light, advancedlight, ttrans, ram, tablet);
		}
		if (Loader.isModLoaded("IC2") && Config.IC2) {
			registerIC2Recipes(gpu, monitor, monitorBig, light, advancedlight, ttrans, ram, tablet);
		}
	}
	
	private void registerVanillaRecipes(boolean gpu, boolean monitor, boolean monitorBig, boolean light, boolean advancedlight,
			boolean ttrans, boolean ram, boolean tablet) {

		if (gpu) {
			GameRegistry.addRecipe(new ItemStack(OpenGPU.gpu, 1),
					new Object[] { "III", "RGR", "GGG", 'I',
							Items.iron_ingot, 'R', Items.redstone, 'G',
							Items.gold_ingot });
		}
		if (monitor) {
			GameRegistry.addRecipe(new ItemStack(OpenGPU.monitor, 2),
					new Object[] { "III", "RLR", "GGG", 'I',
							Items.iron_ingot, 'R', Items.redstone, 'G',
							Items.gold_ingot, 'L', Blocks.glass_pane });
		}
		if (monitorBig) {
			GameRegistry.addRecipe(new ItemStack(OpenGPU.monitorBig, 8),
					new Object[] { "LLL", "LGL", "LLL", 'G',
							OpenGPU.monitor, 'L', Blocks.glass_pane });
		}
		if (ttrans) {
			GameRegistry.addRecipe(new ItemStack(OpenGPU.ttrans, 1),
					new Object[] { " L ", "LGL", " L ", 'G',
							OpenGPU.monitor, 'L', Items.redstone });
		}
		if (ram) {
			GameRegistry.addRecipe(new ItemStack(OpenGPU.ram, 8),
					new Object[] { "III", "R R", "GGG", 'I', Items.iron_ingot, 'R', Blocks.redstone_block, 'G', Items.gold_ingot, 'L', Blocks.glass_pane });
			
			// register recipes for RAM upgrades item,output,metadata
			for (int i = 0; i < 8; i++) {
				for (int x = 0; x < 8; x++) {
					int total = i + x;
					if (total <= 8 && i != total && x != total) {
						GameRegistry.addShapelessRecipe(new ItemStack( OpenGPU.ram, 1, total + 1), new ItemStack(OpenGPU.ram, 1, i),
								new ItemStack(OpenGPU.ram, 1, x));
					}
				}
			}
		}
		if (tablet) {
			GameRegistry.addRecipe(new ItemStack(OpenGPU.tablet, 2),
					new Object[] { "GIG", "RMR", "GIG", 'I',
							Items.iron_ingot, 'R', Items.redstone, 'G',
							Items.gold_ingot, 'M', OpenGPU.monitorBig });
		}
	}
	
	public void registerIC2Recipes(boolean gpu, boolean monitor,boolean monitorBig, boolean light, boolean advancedlight,boolean ttrans, boolean ram, boolean tablet) {
		// do some stuff to fak over recipes here kthxbai
	}
	
	public File getWorldDir(World world)
	  {
	    return new File(FMLCommonHandler.instance().getMinecraftServerInstance().getFile("."), DimensionManager.getWorld(0).getSaveHandler().getWorldDirectoryName());
	  }
}
