package opengpu.v2.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;

import opengpu.v2.scene.CanvasCommand;

/**
 * Binary codec for {@link SceneBatch}. Wire layout:
 *
 *   [short PROTOCOL_VERSION][UTF sceneId][int seq][long serverTick][int deltaCount]
 *   then per delta: [byte typeId][payload]
 *
 * Canvas commands encode as [byte op][double args...][UTF text if OP_DRAW_TEXT].
 * NodeProps values encode as plain doubles; VISIBLE travels as 0/1 and TINT as the exact
 * double representation of the unsigned 32-bit ARGB value (0..2^32-1 — exactly representable
 * in a double); appliers recover it with (int)(long)value.
 *
 * Decoding is strict: any unknown version/type/op/mask-bit, truncation, TRAILING DATA, or
 * count above the sanity caps throws {@link CodecException}. Sanity caps exist so a garbage
 * payload cannot force a huge allocation before the structure check fails.
 */
public final class BatchCodec {
	private BatchCodec() {}

	/**
	 * Leading short of a DEFLATE-wrapped batch: [short -2][int rawLen][deflated raw batch].
	 *
	 * A sentinel rather than a PROTOCOL_VERSION bump on purpose. The version is shared by
	 * the batch, snapshot and message codecs AND by the persisted structure, so bumping it
	 * for a batch-only change would force a save migration for nothing. Protocol versions
	 * are positive, so a negative marker can never collide, and the inflated payload still
	 * carries the real version — the strictness of the inner decode is unchanged.
	 */
	static final short COMPRESSED_MARKER = -2;

	/** Below this, framing overhead outweighs any saving; small batches ship raw. */
	static final int COMPRESS_THRESHOLD_BYTES = 256;

	/**
	 * Hard ceiling on a declared inflated size, refused BEFORE any allocation.
	 *
	 * Sized to what a batch can legitimately be, NOT to the transport ceiling. The transport
	 * ceiling exists for resource bodies (up to a 256 MiB texture), and reusing it here would
	 * let a few KB of deflate claim a quarter-gigabyte per inbound batch — reopening the
	 * amplification this constant exists to close. One batch is at most a tick's worth of
	 * deltas: the texture-write payload is capped at 16 KiB, and the largest canvas publish
	 * this server produces is a few hundred KB, so 4 MiB is generous headroom.
	 */
	static final int MAX_INFLATED_BYTES = 4 * 1024 * 1024;

