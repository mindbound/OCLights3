package opengpu;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLMissingMappingsEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import opengpu.network.PacketHandler;
import opengpu.network.PacketHandler.PacketMessage;
import opengpu.v2.mc.net.V2Net;
import opengpu.v2.mc.server.V2ServerRuntime;

@Mod(modid = Tags.MOD_ID,
     name = Tags.MOD_NAME,
     version = Tags.MOD_VERSION,
     dependencies = "required-after:OpenComputers",
     acceptedMinecraftVersions = "1.7.10")
public class OpenGPU {
	@Mod.Instance(Tags.MOD_ID)
	public static OpenGPU instance;
	
	@SidedProxy(serverSide = Tags.ROOT_PKG + ".CommonProxy", clientSide = Tags.ROOT_PKG + ".client.ClientProxy")
	public static CommonProxy proxy;
	
	public static Block gpu,monitor,monitorBig,light,advancedlight,ttrans;
	public static Block gpu2, screen2;
	public static Item ram,tablet;
	public static Logger logger;
	
	public static SimpleNetworkWrapper network = new SimpleNetworkWrapper(Tags.MOD_ID);
	
	public static CreativeTabs ocltab = new CreativeTabs(Tags.MOD_ID) {
		@Override
		public ItemStack getIconItemStack() {
			this.getTranslatedTabLabel();
			return new ItemStack(tablet, 1, 0);
		}

		@Override
		public Item getTabIconItem() {
			return tablet;
		}
	};

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		// Config.parse logs through OpenGPU.logger on malformed values — assign it first.
		logger = event.getModLog();
		Config.loadConfig(new Configuration(migrateLegacyConfig(event)));
		
		proxy.registerBlocks();

		V2Net.init();
		V2ServerRuntime.init();
		proxy.initV2Client();

		logger.log(Level.INFO, "STANDING BY");
	}

	@Mod.EventHandler
	public void serverStopped(FMLServerStoppedEvent event) {
		V2ServerRuntime.get().onServerStopped();
	}

	/**
	 * Earlier identities suggested differently-named config files (OCLights3.cfg, and
	 * OCLights2.cfg before that). Copy the newest legacy file into place on first launch
	 * so user settings survive the rename; copy rather than move so rolling back to an
	 * older jar still finds its own config.
	 */
	private static File migrateLegacyConfig(FMLPreInitializationEvent event) {
		File suggested = event.getSuggestedConfigurationFile();
		if (!suggested.exists()) {
			for (String legacy : new String[] { "OCLights3.cfg", "OCLights2.cfg" }) {
				File old = new File(event.getModConfigurationDirectory(), legacy);
				if (old.exists()) {
					try {
						Files.copy(old.toPath(), suggested.toPath());
						logger.info("Migrated legacy config " + legacy + " to " + suggested.getName());
					} catch (IOException e) {
						logger.warn("Could not migrate legacy config " + legacy + "; using defaults", e);
					}
					break;
				}
			}
		}
		return suggested;
	}

	@Mod.EventHandler
	public void missingMappings(FMLMissingMappingsEvent event) {
		// Worlds created before the OpenGPU rename store block/item ids under the
		// "OCLights2" domain (original mod) or "OCLights3" (interim rename; same
		// registration names). The disabled light blocks are intentionally not remapped.
		for (FMLMissingMappingsEvent.MissingMapping mapping : event.getAll()) {
			if (!mapping.name.startsWith("OCLights2:") && !mapping.name.startsWith("OCLights3:")) {
				continue;
			}
			String name = mapping.name.substring(mapping.name.indexOf(':') + 1);
			Block block = null;
			Item item = null;
			if (name.equals("OCLGPU")) block = gpu;
			else if (name.equals("OCLMonitor")) block = monitor;
			else if (name.equals("OCLBigMonitor")) block = monitorBig;
			else if (name.equals("OCLTTrans")) block = ttrans;
			else if (name.equals("OCLRAM")) item = ram;
			else if (name.equals("OCLTab")) item = tablet;
			if (mapping.type == GameRegistry.Type.BLOCK && block != null) {
				mapping.remap(block);
			} else if (mapping.type == GameRegistry.Type.ITEM) {
				if (item == null && block != null) {
					item = Item.getItemFromBlock(block);
				}
				if (item != null) {
					mapping.remap(item);
				}
			}
		}
	}

	@Mod.EventHandler
	public void load(FMLPostInitializationEvent event) {
		proxy.registerRenderInfo();
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiHandler());
        network.registerMessage(PacketHandler.class, PacketMessage.class, 0, Side.CLIENT);
        network.registerMessage(PacketHandler.class, PacketMessage.class, 1, Side.SERVER);
	}

	public static void debug(String debugmsg) {
		if (Config.DEBUGS) {
			logger.log(Level.INFO, debugmsg);
		}
	}
}
