package ds.mods.OCLights2;

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
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import ds.mods.OCLights2.network.PacketHandler;
import ds.mods.OCLights2.network.PacketHandler.PacketMessage;

@Mod(modid = Tags.MOD_ID,
     name = Tags.MOD_NAME,
     version = Tags.MOD_VERSION,
     dependencies = "required-after:OpenComputers",
     acceptedMinecraftVersions = "1.7.10")
public class OCLights2 {
	@Mod.Instance(Tags.MOD_ID)
	public static OCLights2 instance;
	
	@SidedProxy(serverSide = Tags.ROOT_PKG + ".CommonProxy", clientSide = Tags.ROOT_PKG + ".client.ClientProxy")
	public static CommonProxy proxy;
	
	public static Block gpu,monitor,monitorBig,light,advancedlight,ttrans;
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
		Config.loadConfig(new Configuration(event.getSuggestedConfigurationFile()));
		logger = event.getModLog();
		
		proxy.registerBlocks();
        
		logger.log(Level.INFO, "STANDING BY");
	}

	@Mod.EventHandler
	public void missingMappings(FMLMissingMappingsEvent event) {
		// Worlds created before the OCLights3 rename store block/item ids under the
		// "OCLights2" domain. The disabled light blocks are intentionally not remapped.
		for (FMLMissingMappingsEvent.MissingMapping mapping : event.getAll()) {
			if (!mapping.name.startsWith("OCLights2:")) {
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
