package opengpu.v2.mc.server;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
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

	/**
	 * In-world click: map the face hit to logical scene coordinates and deliver it as a
	 * press/release pair. The hit coordinates arrive server-side already, so this path needs
	 * no C-&gt;S message at all.
	 */
	@Override
	public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player,
			int side, float hitX, float hitY, float hitZ) {
		if (player.isSneaking()) {
			return false;
		}
		TileEntity te = world.getTileEntity(x, y, z);
		if (!(te instanceof TileEntityScreen2)) {
			return false;
		}
		TileEntityScreen2 screen = (TileEntityScreen2) te;
		if (side != screen.facing()) {
			return false; // only the display face is interactive
		}
		// The surface is the wall's ORIGIN; a satellite carries no scene of its own.
		TileEntityScreen2 origin = screen.origin();
		if (origin == null || origin.sceneId() == null) {
			return false;
		}
		int[] logical = wallHitToLogical(screen, origin, side, hitX, hitY, hitZ);
		if (logical == null) {
			return false; // the letterbox bars are not part of the image
		}
		// The predicate above is evaluated identically on both sides — sceneId comes from the
		// description packet and facing from synced metadata — so the return value matches.
		// Returning different values per side makes the client predict a block placement the
		// server rejects, which shows up as a ghost block on every right-click.
		if (!world.isRemote) {
			TileEntityGpu2 gpu = V2ServerRuntime.get().gpuForScene(origin.sceneId());
			if (gpu != null) {
				// Signals name the ORIGIN, so a program sees one surface address for the
				// whole wall regardless of which tile the player happened to hit.
				gpu.onSurfaceClick(player, origin, logical[0], logical[1], 0);
			}
		}
		return true;
	}

	/**
	 * A hit anywhere on a wall to LOGICAL scene coordinates, or null on a letterbox bar.
	 *
	 * The wall-space position is (tileCol + faceU, tileRow + faceV) in tile units, and the
	 * scene is letterboxed into the wall rectangle. This MUST mirror ScreenRenderer's quad
	 * exactly — the single-tile version of this already shipped a 63-row offset because the
	 * forward and inverse mappings were written separately.
	 */
	private static int[] wallHitToLogical(TileEntityScreen2 hit, TileEntityScreen2 origin,
			int side, float hitX, float hitY, float hitZ) {
		int wallW = origin.wallWidth();
		int wallH = origin.wallHeight();
		int sceneW = TileEntityGpu2.DEFAULT_WIDTH;
		int sceneH = TileEntityGpu2.DEFAULT_HEIGHT;

		// Position across the whole wall, in tiles, from its bottom-left as the viewer sees it.
		double wallU = hit.wallCol() + faceU(side, hitX, hitZ);
		double wallV = hit.wallRow() + hitY;

		// The image rectangle inside the wall, same fit the renderer computes.
		double halfW = wallW * 0.5, halfH = wallH * 0.5;
		double sceneAspect = (double) sceneW / sceneH;
		double wallAspect = (double) wallW / wallH;
		if (sceneAspect >= wallAspect) {
			halfH = halfW / sceneAspect;
		} else {
			halfW = halfH * sceneAspect;
		}
		double centreU = wallW * 0.5, centreV = wallH * 0.5;
		double su = (wallU - (centreU - halfW)) / (2.0 * halfW);
		double sv = ((centreV + halfH) - wallV) / (2.0 * halfH); // canvas origin is top-left
		if (su < 0.0 || su >= 1.0 || sv < 0.0 || sv >= 1.0) {
			return null;
		}
		int lx = (int) (su * sceneW);
		int ly = (int) (sv * sceneH);
		return new int[] { Math.max(0, Math.min(sceneW - 1, lx)),
				Math.max(0, Math.min(sceneH - 1, ly)) };
	}

	/**
	 * Face hit to LOGICAL scene coordinates, or null when the hit landed on a letterbox bar.
	 *
	 * This is the exact inverse of the quad the TESR draws: the scene is inset inside the
	 * square face to preserve its aspect, so mapping the whole face to the whole scene puts
	 * every click tens of rows away from the pixel the player aimed at. The two mappings live
	 * in different files and MUST agree.
	 */
	private static int[] faceHitToLogical(int side, float hitX, float hitY, float hitZ) {
		int sceneW = TileEntityGpu2.DEFAULT_WIDTH;
		int sceneH = TileEntityGpu2.DEFAULT_HEIGHT;
		double aspect = (double) sceneW / sceneH;
		double halfW = 0.5, halfH = 0.5;
		if (aspect >= 1.0) {
			halfH = 0.5 / aspect;
		} else {
			halfW = 0.5 * aspect;
		}
		// Face-local coordinates in [0,1], u left-to-right as the player sees it.
		double u = faceU(side, hitX, hitZ);
		// The quad spans [0.5-half, 0.5+half] on each axis of the face.
		double su = (u - (0.5 - halfW)) / (2.0 * halfW);
		// hitY runs bottom-to-top; the canvas origin is top-left.
		double sv = ((0.5 + halfH) - hitY) / (2.0 * halfH);
		if (su < 0.0 || su >= 1.0 || sv < 0.0 || sv >= 1.0) {
			return null;
		}
		int lx = (int) (su * sceneW);
		int ly = (int) (sv * sceneH);
		return new int[] { Math.max(0, Math.min(sceneW - 1, lx)),
				Math.max(0, Math.min(sceneH - 1, ly)) };
	}

	/**
	 * Horizontal position across the display face, 0..1 left-to-right as the player sees it.
	 * Must match the TESR's rotation for each facing, or clicks mirror horizontally.
	 */
	private static float faceU(int side, float hitX, float hitZ) {
		switch (side) {
			case 2:  return 1.0F - hitX; // north (-Z)
			case 3:  return hitX;        // south (+Z)
			case 4:  return hitZ;        // west  (-X)
			default: return 1.0F - hitZ; // east  (+X)
		}
	}

	/**
	 * Any neighbouring screen change may grow, shrink or split this wall. Rescanning is
	 * deferred to the next tick so a multi-block placement settles once rather than N times.
	 */
	@Override
	public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbour) {
		super.onNeighborBlockChange(world, x, y, z, neighbour);
		if (!world.isRemote) {
			TileEntity te = world.getTileEntity(x, y, z);
			if (te instanceof TileEntityScreen2) {
				((TileEntityScreen2) te).markWallDirty();
			}
		}
	}

	@Override
	public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
		if (!world.isRemote) {
			// Tell every surviving tile to rescan BEFORE this one disappears, so a wall that
			// loses a middle block splits instead of keeping a shape with a hole in it.
			for (int[] d : new int[][] { { -1, 0, 0 }, { 1, 0, 0 }, { 0, -1, 0 }, { 0, 1, 0 },
					{ 0, 0, -1 }, { 0, 0, 1 } }) {
				TileEntity n = world.getTileEntity(x + d[0], y + d[1], z + d[2]);
				if (n instanceof TileEntityScreen2) {
					((TileEntityScreen2) n).markWallDirty();
				}
			}
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
