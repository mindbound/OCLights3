package opengpu;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import cpw.mods.fml.common.network.IGuiHandler;
import opengpu.v2.mc.client.GuiScene;
import opengpu.v2.mc.server.TileEntityGpu2;

public class GuiHandler implements IGuiHandler {

	/**
	 * Deliberately still 2, with ids 0 and 1 left burned.
	 *
	 * They belonged to the legacy monitor and tablet GUIs, deleted at the Stage A cut-over.
	 * Renumbering this to 0 would look tidier and would cost nothing today — but the id is what
	 * BlockGpu2 passes to openGui, and a mismatch between the two is a silent no-op rather than
	 * an error, so the only tidy version worth having is one nobody has to re-derive.
	 */
	public static final int GUI_SCENE_VIEWER = 2;

	@Override
	public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
		return null;
	}

	@Override
	public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
		if (ID == GUI_SCENE_VIEWER) {
			TileEntity te = world.getTileEntity(x, y, z);
			if (te instanceof TileEntityGpu2) {
				return new GuiScene((TileEntityGpu2) te);
			}
		}
		return null;
	}
}
