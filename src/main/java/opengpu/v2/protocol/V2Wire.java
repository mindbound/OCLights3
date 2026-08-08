package opengpu.v2.protocol;

/**
 * Wire-level constants for protocol v2. Every id here is an explicit constant — nothing on
 * the wire may ever depend on enum ordinals or registration order (the legacy protocol's
 * central fragility). Changing PROTOCOL_VERSION is a hard compatibility break by design:
 * decoders reject anything else.
 */
public final class V2Wire {
	private V2Wire() {}

	// v3: mutable texture content. Region writes (DELTA_TEX_WRITE) ride the batch stream, a
	// monotone per-resource version replaces the content hash as the sync identity, and the
	// snapshot manifest / resource messages carry (version, knownHash).
	// v2 was: scene incarnation epoch in batch/snapshot/heartbeat headers.
	// Discipline: any layout change bumps this in the same change.
	/**
	 * Bumped 3 -> 4 on 2026-08-08 when OP_SET_FONT was added.
	 *
	 * THE BUMP AND THE OP TABLE MUST MOVE TOGETHER, and that is the only thing protecting
	 * against silent corruption here. The decoder tests this for strict equality and rejects
	 * unknown ops outright, so a client of the wrong vintage discards the whole batch before
	 * it can misread anything — which is why the renderer can assert that unknown ops cannot
	 * arrive. Add an op without bumping this and that guarantee inverts: two builds share a
	 * version number, disagree about the op table, and an old client decodes the new op's
	 * argument as some other op's payload. See ProtocolVersionTest.
	 */
	public static final short PROTOCOL_VERSION = 4;

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
	/** Packed-RGBA region write into a texture (v3). Always carries its pixels. */
	public static final byte DELTA_TEX_WRITE = 11;

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

	/** One writeRegion payload: 64x64 RGBA. Producer cap AND decoder cap. */
	public static final int MAX_WRITE_REGION_BYTES = 16384;
	/**
	 * Aggregate texture-write payload admitted per scene per TICK. Equal to the per-call cap
	 * deliberately: one full-size write per tick is expressible and is the documented
	 * contract. Raising this is gated on batch compression landing — every number here is a
	 * function of whether that exists.
	 */
	public static final int MAX_WRITE_BYTES_PER_TICK = 16384;

	/**
	 * Texture-write payload one BATCH may carry. Twice the per-tick allowance, and the factor
	 * of two is structural rather than a safety margin.
	 *
	 * A batch can legitimately carry TWO ticks' worth of admitted payload. OC callbacks are
	 * {@code direct=true}, so they run on a machine executor thread, not the server thread — a
	 * write admitted in the window between the END-phase seal and the next START-phase grant is
	 * charged to tick T's allowance but staged into the batch that seals at the END of T+1.
	 * {@code stagedWriteBytes} resets at seal; {@code tickWriteBytes} resets at the tick change;
	 * they are deliberately different counters and the batch one therefore spans strictly more.
	 *
	 * Bounding a batch by ONE tick's number is short by construction, and the failure is not a
	 * throttle but a refusal the caller cannot clear by waiting: the retry lands in the same
	 * unsealed batch and is refused identically. The identical mistake on the submit path is
	 * what made a frame over 64 KiB undeliverable — see MAX_SUBMIT_BYTES_PER_BATCH.
	 *
	 * MUST move together with the decoder check in BatchCodec, which bounds a decoded batch by
	 * this same quantity. A producer admitting more than the decoder accepts loses the whole
	 * batch at every receiver rather than throttling anyone.
	 */
	public static final int MAX_WRITE_BYTES_PER_BATCH = 2 * MAX_WRITE_BYTES_PER_TICK;

	/**
	 * Packed command bytes ONE canvasSubmit call may carry. A per-CALL ceiling and nothing else.
	 *
	 * Its own bound, NOT the texture-write budget: these pace different things (command lists
	 * versus pixel payloads) and reusing a neighbouring concept's number is how this codebase
	 * has repeatedly produced bounds that were wrong for what they were bounding.
	 *
	 * It bounds one {@code checkByteArray} copy and one {@code decodeCommandList} allocation.
	 * It is also bracketed from below by the widest single command — an OP_DRAW_TEXT at
	 * MAX_TEXT_CHARS is ~24.6 KB, so the ceiling must clear several times that to be usable —
	 * and from above by the caller's own memory: the payload is a Lua string built with
	 * table.concat, needing the parts table and the result live at once, against 192 KiB of
	 * Lua RAM on a tier-1 machine.
	 *
	 * This constant used to serve as the per-tick and per-batch allowance as well. That is what
	 * made any frame larger than one submit undeliverable: the library chunks at this ceiling,
	 * so chunk 1 consumed the entire tick AND batch allowance and left less than one command's
	 * worth for chunk 2 — provably, for any op mix, with no contention. See PERF-BASELINE.md.
	 */
	public static final int MAX_SUBMIT_BYTES = 65536;

