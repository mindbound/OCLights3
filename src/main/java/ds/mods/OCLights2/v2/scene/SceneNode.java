package ds.mods.OCLights2.v2.scene;

/**
 * One retained scene node. TRS + z-order + visibility + tint, plus a resource reference
 * (the canvas a Canvas node displays, the texture a Sprite samples; 0 = none).
 * Interpolation metadata (previous-state tracking) is a mirror/renderer concern layered on
 * later — this class is the shared, GL-free data model.
 */
public final class SceneNode {
	public final int id;
	public final byte type;
	public int ref;

	public double x = 0, y = 0;
	public double rot = 0;
	public double sx = 1, sy = 1;
	public int z = 0;
	public boolean visible = true;
	/** ARGB. */
	public int tint = 0xFFFFFFFF;

	public SceneNode(int id, byte type, int ref) {
		this.id = id;
		this.type = type;
		this.ref = ref;
	}

	public SceneNode copy() {
		SceneNode n = new SceneNode(id, type, ref);
		n.x = x;
		n.y = y;
		n.rot = rot;
		n.sx = sx;
		n.sy = sy;
		n.z = z;
		n.visible = visible;
		n.tint = tint;
		return n;
	}

	public boolean contentEquals(SceneNode o) {
		return id == o.id && type == o.type && ref == o.ref
				&& x == o.x && y == o.y && rot == o.rot && sx == o.sx && sy == o.sy
				&& z == o.z && visible == o.visible && tint == o.tint;
	}
}
