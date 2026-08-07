package opengpu;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLMissingMappingsEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
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
	
	/**
	 * The resource domain for this mod's assets — {@code assets/opengpu/}.
	 *
	 * Lowercase and deliberately NOT {@code Tags.MOD_ID}, which is mixed-case "OpenGPU";
	 * resource domains are conventionally lowercase and the two are unrelated identifiers.
	 * Renamed from "oclights" on 2026-08-04 with the rest of the OCLights inheritance.
	 *
	 * A constant because the domain is referenced from block icon registration, the font
	 * ResourceLocation and the loot filesystem mount, and every one of those fails at RUNTIME
	 * ONLY — a wrong icon domain is a missing-texture placeholder and a wrong filesystem domain
	 * is a silently null mount. Neither the compiler nor any test can see either.
	 *
	 * {@link opengpu.v2.mc.FontMetrics} deliberately does NOT use this constant: it is
	 * Minecraft-free so the server can compute text metrics headlessly, and referencing this
	 * class would drag Block/Item/CreativeTabs onto its classpath. It carries its own copy with
	 * a pointer back here.
	 */
	public static final String ASSET_DOMAIN = "opengpu";

	public static Block gpu2, screen2;
	public static Logger logger;
	
	/**
	 * The creative tab both v2 blocks attach to.
	 *
	 * Its icon is resolved from whatever is actually registered rather than from a named field,
	 * because a null icon here is a hard client crash and not a blank square: opening the tab
	 * takes RenderItem into {@code ItemStack.getItemDamage()}, which dereferences the Item.
	 * The tab used to point at the legacy tablet, so deleting that item would have crashed
	 * every creative-mode client while compiling cleanly, passing every test and passing a
	 * server-side CI smoke — none of those channels open a creative menu.
	 */
	public static CreativeTabs ocltab = new CreativeTabs(Tags.MOD_ID) {
		private Item icon() {
			// gpu2 is the mod's identity; fall back rather than assume any single registration
			// survived, and default to a vanilla item so the icon can never be null.
			if (gpu2 != null) {
				Item fromBlock = Item.getItemFromBlock(gpu2);
				if (fromBlock != null) {
					return fromBlock;
				}
			}
			if (screen2 != null) {
				Item fromScreen = Item.getItemFromBlock(screen2);
				if (fromScreen != null) {
					return fromScreen;
				}
			}
			return net.minecraft.init.Items.redstone;
		}

		@Override
		public ItemStack getIconItemStack() {
			this.getTranslatedTabLabel();
			return new ItemStack(icon(), 1, 0);
		}

		@Override
		public Item getTabIconItem() {
			return icon();
		}
	};

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		logger = event.getModLog();

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

	// There is deliberately no config any more. Config.java was 100% legacy — monitor sizes,
	// recipe toggles, light block ids — and nothing in v2 ever read it, so the cut-over drops
	// the file rather than leaving an empty one that implies settings exist. The rename-era
	// migration that copied OCLights3.cfg/OCLights2.cfg into place goes with it. Add a config
	// back when something actually needs one.

	/**
	 * Registration names of the legacy block/item set, which the Stage A cut-over deletes.
	 *
	 * The two light entries were never registered (their block in CommonProxy is commented
	 * out), so they can only appear in an OCLights2-era world — covering them costs nothing.
	 */
	private static final java.util.Set<String> ABANDONED_REGISTRY_NAMES =
			new java.util.HashSet<String>(java.util.Arrays.asList(
					"OCLGPU", "OCLMonitor", "OCLBigMonitor", "OCLTTrans", "OCLRAM", "OCLTab",
					"OCLLIGHT", "OCLADVLIGHT"));

	/**
	 * Abandon the deleted legacy block/item ids rather than letting FML treat them as damage.
	 *
	 * This is the single method that decides whether an existing world still loads after the
	 * cut-over, and none of its failure modes are caught by javac, by the test suite (all of
	 * which is v2-only) or by CI. The fields it used to remap onto are plain {@code Block} /
	 * {@code Item} in this class, so deleting their registrations leaves them silently null and
	 * still compiling — {@code light} and {@code advancedlight} are already in exactly that
	 * state today.
	 *
	 * <h2>Why the names are abandoned and not remapped</h2>
	 * OpenGPU is a separate mod, not an updated OCLights2, so it makes no promise to carry old
	 * worlds. More sharply: remapping these onto {@code gpu_v2}/{@code screen_v2} would be
	 * actively destructive. {@code GameData.processIdRematches} requires the re-registered id to
	 * equal the old one, and in any world that already knows {@code gpu_v2} it cannot, so the
	 * load aborts with "the world state is utterly corrupted". That trap passes on a test world
	 * predating gpu_v2 and fails on every current one.
	 *
	 * <h2>Why the domain list is wider than it looks</h2>
	 * These ids live under THREE domains: "OpenGPU:" for any world saved by a current jar, plus
	 * the pre-rename "OCLights3:" and "OCLights2:". The version of this method before the
	 * cut-over handled only the latter two and {@code continue}d on everything else — which
	 * would have left every current world's own ids unhandled. Note also that FML writes the
	 * whole registry snapshot into level.dat, so a world carries these ids whether or not a
	 * block was ever placed.
	 *
	 * Unhandled is not a warning: singleplayer prompts and force-backs-up the world, a
	 * dedicated server BLOCKS on a console query, and a client joining a server is refused the
	 * handshake outright with no prompt at all.
	 *
	 * {@code ignore()} rather than {@code warn()}: both load, but warn() makes FML log its
	 * "may cause world breakage" banner on every subsequent load, forever, for a condition that
	 * is permanent and intended. Matched by name rather than by domain so that a genuinely
	 * missing {@code gpu_v2} still surfaces loudly instead of being swallowed.
	 */
	@Mod.EventHandler
	public void missingMappings(FMLMissingMappingsEvent event) {
		// getAll(), not get(): get() filters to this mod's own domain and would silently skip
		// the OCLights2:/OCLights3: entries in a pre-rename world.
		for (FMLMissingMappingsEvent.MissingMapping mapping : event.getAll()) {
			int colon = mapping.name.indexOf(':');
			if (colon < 0) {
				continue;
			}
			String domain = mapping.name.substring(0, colon);
			if (!domain.equals(Tags.MOD_ID) && !domain.equals("OCLights3")
					&& !domain.equals("OCLights2")) {
				continue;
			}
			if (ABANDONED_REGISTRY_NAMES.contains(mapping.name.substring(colon + 1))) {
				logger.info("Abandoning removed legacy mapping " + mapping.name
						+ " (" + mapping.type + ")");
				mapping.ignore();
			}
		}
	}

	@Mod.EventHandler
	public void load(FMLPostInitializationEvent event) {
		proxy.registerRenderInfo();
		// v2 has its own channel (V2Net, "OpenGPUv2"); the legacy SimpleNetworkWrapper on the
		// mod id died with the old protocol.
		NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiHandler());
	}
}
