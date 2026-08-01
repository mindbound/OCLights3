package opengpu.v2.mc.server;

import li.cil.oc.api.Network;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

/**
 * A v2 screen: an OC component that displays one scene. It owns no scene state of its own —
 * a GPU binds it ({@code gpu.bind(address)}) and pushes its scene id here; the id rides the
 * description packet so client TESRs know what to draw.
 *
 * The design's "monitors become OC components" step: no AbstractValue inner class, so the
 * legacy A-03 value-persistence hazard cannot recur.
 */
public class TileEntityScreen2 extends TileEntity implements Environment {
	public static final String COMPONENT_NAME = "opengpu_screen";

	/** Same cadence as the GPU's policy tick. */
	private static final int RECONCILE_INTERVAL_TICKS = 20;

	protected Node node;
	private boolean addedToNetwork;
	private int reconcileTicks;

	/** Server: pushed by the driving GPU. Client: from the description packet. */
	private String sceneId;
	/** Address of the GPU currently driving this screen (server side, persisted). */
	private String driverAddress;

	public TileEntityScreen2() {
		node = Network.newNode(this, Visibility.Network).withComponent(COMPONENT_NAME).create();
	}

	public String sceneId() {
		return sceneId;
	}

	public String driverAddress() {
		return driverAddress;
	}

	/**
	 * Called server-side by the driving GPU. Re-sends the description packet on change so
	 * watchers learn the new scene id without the TE having to reappear.
	 */
	public void bindScene(String gpuAddress, String newSceneId) {
		boolean changed = !equal(sceneId, newSceneId) || !equal(driverAddress, gpuAddress);
		driverAddress = gpuAddress;
		sceneId = newSceneId;
		if (changed && worldObj != null && !worldObj.isRemote) {
			markDirty();
			worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		}
	}

	/** Driver went away (unbound, broken, or rebound elsewhere). */
	public void clearScene(String gpuAddress) {
		if (gpuAddress == null || gpuAddress.equals(driverAddress)) {
			bindScene(null, null);
		}
	}

	private static boolean equal(String a, String b) {
		return a == null ? b == null : a.equals(b);
	}

	/** Facing is stored in block metadata (2..5 = N/S/W/E), like vanilla directional blocks. */
	public int facing() {
		return BlockScreen2.facingFromMeta(getBlockMetadata());
	}

	@Override
	public void updateEntity() {
		if (worldObj.isRemote) {
			return;
		}
		if (!addedToNetwork) {
			addedToNetwork = true;
			Network.joinOrCreateNetwork(this);
		}
		if (++reconcileTicks >= RECONCILE_INTERVAL_TICKS) {
			reconcileTicks = 0;
			reconcileDriver();
		}
	}

	/**
	 * The binding is recorded in two chunks — here and on the GPU — so a crash between
	 * their saves can leave this screen claiming a driver that does not claim it back.
	 * That direction never self-heals on its own (the GPU re-pushes only bindings it
	 * knows about), and the stale driverAddress would lock every other GPU out while the
	 * screen renders a scene nobody updates.
	 *
	 * The GPU is the single source of truth. Only act on the unambiguous signature: the
	 * named driver is resolvable AND does not claim us. A driver that is merely unloaded
	 * resolves to null and is left alone, so an unloaded-chunk GPU keeps its screen.
	 */
	private void reconcileDriver() {
		if (driverAddress == null || node == null || node.network() == null || node.address() == null) {
			return;
		}
		Node driver = node.network().node(driverAddress);
		if (driver == null || !(driver.host() instanceof TileEntityGpu2)) {
			return;
		}
		String claimed = ((TileEntityGpu2) driver.host()).boundScreenAddress();
		if (!node.address().equals(claimed)) {
			bindScene(null, null);
		}
	}

	@Override
	public void invalidate() {
		super.invalidate();
		if (node != null) {
			node.remove();
		}
	}

	@Override
	public void onChunkUnload() {
		super.onChunkUnload();
		if (node != null) {
			node.remove();
		}
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		super.writeToNBT(tag);
		if (node != null && node.host() == this) {
			NBTTagCompound nodeTag = new NBTTagCompound();
			node.save(nodeTag);
			tag.setTag("oc:node", nodeTag);
		}
		if (driverAddress != null) {
			tag.setString("v2driver", driverAddress);
		}
		if (sceneId != null) {
			tag.setString("v2scene", sceneId);
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		if (node != null && node.host() == this && tag.hasKey("oc:node")) {
			node.load(tag.getCompoundTag("oc:node"));
		}
		driverAddress = tag.hasKey("v2driver") ? tag.getString("v2driver") : null;
		sceneId = tag.hasKey("v2scene") ? tag.getString("v2scene") : null;
	}

	@Override
	public Packet getDescriptionPacket() {
		NBTTagCompound tag = new NBTTagCompound();
		if (sceneId != null) {
			tag.setString("sceneId", sceneId);
		}
		return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 3, tag);
	}

	@Override
	public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
		NBTTagCompound tag = pkt.func_148857_g();
		sceneId = tag.hasKey("sceneId") ? tag.getString("sceneId") : null;
	}

	// ------------------------------------------------------------------
	// OC Environment

	@Override
	public Node node() {
		return node;
	}

	@Override
	public void onConnect(Node node) {}

	@Override
	public void onDisconnect(Node node) {}

	@Override
	public void onMessage(Message message) {}

	@Callback(direct = true, doc = "function():number, number -- The scene resolution shown on this screen.")
	public Object[] getResolution(Context context, Arguments args) {
		return new Object[] { TileEntityGpu2.DEFAULT_WIDTH, TileEntityGpu2.DEFAULT_HEIGHT };
	}

	@Callback(direct = true, doc = "function():string -- Address of the GPU driving this screen, or nil.")
	public Object[] getDriver(Context context, Arguments args) {
		return new Object[] { driverAddress };
	}
}
