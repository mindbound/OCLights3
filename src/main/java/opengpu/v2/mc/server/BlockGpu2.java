package opengpu.v2.mc.server;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraft.block.Block;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import opengpu.GuiHandler;
import opengpu.OpenGPU;

/**
 * The v2 GPU block. Right-click opens the scene viewer GUI (the Stage A development
 * surface — monitors return as components in the next increment). Breaking the block
 * destroys the scene: SCENE_GONE to watchers, stored resource bytes deleted.
 */
public class BlockGpu2 extends BlockContainer {
	@SideOnly(Side.CLIENT)
	private IIcon sides;

	public BlockGpu2(Material material) {
		super(material);
		setHardness(2.0F);
		setBlockName("gpu2");
		setCreativeTab(OpenGPU.ocltab);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int metadata) {
		return new TileEntityGpu2();
	}

	@Override
	public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player,
			int side, float hitX, float hitY, float hitZ) {
		if (player.isSneaking()) {
			return false;
		}
		player.openGui(OpenGPU.instance, GuiHandler.GUI_SCENE_VIEWER, world, x, y, z);
		return true;
	}

	@Override
	public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
		if (!world.isRemote) {
			TileEntity te = world.getTileEntity(x, y, z);
			if (te instanceof TileEntityGpu2) {
				((TileEntityGpu2) te).onBlockDestroyed();
			}
		}
		super.breakBlock(world, x, y, z, block, metadata);
	}

	@Override
	public int getRenderType() {
		// BlockContainer defaults to -1 (invisible); this is a plain textured cube.
		return 0;
	}

	@Override
	public boolean renderAsNormalBlock() {
		return true;
	}

	@Override
	public boolean isOpaqueCube() {
		return true;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void registerBlockIcons(IIconRegister register) {
		blockIcon = register.registerIcon("oclights:gpufront");
		sides = register.registerIcon("oclights:gpusides");
	}

	@Override
	@SideOnly(Side.CLIENT)
	public IIcon getIcon(int side, int metadata) {
		return side == 1 ? blockIcon : sides;
	}
}
