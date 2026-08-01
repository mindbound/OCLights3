package opengpu.item;

import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import opengpu.OpenGPU;
import opengpu.block.tileentity.TileEntityTTrans;
import opengpu.client.ClientTickHandler;
import opengpu.client.render.TabletRenderer;
import opengpu.utils.TabMesg;
import opengpu.utils.TabMesg.Message;

public class ItemTablet extends Item {

	public ItemTablet() {
		super();
		this.setMaxStackSize(1);
		this.setNoRepair();
		this.setUnlocalizedName("tablet");
		this.setCreativeTab(OpenGPU.ocltab);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void addInformation(ItemStack item,
			EntityPlayer Player, @SuppressWarnings("rawtypes") List par3List, boolean par4) {
	}

	@Override
	public ItemStack onItemRightClick(ItemStack par1ItemStack, World par3World, EntityPlayer Player) {
		NBTTagCompound nbt = getNBT(par1ItemStack, par3World);
		if(Player.isSneaking()){
			if (nbt.getBoolean("canDisplay") && par3World.isRemote) {
				UUID trans = UUID.fromString(nbt.getString("trans"));
				if (TabMesg.getTabVar(trans, "x") != null && TabletRenderer.isInOfRange(trans)) {
					TileEntity te = par3World.getTileEntity(
								(Integer) TabMesg.getTabVar(trans, "x"),
								(Integer) TabMesg.getTabVar(trans, "y"),
								(Integer) TabMesg.getTabVar(trans, "z"));
					if (te instanceof TileEntityTTrans) {
						ClientTickHandler.tile = (TileEntityTTrans) te;
					}
				}
			}
		}
		else{
			Player.openGui(OpenGPU.instance, 1, par3World, 0, 0, 0);
		}
		return par1ItemStack;
	}

	@Override
	public boolean onItemUse(ItemStack par1ItemStack,
			EntityPlayer par2EntityPlayer, World par3World, int par4, int par5,
			int par6, int par7, float par8, float par9, float par10) {
		NBTTagCompound nbt = getNBT(par1ItemStack,par3World);

		if (!par3World.isRemote && Block.isEqualTo(OpenGPU.ttrans, par3World.getBlock(par4, par5, par6)))
		{
			TileEntity te = par3World.getTileEntity(par4, par5, par6);
			if (te instanceof TileEntityTTrans) {
				TileEntityTTrans tile = (TileEntityTTrans) te;
				nbt.setBoolean("canDisplay",true);
				nbt.setString("trans", tile.id.toString());
				TabMesg.pushMessage(tile.id, new Message("connect",UUID.fromString(nbt.getString("uuid"))));
			}
			return false;
		}
		return false;
	}

	@Override
	public int getMaxItemUseDuration(ItemStack par1ItemStack) {
		return 1;
	}

	public NBTTagCompound createNBT(World par3World)
	{
		NBTTagCompound nbt = new NBTTagCompound();
		nbt.setBoolean("canDisplay", false);
		nbt.setString("uuid", UUID.randomUUID().toString());
		return nbt;
	}
	
	public NBTTagCompound getNBT(ItemStack item, World parWorld)
	{
		NBTTagCompound nbt = item.getTagCompound();
		if (nbt == null)
		{
			nbt = createNBT(parWorld);
			item.setTagCompound(nbt);
		}
		// Self-heal partial tags (renamed/command-given tablets): a tag without these keys
		// crashed pairing on UUID.fromString("").
		if (!nbt.hasKey("uuid"))
		{
			nbt.setString("uuid", UUID.randomUUID().toString());
		}
		if (!nbt.hasKey("canDisplay"))
		{
			nbt.setBoolean("canDisplay", false);
		}
		return nbt;
	}
	
	@Override
	public void onCreated(ItemStack par1ItemStack, World par2World,
			EntityPlayer par3EntityPlayer) {
		par1ItemStack.setTagCompound(createNBT(par2World));
	}

	@Override
	public boolean isItemTool(ItemStack par1ItemStack)
    {
		return true;
    }
	//stuff loads faster when forge is satisfied at load
	@Override
	@SideOnly(Side.CLIENT)
	public void registerIcons(IIconRegister par1IconRegister){}
}