	/**
	 * Packed command bytes admitted per scene per TICK. The rate limiter.
	 *
	 * Sized to the MEASURED case rather than the theoretical maximum: the largest frame anyone
	 * has actually published is ~99 KB (3001 commands, PERF-BASELINE.md), which chunks to two
	 * submits. Two ceilings admits that in a single tick, so it lands in ONE batch — and
	 * SceneMirror.applyBatch applies a whole batch before setting {@code dirty}, so a frame that
	 * lands in one batch is never rendered half-drawn.
	 *
	 * Deliberately NOT sized to a full 4096-command canvas (~233 KB, four chunks). That frame
	 * still completes, over two ticks, via the admission retry the batch bound now makes work;
	 * it may show torn for one tick, which is the seal race that server-side frame assembly
	 * (DESIGN-RENDERER-V2 § scene.begin/commit) exists to close properly. Buying tear-freedom
	 * for a frame size nobody has published, by doubling the sustained bandwidth a single Lua
	 * program can drive, is the trade this project keeps getting wrong in the generous
	 * direction. Raise it when a measurement asks for it, which is cheap; lowering a shipped
	 * allowance is not.
	 *
	 * At 20 ticks/s this permits 2.6 MB/s per watcher per scene pre-DEFLATE.
	 */
	public static final int MAX_SUBMIT_BYTES_PER_TICK = 2 * MAX_SUBMIT_BYTES;

	/**
	 * Packed command bytes one BATCH may carry. Twice the per-tick allowance, for exactly the
	 * reason given on MAX_WRITE_BYTES_PER_BATCH: a batch spans up to two tick allowances because
	 * direct callbacks run off the server thread and can land in the inter-tick window.
	 *
	 * This is the constant whose absence produced the confirmed defect. With the batch bounded
	 * by one tick's worth, canvasSubmit's {@code consumeCallBudget} retry — which exists to make
	 * a refusal transparent to Lua — could not clear: at the next tick's synchronized replay
	 * {@code tickSubmitBytes} had reset at START but {@code stagedSubmitBytes} had not, because
	 * it only resets at the END-phase seal, which had not run yet. Verified in-game 2026-08-04.
	 */
	public static final int MAX_SUBMIT_BYTES_PER_BATCH = 2 * MAX_SUBMIT_BYTES_PER_TICK;

	/**
	 * Encoded command bytes a scene may HOLD across all its canvases at once.
	 *
	 * MAX_SUBMIT_BYTES paces the rate commands arrive; this bounds the standing total, and the
	 * two are genuinely different quantities. Nothing bounded the total before canvasSubmit
	 * existed, but only because it was vacuous: no code path could put a command into a
	 * non-display canvas, so every offscreen canvas was provably empty and the display
	 * canvas's own commandCap was the whole story.
	 *
	 * The standing total is what a RESYNC SNAPSHOT carries, in full, to every client entering
	 * range — and what the save writes to disk on every autosave. The VRAM budget does bound
	 * it, but only at ~26 MB, because CANVAS_SLOT_COST prices a slot at 32 bytes as a proxy
	 * for the client's FBO while a filled slot costs up to 57 bytes on the wire (and an
	 * OP_DRAW_TEXT slot far more). Pricing VRAM by wire cost would be the same borrowed-bound
	 * mistake in the other direction, so the wire total gets its own explicit budget.
	 *
	 * 2 MiB: comfortably over any plausible real scene (a full 4096-command canvas of the
	 * widest op is ~233 KB) and comfortably under MAX_INFLATED_BYTES, which the snapshot must
	 * also satisfy.
	 */
	public static final int MAX_STANDING_COMMAND_BYTES = 2 * 1024 * 1024;

	/** Self-describing persisted body blob: 'OGPB'. */
	public static final int PERSIST_BODY_MAGIC = 0x4F475042;
	public static final short PERSIST_BODY_FORMAT = 1;

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
	/**
	 * "This transform change is a JUMP — do not interpolate it."
	 *
	 * Carries a 0/1 value like PROP_VISIBLE rather than being a valueless flag, because the
	 * codec derives the value count from Integer.bitCount(mask); a bit without a value would
	 * desynchronise every field after it.
	 *
	 * Transient by nature: it describes a transition, not state, so nothing stores it on
	 * SceneNode and it never reaches a save — snapshots encode node VALUES, not deltas. That
	 * is why widening the mask needs no PROTOCOL_VERSION bump and no save migration, the same
	 * reasoning as the compression sentinel in BatchCodec.
	 */
	public static final int PROP_TELEPORT = 1 << 8;
	/** Every defined property bit; masks carrying anything else are rejected outright. */
	public static final int KNOWN_PROPS_MASK = 0x1FF;

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
	 * Select the font subsequent OP_DRAW_TEXT commands use. Ambient state with exactly
	 * OP_SET_COLOR's lifecycle: unscoped by PUSH/POP, and reset to {@link #FONT_DEFAULT} at
	 * the start of every canvas replay. The reset is load-bearing — without it a canvas that
	 * selected a font would leak it into whichever canvas replayed next, so the symptom would
	 * follow node draw order and read as nondeterministic.
	 */
	public static final byte OP_SET_FONT = 22;

	/**
	 * GNU Unifont, read at runtime from OpenComputers. 8x16 cells, ~75,000 glyphs covering
	 * the Basic Multilingual Plane and beyond. The default because it is the one that can
	 * render an arbitrary string.
	 */
	public static final byte FONT_DEFAULT = 0;
	/**
	 * unscii-8, bundled. 8x8 cells, ~3,190 glyphs: complete Box Drawing, Block Elements,
	 * Geometric Shapes and Braille, and NO CJK, kana or Hangul whatsoever. Half the cell
	 * height, and drawn for an 8px box rather than scaled down from 16.
	 */
	public static final byte FONT_UNSCII8 = 1;
	/** Ids are dense from 0; anything at or above this is rejected rather than clamped. */
	public static final int FONT_COUNT = 2;

	public static boolean isValidFont(int fontId) {
		return fontId >= 0 && fontId < FONT_COUNT;
	}

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
		1,  // SET_FONT fontId
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
