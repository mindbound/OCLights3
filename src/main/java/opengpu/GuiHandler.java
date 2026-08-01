package opengpu;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import cpw.mods.fml.common.network.IGuiHandler;
import opengpu.block.tileentity.TileEntityMonitor;
import opengpu.client.gui.GuiMonitor;
import opengpu.client.gui.GuiTablet;
import opengpu.v2.mc.client.GuiScene;
import opengpu.v2.mc.server.TileEntityGpu2;

public class GuiHandler implements IGuiHandler {

	public static final int GUI_SCENE_VIEWER = 2;

	@Override
	public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
		return null;
	}

	@Override
	public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
		// Use ID 0 to open regular tile GUI. Just use elseif's
		
		switch (ID)
		{
		case 0: {
			TileEntity tile_entity = world.getTileEntity(x, y, z);
			if(tile_entity instanceof TileEntityMonitor) {
				return new GuiMonitor(((TileEntityMonitor) tile_entity));
			}
			return null;
		}
		case 1: {
			ItemStack held = player.getHeldItem();
			if (held == null || held.getTagCompound() == null) {
				return null;
			}
			return new GuiTablet(held.getTagCompound(), world);
		}
		case GUI_SCENE_VIEWER: {
			TileEntity te = world.getTileEntity(x, y, z);
			if (te instanceof TileEntityGpu2) {
				return new GuiScene((TileEntityGpu2) te);
			}
			return null;
		}
		}
		return null;
	}

}
