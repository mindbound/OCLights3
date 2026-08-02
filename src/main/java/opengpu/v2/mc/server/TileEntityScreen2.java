package opengpu.v2.mc.server;

import li.cil.oc.api.Network;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Component;
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

	// ------------------------------------------------------------------
	// Multiblock wall. A lone screen is a 1x1 wall, so there is one code path, not two.
	//
	// The ORIGIN tile owns the surface: it holds the component node visibility, the scene
	// binding, and the whole wall's render bounds. Satellites keep their own node (so their
	// address survives a reshape) but hide it from the component list, and render nothing.

	/** World coords of the wall's origin tile; equal to our own when we ARE the origin. */
	private int originX, originY, originZ;
	/** Wall size in tiles; only meaningful on the origin. */
	private int wallW = 1, wallH = 1;
	/** This tile's position within the wall, 0-based from the viewer's bottom-left. */
	private int col, row;
	private boolean wallDirty = true;

	public boolean isOrigin() {
		return originX == xCoord && originY == yCoord && originZ == zCoord;
	}

	public int wallWidth() {
		return wallW;
	}

	public int wallHeight() {
		return wallH;
	}

	public int wallCol() {
		return col;
	}

	public int wallRow() {
		return row;
	}

	public int[] originCoords() {
		return new int[] { originX, originY, originZ };
	}

	/** The origin TE of this wall, or null when its chunk is not loaded. */
	public TileEntityScreen2 origin() {
		if (isOrigin()) {
			return this;
		}
		// blockExists first: getTileEntity will happily LOAD (and on the server generate) the
		// chunk, which is not what "or null when its chunk is not loaded" promises and turns
		// a render/click lookup into world generation.
		if (worldObj == null || !worldObj.blockExists(originX, originY, originZ)) {
			return null;
		}
		TileEntity te = worldObj.getTileEntity(originX, originY, originZ);
		return te instanceof TileEntityScreen2 ? (TileEntityScreen2) te : null;
	}

	/** Ask for a rescan on the next tick (placement, break, or neighbour change). */
	public void markWallDirty() {
		wallDirty = true;
	}

	/**
	 * Horizontal axis across the display face, as the viewer sees it left-to-right.
	 * Returns the world-space delta for one tile step to the viewer's right.
	 */
	private int[] rightAxis() {
		switch (facing()) {
			case 2:  return new int[] { -1, 0, 0 }; // north (-Z): right is -X
			case 3:  return new int[] { 1, 0, 0 };  // south (+Z): right is +X
			case 4:  return new int[] { 0, 0, 1 };  // west  (-X): right is +Z
			default: return new int[] { 0, 0, -1 }; // east  (+X): right is -Z
		}
	}

	private TileEntityScreen2 screenAt(int x, int y, int z) {
		if (worldObj == null || !worldObj.blockExists(x, y, z)) {
			return null;
		}
		TileEntity te = worldObj.getTileEntity(x, y, z);
		if (!(te instanceof TileEntityScreen2)) {
			return null;
		}
		TileEntityScreen2 other = (TileEntityScreen2) te;
		// Same plane and orientation only: two screens facing different ways are two walls.
		return other.facing() == facing() ? other : null;
	}

	/**
	 * Rebuild this wall's shape.
	 *
	 * Walks the coplanar, same-facing neighbours to a bounding rectangle and accepts it only
	 * when every cell is filled — an L-shape is not a display. The origin is STICKY: if the
	 * previous origin is still part of the wall it keeps the role, so adding a tile does not
	 * silently move the surface address that Lua is holding. Otherwise the lowest cell wins,
	 * deterministically.
	 */
	public void rebuildWall() {
		wallDirty = false;
		if (worldObj == null || worldObj.isRemote) {
			return;
		}
		// Remember the current membership so tiles that LEAVE the wall can be rescanned.
		int[] oldRight = rightAxis();
		int oldBaseX = xCoord - oldRight[0] * col;
		int oldBaseY = yCoord - row;
		int oldBaseZ = zCoord - oldRight[2] * col;
		int oldW = wallW, oldH = wallH;

		int[] right = rightAxis();
		// Extent along each axis from this tile. Each direction is scanned independently to
		// a hard stop; a run LONGER than the span is then refused outright rather than
		// clamped, because a clamped window depends on which tile did the scanning and two
		// tiles in the same run would build two different, overlapping walls.
		int minR = 0, maxR = 0, minU = 0, maxU = 0;
		int limit = MAX_WALL_SPAN + 1;
		while (-minR < limit
				&& screenAt(xCoord + right[0] * (minR - 1), yCoord, zCoord + right[2] * (minR - 1)) != null) {
			minR--;
		}
		while (maxR < limit
				&& screenAt(xCoord + right[0] * (maxR + 1), yCoord, zCoord + right[2] * (maxR + 1)) != null) {
			maxR++;
		}
		while (-minU < limit && screenAt(xCoord, yCoord + (minU - 1), zCoord) != null) {
			minU--;
		}
		while (maxU < limit && screenAt(xCoord, yCoord + (maxU + 1), zCoord) != null) {
			maxU++;
		}
		int width = maxR - minR + 1;
		int height = maxU - minU + 1;
		if (width > MAX_WALL_SPAN || height > MAX_WALL_SPAN) {
			// Every tile in an over-long run sees an over-long run, so all of them collapse
			// to 1x1 — a deterministic, if unhelpful, outcome rather than a split-brain wall.
			width = 1;
			height = 1;
			minR = maxR = minU = maxU = 0;
		}
		// Every cell must be present, or this is not a rectangle.
		for (int r = minR; r <= maxR; r++) {
			for (int u = minU; u <= maxU; u++) {
				if (screenAt(xCoord + right[0] * r, yCoord + u, zCoord + right[2] * r) == null) {
					width = 1;
					height = 1;
					minR = maxR = minU = maxU = 0;
					r = maxR + 1;
					break;
				}
			}
		}
		int baseX = xCoord + right[0] * minR;
		int baseY = yCoord + minU;
		int baseZ = zCoord + right[2] * minR;
		// Sticky origin: keep the incumbent if it is still inside the new rectangle.
		int newOx = baseX, newOy = baseY, newOz = baseZ;
		TileEntityScreen2 incumbent = screenAt(originX, originY, originZ);
		if (incumbent != null && withinWall(originX, originY, originZ, baseX, baseY, baseZ,
				right, width, height)) {
			newOx = originX;
			newOy = originY;
			newOz = originZ;
		}
		// Apply to every tile, including this one. Each tile it touches is authoritative
		// afterwards, so clearing their dirty flags stops all N tiles of a wall each running
		// their own O(W*H) rebuild for the same event.
		for (int r = 0; r < width; r++) {
			for (int u = 0; u < height; u++) {
				TileEntityScreen2 tile = screenAt(baseX + right[0] * r, baseY + u, baseZ + right[2] * r);
				if (tile != null) {
					tile.applyWall(newOx, newOy, newOz, width, height, r, u);
					tile.wallDirty = false;
				}
			}
		}
		// Tiles that were in the OLD wall but not the new one are now orphans. Nothing above
		// touches them, so without this they keep stale geometry, a stale origin and — worst
		// — an invisible OC component, i.e. a screen that can never be bound again.
		for (int r = 0; r < oldW; r++) {
			for (int u = 0; u < oldH; u++) {
				int tx = oldBaseX + oldRight[0] * r;
				int ty = oldBaseY + u;
				int tz = oldBaseZ + oldRight[2] * r;
				if (withinWall(tx, ty, tz, baseX, baseY, baseZ, right, width, height)) {
					continue;
				}
				TileEntityScreen2 orphan = screenAt(tx, ty, tz);
				if (orphan != null) {
					orphan.markWallDirty();
				}
			}
		}
	}

	private boolean withinWall(int x, int y, int z, int baseX, int baseY, int baseZ,
			int[] right, int width, int height) {
		for (int r = 0; r < width; r++) {
			for (int u = 0; u < height; u++) {
				if (baseX + right[0] * r == x && baseY + u == y && baseZ + right[2] * r == z) {
					return true;
				}
			}
		}
		return false;
	}

	/** Largest wall span in tiles, so a pathological build cannot scan without bound. */
	public static final int MAX_WALL_SPAN = 16;

	private void applyWall(int ox, int oy, int oz, int width, int height, int c, int r) {
		boolean changed = originX != ox || originY != oy || originZ != oz
				|| wallW != width || wallH != height || col != c || row != r;
		originX = ox;
		originY = oy;
		originZ = oz;
		wallW = width;
		wallH = height;
		col = c;
		row = r;
		if (node instanceof Component) {
			// Only the origin is a visible component: N addresses for one display would be a
			// confusing component list and an ambiguous bind target. Node REACHABILITY is
			// fixed at creation; component VISIBILITY is the dynamic one, so the node (and
			// therefore the address) survives for a tile that becomes an origin later.
			((Component) node).setVisibility(isOrigin() ? Visibility.Network : Visibility.None);
		}
		if (changed) {
			if (!isOrigin()) {
				// A satellite shows nothing of its own; the origin covers the whole wall.
				sceneId = null;
				driverAddress = null;
			}
			markDirty();
			worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		}
	}

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
			wallDirty = true;
		}
		if (wallDirty) {
			rebuildWall();
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
		tag.setIntArray("v2wall", new int[] { originX, originY, originZ, wallW, wallH, col, row });
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		if (node != null && node.host() == this && tag.hasKey("oc:node")) {
			node.load(tag.getCompoundTag("oc:node"));
		}
		driverAddress = tag.hasKey("v2driver") ? tag.getString("v2driver") : null;
		sceneId = tag.hasKey("v2scene") ? tag.getString("v2scene") : null;
		int[] wall = tag.getIntArray("v2wall");
		if (wall.length == 7) {
			originX = wall[0];
			originY = wall[1];
			originZ = wall[2];
			wallW = wall[3];
			wallH = wall[4];
			col = wall[5];
			row = wall[6];
		} else {
			// Fresh placement or a pre-wall save: a lone screen is its own 1x1 wall.
			originX = xCoord;
			originY = yCoord;
			originZ = zCoord;
		}
		// The neighbours may have changed while unloaded, so never trust the saved shape.
		wallDirty = true;
	}

	@Override
	public Packet getDescriptionPacket() {
		NBTTagCompound tag = new NBTTagCompound();
		if (sceneId != null) {
			tag.setString("sceneId", sceneId);
		}
		// Geometry only, per the description-packet contract — never bulk scene state.
		tag.setIntArray("wall", new int[] { originX, originY, originZ, wallW, wallH, col, row });
		return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 3, tag);
	}

	@Override
	public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
		NBTTagCompound tag = pkt.func_148857_g();
		sceneId = tag.hasKey("sceneId") ? tag.getString("sceneId") : null;
		int[] wall = tag.getIntArray("wall");
		if (wall.length == 7) {
			originX = wall[0];
			originY = wall[1];
			originZ = wall[2];
			wallW = wall[3];
			wallH = wall[4];
			col = wall[5];
			row = wall[6];
		}
	}

	/**
	 * The origin's render bounds must cover the WHOLE wall.
	 *
	 * Angelica caches and classifies render bounds per TE class and the base implementation
	 * is now the block's collision box, so an unoverridden origin frustum-culls the entire
	 * display the moment its own block leaves view — the wall vanishes while the player is
	 * still looking straight at it. Satellites render nothing, so their bounds do not matter.
	 *
	 * It must be INFINITE, not a wall-sized box, and that is not laziness.
	 * TileEntityRenderBoundsRegistry.classify() sorts a TE class three ways: a box containing
	 * an infinity is INFINITE (never culled); any other box is STATIC, and Angelica then
	 * CACHES it per instance and never recomputes it; only classes named in the
	 * dynamicBoundsTileEntities config get this method called per frame. Our bounds change
	 * whenever a wall is assembled or reshaped, so a finite box is cached while the TE is
	 * still a 1x1 and the display then culls the moment the ORIGIN BLOCK leaves the frustum,
	 * even though the rest of the wall is in plain view — observed in game as the picture
	 * vanishing when the player steps close or looks up. Worse, classify() caches per CLASS
	 * from the first instance it ever sees, so "return the wall box once assembled" cannot
	 * work either.
	 *
	 * A finite box also loses twice, not once: the chunk mesher sorts a TE whose box fits
	 * inside its own 16^3 section into a "culled" list that is walked only through
	 * frustum-VISIBLE sections, so such a screen is section-gated as well as box-tested.
	 * INFINITE puts us in the global list and skips the per-TE test outright.
	 *
	 * Angelica does expose TileEntityRenderBoundsRegistry.registerDynamicClass(String)
	 * publicly, so a soft-dependency call at client init could buy real per-frame culling.
	 * It is deliberately not done: it only pays off if the box here is finite, and then a
	 * registry class that moves — ANGELICA-NOTES warns these churn every release — silently
	 * reinstates this exact bug instead of failing loudly. This TESR early-returns for
	 * satellites and for scenes with no texture, so always dispatching costs a few branches
	 * per screen. Infinite is also Forge's own 1.7.10 default, so vanilla is unaffected.
	 */
	@Override
	@cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
	public net.minecraft.util.AxisAlignedBB getRenderBoundingBox() {
		return INFINITE_EXTENT_AABB;
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
