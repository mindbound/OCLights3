package opengpu.v2.mc.server;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import opengpu.OpenGPU;

/**
 * The v2 screen block. Faces the player on placement (metadata 2..5 = N/S/W/E); its TESR
 * draws the bound scene on that face.
 */
public class BlockScreen2 extends BlockContainer {
	@SideOnly(Side.CLIENT)
	private IIcon front;

	public BlockScreen2(Material material) {
		super(material);
		setHardness(2.0F);
		setBlockName("screen2");
		setCreativeTab(OpenGPU.ocltab);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int metadata) {
		return new TileEntityScreen2();
	}

	@Override
	public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
		int facing = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
		// Vanilla directional convention: the block shows its face toward the placer.
		int meta = facing == 0 ? 2 : facing == 1 ? 5 : facing == 2 ? 3 : 4;
		world.setBlockMetadataWithNotify(x, y, z, meta, 2);
	}

	@Override
	public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
		if (!world.isRemote) {
			TileEntity te = world.getTileEntity(x, y, z);
			if (te instanceof TileEntityScreen2) {
				// Tell the driver so it stops pushing scene updates at a dead screen.
				TileEntityScreen2 screen = (TileEntityScreen2) te;
				V2ServerRuntime.get().onScreenRemoved(screen);
			}
		}
		super.breakBlock(world, x, y, z, block, metadata);
	}

	@Override
	public int getRenderType() {
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
		blockIcon = register.registerIcon("oclights:gpusides");
		front = register.registerIcon("oclights:gpufront");
	}

	/**
	 * 2..5 = N/S/W/E. Metadata 0 is reachable whenever placement skips onBlockPlacedBy
	 * (/setblock, WorldEdit, builder machines) and for the inventory item form, so it must
	 * never be treated as a facing.
	 */
	public static int facingFromMeta(int metadata) {
		return metadata >= 2 && metadata <= 5 ? metadata : 3;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public IIcon getIcon(int side, int metadata) {
		// Sides 0/1 (bottom/top) are never a facing; without this guard metadata 0 puts the
		// front icon on the bottom face, including in the creative tab.
		if (side <= 1) {
			return blockIcon;
		}
		return side == facingFromMeta(metadata) ? front : blockIcon;
	}
}
