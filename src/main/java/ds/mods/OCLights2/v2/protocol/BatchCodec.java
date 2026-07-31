package ds.mods.OCLights2.v2.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;

import ds.mods.OCLights2.v2.scene.CanvasCommand;

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

	public static byte[] encode(SceneBatch batch) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(batch.sceneId);
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
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
			short version = in.readShort();
			if (version != V2Wire.PROTOCOL_VERSION)
				throw new CodecException("Unsupported protocol version " + version);
			String sceneId = in.readUTF();
			int seq = in.readInt();
			long tick = in.readLong();
			int count = in.readInt();
			if (count < 0 || count > V2Wire.MAX_DELTAS)
				throw new CodecException("Delta count out of range: " + count);
			ArrayList<Delta> deltas = new ArrayList<Delta>(Math.min(count, 4096));
			for (int i = 0; i < count; i++) {
				deltas.add(readDelta(in));
			}
			if (in.read() != -1)
				throw new CodecException("Trailing data after batch");
			return new SceneBatch(sceneId, seq, tick, deltas);
		} catch (EOFException e) {
			throw new CodecException("Truncated batch", e);
		} catch (IOException e) {
			throw new CodecException("Malformed batch", e);
		} catch (IllegalArgumentException e) {
			// CanvasCommand constructor validation (bad op/arg shape from the wire).
			throw new CodecException("Malformed batch: " + e.getMessage(), e);
		}
	}

	private static Delta readDelta(DataInputStream in) throws IOException, CodecException {
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

	static ArrayList<CanvasCommand> readCommands(DataInputStream in) throws IOException, CodecException {
		int count = in.readInt();
		if (count < 0 || count > V2Wire.MAX_COMMANDS)
			throw new CodecException("Command count out of range: " + count);
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