	public static byte[] encode(SceneBatch batch) {
		byte[] raw = encodeRaw(batch);
		if (raw.length < COMPRESS_THRESHOLD_BYTES) {
			return raw;
		}
		java.util.zip.Deflater deflater = new java.util.zip.Deflater(
				java.util.zip.Deflater.BEST_SPEED);
		try {
			deflater.setInput(raw);
			deflater.finish();
			// Worst case for incompressible input is slightly larger than the input; give
			// the buffer room so a single deflate() call always completes.
			byte[] buffer = new byte[raw.length + 64];
			int compressed = deflater.deflate(buffer);
			if (!deflater.finished() || compressed + 6 >= raw.length) {
				return raw; // incompressible: the wrapper would only add bytes
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(compressed + 6);
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(COMPRESSED_MARKER);
			out.writeInt(raw.length);
			out.write(buffer, 0, compressed);
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			deflater.end();
		}
	}

	private static byte[] encodeRaw(SceneBatch batch) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(batch.sceneId);
			out.writeInt(batch.epoch);
			out.writeInt(batch.seq);
			out.writeLong(batch.serverTick);
			out.writeInt(batch.deltas.size());
			for (Delta d : batch.deltas) {
				out.writeByte(d.typeId());
				writeDelta(out, d);
			}
			return bytes.toByteArray();
		} catch (IOException e) {
			// The only reachable IOException here would be writeUTF's 65535-byte limit, and
			// CanvasCommand's MAX_TEXT_CHARS constructor cap keeps every string far below it —
			// so this indicates a bug (an invariant bypassed), not user input.
			throw new RuntimeException(e);
		}
	}

	private static void writeDelta(DataOutputStream out, Delta d) throws IOException {
		if (d instanceof Delta.NodeCreate) {
			Delta.NodeCreate n = (Delta.NodeCreate) d;
			out.writeInt(n.nodeId);
			out.writeByte(n.nodeType);
			out.writeInt(n.ref);
		} else if (d instanceof Delta.NodeFree) {
			out.writeInt(((Delta.NodeFree) d).nodeId);
		} else if (d instanceof Delta.NodeProps) {
			Delta.NodeProps n = (Delta.NodeProps) d;
			out.writeInt(n.nodeId);
			out.writeInt(n.mask);
			for (double v : n.values) {
				out.writeDouble(v);
			}
		} else if (d instanceof Delta.ResourceCreate) {
			Delta.ResourceCreate r = (Delta.ResourceCreate) d;
			out.writeInt(r.resId);
			out.writeByte(r.resType);
			out.writeInt(r.width);
			out.writeInt(r.height);
			out.writeInt(r.sizeBytes);
			out.writeLong(r.hash);
			out.writeInt(r.commandCap);
		} else if (d instanceof Delta.ResourceFree) {
			out.writeInt(((Delta.ResourceFree) d).resId);
		} else if (d instanceof Delta.CanvasPublish) {
			Delta.CanvasPublish c = (Delta.CanvasPublish) d;
			out.writeInt(c.resId);
			writeCommands(out, c.commands);
		} else if (d instanceof Delta.CanvasAppend) {
			Delta.CanvasAppend c = (Delta.CanvasAppend) d;
			out.writeInt(c.resId);
			writeCommands(out, c.commands);
		} else if (d instanceof Delta.TextureWrite) {
			Delta.TextureWrite t = (Delta.TextureWrite) d;
			out.writeInt(t.resId);
			out.writeInt(t.version);
			out.writeInt(t.x);
			out.writeInt(t.y);
			out.writeInt(t.w);
			out.writeInt(t.h);
			// No length prefix: w*h*4 is the single source of truth for the payload size.
			out.write(t.pixels);
		} else if (d instanceof Delta.SceneProp) {
			Delta.SceneProp s = (Delta.SceneProp) d;
			out.writeInt(s.propId);
			out.writeInt(s.payload.length);
			out.write(s.payload);
		} else {
			throw new IllegalArgumentException("Unencodable delta " + d.getClass());
		}
	}

	static void writeCommands(DataOutputStream out, java.util.List<CanvasCommand> commands) throws IOException {
		out.writeInt(commands.size());
		for (CanvasCommand cmd : commands) {
			out.writeByte(cmd.op);
			for (double a : cmd.args) {
				out.writeDouble(a);
			}
			if (cmd.op == V2Wire.OP_DRAW_TEXT) {
				out.writeUTF(cmd.text);
			}
		}
	}

	public static SceneBatch decode(byte[] data) throws CodecException {
		if (data != null && data.length >= 2
				&& (short) (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) == COMPRESSED_MARKER) {
			return decode(inflate(data));
		}
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
			short version = in.readShort();
			if (version != V2Wire.PROTOCOL_VERSION)
				throw new CodecException("Unsupported protocol version " + version);
			String sceneId = in.readUTF();
			int epoch = in.readInt();
			if (epoch == 0)
				throw new CodecException("Epoch 0 is reserved");
			int seq = in.readInt();
			long tick = in.readLong();
			int count = in.readInt();
			if (count < 0 || count > V2Wire.MAX_DELTAS)
				throw new CodecException("Delta count out of range: " + count);
			ArrayList<Delta> deltas = new ArrayList<Delta>(Math.min(count, 4096));
			int[] writeBytes = new int[1];
			for (int i = 0; i < count; i++) {
				deltas.add(readDelta(in, writeBytes));
			}
			if (in.read() != -1)
				throw new CodecException("Trailing data after batch");
			return new SceneBatch(sceneId, epoch, seq, tick, deltas);
		} catch (EOFException e) {
			throw new CodecException("Truncated batch", e);
		} catch (IOException e) {
			throw new CodecException("Malformed batch", e);
		} catch (IllegalArgumentException e) {
			// CanvasCommand constructor validation (bad op/arg shape from the wire).
			throw new CodecException("Malformed batch: " + e.getMessage(), e);
		}
	}

	/**
	 * Inflate a marker-wrapped batch. The declared size is validated BEFORE allocating, and
	 * the inflater is bounded by that allocation, so a crafted payload claiming gigabytes is
	 * refused rather than serviced. A stream that inflates to a different size than declared
	 * is a malformed batch, not a resizable one.
	 */
	private static byte[] inflate(byte[] data) throws CodecException {
		if (data.length < 6)
			throw new CodecException("Truncated compressed batch");
		int rawLen = ((data[2] & 0xFF) << 24) | ((data[3] & 0xFF) << 16)
				| ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
		if (rawLen < 2 || rawLen > MAX_INFLATED_BYTES)
			throw new CodecException("Compressed batch declares an unusable size: " + rawLen);
		java.util.zip.Inflater inflater = new java.util.zip.Inflater();
		try {
			inflater.setInput(data, 6, data.length - 6);
			byte[] raw = new byte[rawLen];
			int produced = inflater.inflate(raw);
			if (produced != rawLen || !inflater.finished())
				throw new CodecException("Compressed batch does not inflate to its declared size");
			// The inner decode's no-trailing-data rule must survive the wrapper: an inflater
			// simply stops at the end of the deflate stream and never looks at what follows,
			// so without this check the compressed path silently accepts appended garbage
			// that the raw path rejects.
			if (inflater.getRemaining() != 0)
				throw new CodecException("Trailing data after compressed batch");
			// encode() wraps at most once and encodeRaw() always leads with the positive
			// PROTOCOL_VERSION, so a marker INSIDE an inflated payload is hostile or corrupt.
			// Refusing it pins decode's recursion at one level: without this, N nested
			// wrappers recurse N deep with every level's byte[] simultaneously live, so a
			// few hundred KB on the wire becomes hundreds of MB and a StackOverflowError —
			// which escapes the CodecException-only catch in the inbound drain and takes the
			// client down. That is the very amplification the size ceiling above exists to
			// refuse, so the ceiling must not be bypassable by nesting.
			if (raw.length >= 2
					&& (short) (((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)) == COMPRESSED_MARKER)
				throw new CodecException("Nested compressed batch");
			return raw;
		} catch (java.util.zip.DataFormatException e) {
			throw new CodecException("Malformed compressed batch", e);
		} finally {
			inflater.end();
		}
	}

	/**
	 * @param writeBytes single-element accumulator of texture-write payload bytes seen so far
	 *                   in this batch; the per-tick aggregate cap is enforced against it so a
	 *                   batch cannot smuggle unbounded pixel data past the per-call cap.
	 */
	private static Delta readDelta(DataInputStream in, int[] writeBytes) throws IOException, CodecException {
		byte type = in.readByte();
		switch (type) {
			case V2Wire.DELTA_NODE_CREATE: {
				int nodeId = in.readInt();
				byte nodeType = in.readByte();
				if (!V2Wire.isKnownNodeType(nodeType))
					throw new CodecException("Unknown node type " + nodeType);
				return new Delta.NodeCreate(nodeId, nodeType, in.readInt());
			}
			case V2Wire.DELTA_NODE_FREE:
				return new Delta.NodeFree(in.readInt());
			case V2Wire.DELTA_NODE_PROPS: {
				int nodeId = in.readInt();
				int mask = in.readInt();
				if ((mask & ~V2Wire.KNOWN_PROPS_MASK) != 0)
					throw new CodecException("Unknown prop mask bits in " + mask);
				int bits = Integer.bitCount(mask);
				double[] values = new double[bits];
				for (int i = 0; i < bits; i++) {
					values[i] = in.readDouble();
				}
				return new Delta.NodeProps(nodeId, mask, values);
			}
			case V2Wire.DELTA_RES_CREATE: {
				int resId = in.readInt();
				byte resType = in.readByte();
				if (!V2Wire.isKnownResType(resType))
					throw new CodecException("Unknown resource type " + resType);
				return new Delta.ResourceCreate(resId, resType, in.readInt(),
						in.readInt(), in.readInt(), in.readLong(), in.readInt());
			}
			case V2Wire.DELTA_RES_FREE:
				return new Delta.ResourceFree(in.readInt());
			case V2Wire.DELTA_CANVAS_PUBLISH: {
				int resId = in.readInt();
				return new Delta.CanvasPublish(resId, readCommands(in));
			}
			case V2Wire.DELTA_CANVAS_APPEND: {
				int resId = in.readInt();
				return new Delta.CanvasAppend(resId, readCommands(in));
			}
			case V2Wire.DELTA_TEX_WRITE: {
				int resId = in.readInt();
				int version = in.readInt();
				int x = in.readInt();
				int y = in.readInt();
				int w = in.readInt();
				int h = in.readInt();
				// Validate everything BEFORE allocating: a hostile header must not be able to
				// make us reserve memory it never intends to fill.
				if (w < 1 || h < 1 || w > V2Wire.MAX_TEXTURE_DIM || h > V2Wire.MAX_TEXTURE_DIM)
					throw new CodecException("Texture write region out of range: " + w + "x" + h);
				if (x < 0 || y < 0)
					throw new CodecException("Texture write origin out of range: " + x + "," + y);
				if (version < 1)
					throw new CodecException("Texture write version out of range: " + version);
				long len = (long) w * (long) h * 4L;
				if (len > V2Wire.MAX_WRITE_REGION_BYTES)
					throw new CodecException("Texture write payload too large: " + len);
				if (len > in.available())
					throw new CodecException("Texture write payload exceeds remaining data");
				writeBytes[0] += (int) len;
				if (writeBytes[0] > V2Wire.MAX_WRITE_BYTES_PER_TICK)
					throw new CodecException("Batch texture-write payload over the per-tick cap: "
							+ writeBytes[0]);
				byte[] pixels = new byte[(int) len];
				in.readFully(pixels);
				return new Delta.TextureWrite(resId, version, x, y, w, h, pixels);
			}
			case V2Wire.DELTA_SCENE_PROP: {
				int propId = in.readInt();
				int len = in.readInt();
				if (len < 0 || len > V2Wire.MAX_SCENE_PROP_PAYLOAD)
					throw new CodecException("Scene prop payload out of range: " + len);
				byte[] payload = new byte[len];
				in.readFully(payload);
				return new Delta.SceneProp(propId, payload);
			}
			default:
				throw new CodecException("Unknown delta type " + type);
		}
	}

	/**
	 * Decode a standalone packed command list — the payload Lua hands to canvasSubmit.
	 *
	 * Deliberately the SAME reader the batch codec uses, so the format a program packs is
	 * definitionally the format the wire carries; a second parser would be a second thing to
	 * keep in step. Trailing bytes are rejected for the same reason the batch decoder rejects
	 * them: a payload the sender thought meant something else is not a payload to guess at.
	 */
	public static ArrayList<CanvasCommand> decodeCommandList(byte[] data) throws CodecException {
		return decodeCommandList(data, V2Wire.MAX_COMMANDS);
	}

	/**
	 * Decode a standalone command list, refusing more than {@code maxCommands} of them.
	 *
	 * The bound matters because a payload's BYTE length does not bound its command COUNT: the
	 * zero-arity ops encode in one byte, so a 64 KiB list can declare 65,532 commands. Callers
	 * pass the count the target will actually accept (a canvas's command cap), and because the
	 * count is the first field on the wire the refusal costs one readInt and no allocation.
	 */
	public static ArrayList<CanvasCommand> decodeCommandList(byte[] data, int maxCommands)
			throws CodecException {
		if (data == null)
			throw new CodecException("Command payload required");
		try {
			DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
			ArrayList<CanvasCommand> commands = readCommands(in, maxCommands);
			if (in.read() != -1)
				throw new CodecException("Trailing data after command list");
			return commands;
		} catch (java.io.EOFException e) {
			throw new CodecException("Truncated command list", e);
		} catch (IOException e) {
			throw new CodecException("Malformed command list", e);
		} catch (IllegalArgumentException e) {
			throw new CodecException("Malformed command list: " + e.getMessage(), e);
		}
	}

	static ArrayList<CanvasCommand> readCommands(DataInputStream in) throws IOException, CodecException {
		return readCommands(in, V2Wire.MAX_COMMANDS);
	}

	static ArrayList<CanvasCommand> readCommands(DataInputStream in, int maxCommands)
			throws IOException, CodecException {
		int count = in.readInt();
		if (count < 0 || count > V2Wire.MAX_COMMANDS)
			throw new CodecException("Command count out of range: " + count);
		if (count > maxCommands)
			throw new CodecException("Command count " + count + " exceeds the limit of " + maxCommands);
		ArrayList<CanvasCommand> commands = new ArrayList<CanvasCommand>(Math.min(count, 4096));
		for (int i = 0; i < count; i++) {
			byte op = in.readByte();
			int argc = V2Wire.canvasOpArgCount(op);
			if (argc < 0)
				throw new CodecException("Unknown canvas op " + op);
			double[] args = new double[argc];
			for (int a = 0; a < argc; a++) {
				args[a] = in.readDouble();
			}
			String text = op == V2Wire.OP_DRAW_TEXT ? in.readUTF() : null;
			commands.add(new CanvasCommand(op, args, text));
		}
		return commands;
	}
}
