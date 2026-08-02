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
	/**
	 * In-plane neighbour bits as of the last rebuild, or -1 when never scanned. Deliberately
	 * NOT persisted: -1 after a load forces one rebuild, which is what we want anyway.
	 */
	private int neighbourMask = -1;

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

	/** Ask for a rescan on the next tick (placement, break, or orphan sweep). */
	public void markWallDirty() {
		wallDirty = true;
	}

	/**
	 * Ask for a rescan only if this tile's own in-plane adjacency actually changed.
	 *
	 * A wall's shape is a function of nothing but which coplanar neighbours are same-facing
	 * screens, so a redstone torch, a piston, flowing water, or any block placed in front of
	 * or behind the wall cannot reshape it — yet the unfiltered hook re-ran the whole
	 * flood-fill for every one of them. A 1-tick clock next to a 16x16 wall bought a
	 * permanent 20 Hz full rescan for the price of two blocks. Four lookups settle it.
	 *
	 * Sound because a change is always adjacent to SOME member, and that member sees it: a
	 * screen added or removed at P flips the adjacency bit of every tile touching P.
	 */
	public void markWallDirtyIfShapeCouldChange() {
		if (worldObj == null || worldObj.isRemote) {
			return;
		}
		int mask = inPlaneNeighbourMask(rightAxis());
		if (mask != neighbourMask) {
			neighbourMask = mask;
			wallDirty = true;
		}
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
	 * Membership is a property of the GROUP, never of the tile that happens to be scanning.
	 * That distinction is the entire design. An earlier version derived the rectangle from
	 * the scanning tile's own row and column runs, and for any non-rectangular group two
	 * members derived two DIFFERENT, overlapping rectangles and wrote both into the tiles
	 * they shared — while the apply loop cleared the losers' dirty flags, silencing the
	 * dissenters before they could object. Breaking one corner off a 2x2 left the opposite
	 * tile holding an origin whose rectangle excluded it: nothing drew it (the TESR skips a
	 * non-origin), no computer could see it (its component was left hidden), {@code bind()}
	 * refused it, and no event remained that could ever revisit it. Only breaking the block
	 * recovered it.
	 *
	 * So: flood-fill the connected, coplanar, same-facing component and accept it only when
	 * it exactly fills its own bounding box. Every member reaches the same component from
	 * wherever it starts, so every member computes the same verdict — which is what makes
	 * applying that verdict to all of them, and clearing their dirty flags, sound rather
	 * than silencing. Anything else — an L, a T, a ring, a ragged edge — makes every member
	 * a lone 1x1, which is at least a display the player can see and address.
	 *
	 * The origin is STICKY: an incumbent still inside the rectangle keeps the role, so
	 * growing a wall does not move the address Lua is holding. Two walls merging have two
	 * incumbents and one must lose; the tie breaks on the lowest cell, so the outcome does
	 * not depend on tile iteration order. (The GPU follows a displaced origin — see
	 * TileEntityGpu2.resolveScreenLocked.)
	 */
	public void rebuildWall() {
		wallDirty = false;
		if (worldObj == null || worldObj.isRemote) {
			return;
		}
		int[] right = rightAxis();
		neighbourMask = inPlaneNeighbourMask(right);
		// Previous membership, captured before anything is mutated, so tiles that LEAVE can
		// be rescanned. Reading our own fields is only safe because peers now write the
		// IDENTICAL rectangle: when they disagreed, a peer's apply destroyed the very
		// membership this sweep needs and orphans were stranded unreachably.
		int oldBaseX = xCoord - right[0] * col;
		int oldBaseY = yCoord - row;
		int oldBaseZ = zCoord - right[2] * col;
		int oldW = wallW, oldH = wallH;

		java.util.List<TileEntityScreen2> group = collectGroup(right);
		int baseX = xCoord, baseY = yCoord, baseZ = zCoord;
		int width = 1, height = 1;
		boolean rectangle = false;
		if (group != null) {
			int minR = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE;
			int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE;
			for (int i = 0; i < group.size(); i++) {
				TileEntityScreen2 tile = group.get(i);
				int r = cellR(tile, xCoord, zCoord, right);
				int u = tile.yCoord - yCoord;
				if (r < minR) { minR = r; }
				if (r > maxR) { maxR = r; }
				if (u < minU) { minU = u; }
				if (u > maxU) { maxU = u; }
			}
			width = maxR - minR + 1;
			height = maxU - minU + 1;
			// Exactly filled: a hole anywhere makes the area exceed the member count. Long
			// arithmetic because width*height is attacker-influenced through the build.
			rectangle = width <= MAX_WALL_SPAN && height <= MAX_WALL_SPAN
					&& (long) width * (long) height == group.size();
			baseX = xCoord + right[0] * minR;
			baseY = yCoord + minU;
			baseZ = zCoord + right[2] * minR;
		}

		java.util.Set<Long> members = new java.util.HashSet<Long>();
		if (!rectangle) {
			// Demote every member to its own 1x1 HERE, rather than marking them dirty and
			// hoping they get to it: a member left pointing at an origin that no longer
			// counts it is exactly the stranded-tile failure above, and by then there is no
			// event left to recover it. Doing it inline also settles the whole group in one
			// pass instead of N cascading rebuilds.
			if (group != null) {
				for (int i = 0; i < group.size(); i++) {
					TileEntityScreen2 tile = group.get(i);
					tile.applyWall(tile.xCoord, tile.yCoord, tile.zCoord, 1, 1, 0, 0);
					tile.wallDirty = false;
					members.add(Long.valueOf(packed(tile.xCoord, tile.yCoord, tile.zCoord)));
				}
			} else {
				applyWall(xCoord, yCoord, zCoord, 1, 1, 0, 0);
				members.add(Long.valueOf(packed(xCoord, yCoord, zCoord)));
			}
		} else {
			// Sticky origin, decided from the GROUP so the answer cannot depend on which tile
			// rebuilt first. An incumbent is any member that is currently an origin; merging
			// two walls presents two, and the lowest cell wins.
			int originR = 0, originU = 0;
			boolean haveIncumbent = false;
			for (int i = 0; i < group.size(); i++) {
				TileEntityScreen2 tile = group.get(i);
				if (!tile.isOrigin()) {
					continue;
				}
				int r = cellR(tile, baseX, baseZ, right);
				int u = tile.yCoord - baseY;
				if (!haveIncumbent || u < originU || (u == originU && r < originR)) {
					haveIncumbent = true;
					originR = r;
					originU = u;
				}
			}
			int newOx = baseX + right[0] * originR;
			int newOy = baseY + originU;
			int newOz = baseZ + right[2] * originR;
			for (int i = 0; i < group.size(); i++) {
				TileEntityScreen2 tile = group.get(i);
				tile.applyWall(newOx, newOy, newOz, width, height,
						cellR(tile, baseX, baseZ, right), tile.yCoord - baseY);
				tile.wallDirty = false;
				members.add(Long.valueOf(packed(tile.xCoord, tile.yCoord, tile.zCoord)));
			}
		}
		// Tiles that were in the OLD wall but are not members now are orphans — the wall was
		// split, or collapsed. Without this they keep stale geometry, a stale origin and an
		// invisible OC component: a screen that can never be bound again.
		for (int r = 0; r < oldW; r++) {
			for (int u = 0; u < oldH; u++) {
				int tx = oldBaseX + right[0] * r;
				int ty = oldBaseY + u;
				int tz = oldBaseZ + right[2] * r;
				if (members.contains(Long.valueOf(packed(tx, ty, tz)))) {
					continue;
				}
				TileEntityScreen2 orphan = screenAt(tx, ty, tz);
				if (orphan != null) {
					orphan.markWallDirty();
				}
			}
		}
	}

	/** This tile's column index relative to a base cell, along the viewer's right axis. */
	private static int cellR(TileEntityScreen2 tile, int baseX, int baseZ, int[] right) {
		return (tile.xCoord - baseX) * right[0] + (tile.zCoord - baseZ) * right[2];
	}

	/**
	 * Every coplanar, same-facing screen connected to this one, or null when the component is
	 * larger than any legal wall could be. Starting from any member yields the same set,
	 * which is what lets each tile agree on the wall without consulting its peers' stored
	 * state — the property the old row/column scan lacked.
	 */
	private java.util.List<TileEntityScreen2> collectGroup(int[] right) {
		java.util.List<TileEntityScreen2> found = new java.util.ArrayList<TileEntityScreen2>();
		java.util.Set<Long> seen = new java.util.HashSet<Long>();
		java.util.ArrayDeque<TileEntityScreen2> queue = new java.util.ArrayDeque<TileEntityScreen2>();
		seen.add(Long.valueOf(packed(xCoord, yCoord, zCoord)));
		queue.add(this);
		while (!queue.isEmpty()) {
			TileEntityScreen2 tile = queue.poll();
			found.add(tile);
			if (found.size() > MAX_WALL_CELLS) {
				// Cannot be a legal rectangle whatever the rest looks like. Stop before the
				// scan becomes something a player can point at the server thread.
				return null;
			}
			for (int d = -1; d <= 1; d += 2) {
				enqueue(queue, seen, tile.xCoord + right[0] * d, tile.yCoord,
						tile.zCoord + right[2] * d);
				enqueue(queue, seen, tile.xCoord, tile.yCoord + d, tile.zCoord);
			}
		}
		return found;
	}

	private void enqueue(java.util.ArrayDeque<TileEntityScreen2> queue, java.util.Set<Long> seen,
			int x, int y, int z) {
		// Record non-screen positions as seen too, so a wall's boundary is probed once.
		if (!seen.add(Long.valueOf(packed(x, y, z)))) {
			return;
		}
		TileEntityScreen2 tile = screenAt(x, y, z);
		if (tile != null) {
			queue.add(tile);
		}
	}

	private static long packed(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFF) << 30) | (z & 0x3FFFFFFL);
	}

	/**
	 * Which of the four in-plane neighbours are same-facing screens. A wall's shape depends
	 * on nothing else, so comparing this against the last value lets a tile ignore the
	 * neighbour changes that cannot possibly reshape it.
	 */
	private int inPlaneNeighbourMask(int[] right) {
		int mask = 0;
		if (screenAt(xCoord + right[0], yCoord, zCoord + right[2]) != null) { mask |= 1; }
		if (screenAt(xCoord - right[0], yCoord, zCoord - right[2]) != null) { mask |= 2; }
		if (screenAt(xCoord, yCoord + 1, zCoord) != null) { mask |= 4; }
		if (screenAt(xCoord, yCoord - 1, zCoord) != null) { mask |= 8; }
		return mask;
	}

	/**
	 * Squared distance from a point to the nearest tile CENTRE of this wall.
	 *
	 * Authorization distance has to be derived from the SURFACE, not from the one block that
	 * happens to hold the component: the origin is sticky and can sit at any corner of a
	 * wall up to {@link #MAX_WALL_SPAN} tiles across, so measuring from it left the far end
	 * of a wide wall silently deaf to every GUI event while in-world right-clicks on those
	 * same tiles kept working — which reads as a broken GUI, not as a range limit.
	 */
	public double distanceSqToNearestTile(double px, double py, double pz) {
		int[] right = rightAxis();
		int baseX = xCoord - right[0] * col;
		int baseY = yCoord - row;
		int baseZ = zCoord - right[2] * col;
		int farX = baseX + right[0] * (wallW - 1);
		int farZ = baseZ + right[2] * (wallW - 1);
		// Axis-aligned rectangle of unit cells, so clamping per axis finds the nearest cell
		// centre without visiting a single cell.
		double dx = px - clamp(px, Math.min(baseX, farX) + 0.5, Math.max(baseX, farX) + 0.5);
		double dy = py - clamp(py, baseY + 0.5, baseY + wallH - 0.5);
		double dz = pz - clamp(pz, Math.min(baseZ, farZ) + 0.5, Math.max(baseZ, farZ) + 0.5);
		return dx * dx + dy * dy + dz * dz;
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	/** Largest wall span in tiles, so a pathological build cannot scan without bound. */
	public static final int MAX_WALL_SPAN = 16;

	/** Cells in the largest legal wall — the flood-fill's hard stop. */
	private static final int MAX_WALL_CELLS = MAX_WALL_SPAN * MAX_WALL_SPAN;

	/**
	 * Write this tile's membership, and sync only what a client can actually observe.
	 *
	 * A SATELLITE's entire client-visible state is one bit. {@link ScreenRenderer} returns at
	 * {@code !isOrigin()} before reading a single geometry field, and no other client-side
	 * code reads any of them — so a satellite whose col/row shifted, or whose wall grew, or
	 * whose origin moved to a different tile, looks identical on screen and needs no packet.
	 * Sending one anyway is what made a reshape expensive: 1.7.10's PlayerInstance stops
	 * accumulating individual block changes at 64 flags per chunk and escalates to a full
	 * S21PacketChunkData for every affected section PLUS a description packet for every tile
	 * entity in them, so a 16x16 wall turned a seven-int geometry change into two whole
	 * chunk sections re-serialised to every watching player.
	 *
	 * Now only two things reach the wire: an origin whose rendered geometry changed, and any
	 * tile that crossed the origin/satellite line. A reshape that keeps its membership costs
	 * one packet, not 256.
	 *
	 * IF a client-side caller is ever added that reads a satellite's wallW/wallH/col/row or
	 * resolves origin() from one, this optimisation becomes a desync and must be revisited —
	 * chunk load still sends the full state via getDescriptionPacket, so the staleness is
	 * invisible until something looks.
	 */
	private void applyWall(int ox, int oy, int oz, int width, int height, int c, int r) {
		boolean changed = originX != ox || originY != oy || originZ != oz
				|| wallW != width || wallH != height || col != c || row != r;
		boolean wasOrigin = isOrigin();
		boolean geometryChanged = wallW != width || wallH != height || col != c || row != r;
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
				//
				// sceneId only — NOT driverAddress. That field is not just display state, it
				// is the arming condition for the whole break notification: V2ServerRuntime
				// .onScreenRemoved returns immediately when it is null, so nulling it here
				// made breaking a demoted tile notify no GPU at all, and the GPU kept a
				// binding to a node that no longer existed. The window is unbounded — the
				// GPU only notices a demotion on its 20-tick policy tick, and not at all
				// while its own chunk is unloaded. Leaving it set is safe because a satellite
				// is not a bind target either way (bind() refuses one, auto-bind requires an
				// origin), and reconcileDriver() clears it once the driver stops claiming us.
				sceneId = null;
			}
			// Always persist: the save path stores every field, observable or not.
			markDirty();
			if (wasOrigin != isOrigin() || (isOrigin() && geometryChanged)) {
				worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
			}
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
		// Validated, not merely length-checked. These ints come straight back as loop bounds
		// in rebuildWall's orphan sweep, so a hand-edited region file, an NBT-copying
		// schematic tool or plain chunk corruption could otherwise hand the server tick
		// thread a 10^12-iteration loop — and col/row outside the wall would aim the sweep at
		// unrelated screens hundreds of blocks away. Same reasoning as the push-depth clamp
		// in TileEntityGpu2.readFromNBT; MAX_WALL_SPAN was enforced on the live scan only.
		int[] wall = tag.getIntArray("v2wall");
		if (wall.length == 7 && wall[3] >= 1 && wall[3] <= MAX_WALL_SPAN
				&& wall[4] >= 1 && wall[4] <= MAX_WALL_SPAN
				&& wall[5] >= 0 && wall[5] < wall[3]
				&& wall[6] >= 0 && wall[6] < wall[4]) {
			originX = wall[0];
			originY = wall[1];
			originZ = wall[2];
			wallW = wall[3];
			wallH = wall[4];
			col = wall[5];
			row = wall[6];
		} else {
			// Fresh placement, a pre-wall save, or a tag we do not trust: a lone screen is
			// its own 1x1 wall. The rescan below settles the real shape either way.
			originX = xCoord;
			originY = yCoord;
			originZ = zCoord;
			wallW = 1;
			wallH = 1;
			col = 0;
			row = 0;
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
		// Validated like the save path: wallW/wallH scale the TESR's quad and drive the
		// letterbox fit, so a bogus value paints a display across the sky rather than merely
		// desyncing. A rejected packet leaves the tile as a 1x1 until the next update.
		int[] wall = tag.getIntArray("wall");
		if (wall.length == 7 && wall[3] >= 1 && wall[3] <= MAX_WALL_SPAN
				&& wall[4] >= 1 && wall[4] <= MAX_WALL_SPAN
				&& wall[5] >= 0 && wall[5] < wall[3]
				&& wall[6] >= 0 && wall[6] < wall[4]) {
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
	 * The origin's render bounds must never cull while any part of the wall is in view.
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
