package ds.mods.OCLights2.v2.protocol;

/**
 * Wire-level constants for protocol v2. Every id here is an explicit constant — nothing on
 * the wire may ever depend on enum ordinals or registration order (the legacy protocol's
 * central fragility). Changing PROTOCOL_VERSION is a hard compatibility break by design:
 * decoders reject anything else.
 */
public final class V2Wire {
	private V2Wire() {}

	public static final short PROTOCOL_VERSION = 1;

	// Delta type ids
	public static final byte DELTA_NODE_CREATE = 1;
	public static final byte DELTA_NODE_FREE = 2;
	public static final byte DELTA_NODE_PROPS = 3;
	public static final byte DELTA_RES_CREATE = 4;
	public static final byte DELTA_RES_FREE = 5;
	public static final byte DELTA_CANVAS_PUBLISH = 6;
	public static final byte DELTA_CANVAS_APPEND = 7;
	/** Reserved for scene-level state (post-chain order, scene uniforms) — unused until Stage D. */
	public static final byte DELTA_SCENE_PROP = 8;
	/** Reserved for surface bind/unbind (payload settles with the Stage A surface work). */
	public static final byte DELTA_BIND = 9;
	public static final byte DELTA_UNBIND = 10;

	// Node types
	public static final byte NODE_CANVAS = 1;
	public static final byte NODE_SPRITE = 2;
	public static final byte NODE_GROUP = 3;
	// Reserved: 4 = MESH_INSTANCE, 5 = CAMERA (Stage C)

	// Resource types
	public static final byte RES_TEXTURE = 1;
	public static final byte RES_CANVAS = 2;
	// Reserved: 3 = MESH, 4 = FONT, 5 = PROGRAM

	// Producer- AND consumer-side sanity caps. Enforced at seal/construction time as well as
	// decode time so an over-cap payload is impossible to produce, not a decode-time surprise.
	public static final int MAX_DELTAS = 1 << 16;
	public static final int MAX_COMMANDS = 1 << 20;
	public static final int MAX_SCENE_PROP_PAYLOAD = 1 << 20;
	/** Modified-UTF-8 keeps 3 bytes/char worst case: 8192 chars stays far under writeUTF's 65535-byte limit. */
	public static final int MAX_TEXT_CHARS = 8192;
	public static final int MAX_TEXTURE_DIM = 8192;

	public static boolean isKnownNodeType(byte type) {
		return type == NODE_CANVAS || type == NODE_SPRITE || type == NODE_GROUP;
	}

	public static boolean isKnownResType(byte type) {
		return type == RES_TEXTURE || type == RES_CANVAS;
	}

	// Node property mask bits (NodeProps delta)
	public static final int PROP_X = 1;
	public static final int PROP_Y = 1 << 1;
	public static final int PROP_ROT = 1 << 2;
	public static final int PROP_SX = 1 << 3;
	public static final int PROP_SY = 1 << 4;
	public static final int PROP_Z = 1 << 5;
	public static final int PROP_VISIBLE = 1 << 6;
	public static final int PROP_TINT = 1 << 7;
	/** Every defined property bit; masks carrying anything else are rejected outright. */
	public static final int KNOWN_PROPS_MASK = 0xFF;

	// Canvas op ids (v2 replaces CommandEnum; the Transelate typo dies here)
	public static final byte OP_FILL = 1;
	public static final byte OP_PLOT = 2;
	public static final byte OP_LINE = 3;
	public static final byte OP_RECT = 4;
	public static final byte OP_FILL_RECT = 5;
	public static final byte OP_TRIANGLE = 6;
	public static final byte OP_FILL_TRIANGLE = 7;
	public static final byte OP_OVAL = 8;
	public static final byte OP_FILL_OVAL = 9;
	public static final byte OP_CLEAR_RECT = 10;
	public static final byte OP_DRAW_TEXT = 11;
	public static final byte OP_DRAW_TEXTURE = 12;
	public static final byte OP_DRAW_TEXTURE_SUB = 13;
	public static final byte OP_SET_COLOR = 14;
	public static final byte OP_TRANSLATE = 15;
	public static final byte OP_ROTATE = 16;
	public static final byte OP_ROTATE_AROUND = 17;
	public static final byte OP_SCALE = 18;
	public static final byte OP_PUSH = 19;
	public static final byte OP_POP = 20;
	public static final byte OP_ORIGIN = 21;

	/**
	 * Numeric argument count per canvas op. DRAW_TEXT additionally carries a UTF string.
	 * Index = op id; -1 marks an invalid op.
	 */
	private static final int[] CANVAS_OP_ARGS = new int[] {
		-1, // 0 unused
		0,  // FILL
		2,  // PLOT x,y
		4,  // LINE
		4,  // RECT
		4,  // FILL_RECT
		6,  // TRIANGLE
		6,  // FILL_TRIANGLE
		4,  // OVAL
		4,  // FILL_OVAL
		4,  // CLEAR_RECT
		2,  // DRAW_TEXT x,y (+ UTF)
		3,  // DRAW_TEXTURE id,x,y
		7,  // DRAW_TEXTURE_SUB id,x,y,tx,ty,w,h
		4,  // SET_COLOR r,g,b,a
		2,  // TRANSLATE
		1,  // ROTATE
		3,  // ROTATE_AROUND
		2,  // SCALE
		0,  // PUSH
		0,  // POP
		0,  // ORIGIN
	};

	public static int canvasOpArgCount(int op) {
		if (op <= 0 || op >= CANVAS_OP_ARGS.length)
			return -1;
		return CANVAS_OP_ARGS[op];
	}

	public static boolean isTransformOp(int op) {
		return op == OP_TRANSLATE || op == OP_ROTATE || op == OP_ROTATE_AROUND
				|| op == OP_SCALE || op == OP_PUSH || op == OP_POP || op == OP_ORIGIN;
	}

	/**
	 * Wraparound-safe sequence comparison (RFC 1982 style): positive when a is newer than b.
	 */
	public static int seqDelta(int a, int b) {
		return a - b;
	}

	/** FNV-1a 64-bit content hash for resource bytes. */
	public static long contentHash(byte[] data) {
		long hash = 0xcbf29ce484222325L;
		for (int i = 0; i < data.length; i++) {
			hash ^= (data[i] & 0xff);
			hash *= 0x100000001b3L;
		}
		return hash;
	}
}
