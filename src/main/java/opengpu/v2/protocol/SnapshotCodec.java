package opengpu.v2.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneCanvas;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.SceneState;

/**
 * Codec for {@link SceneSnapshot}: the manifest-only full-state resync payload. Texture
 * BYTES never ride a snapshot (ServerScene.snapshot() strips them; this codec has no field
 * for them) — recovered mirrors hold pending textures and request bodies separately.
 *
 * Canvas resources are restored via {@code new SceneCanvas + publish(commands)}, the
 * canonical restore path, so compaction replay-state is rebuilt identically to the source.
 * Decoding applies the same strictness rules as {@link BatchCodec}: unknown types, invalid
 * dimensions, out-of-range counts, truncation, and trailing data all throw.
 */
public final class SnapshotCodec {
	private SnapshotCodec() {}

	static final int MAX_ENTRIES = 1 << 16;

	public static byte[] encode(SceneSnapshot snapshot) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(snapshot.sceneId);
			out.writeInt(snapshot.epoch);
			out.writeInt(snapshot.seq);
			out.writeLong(snapshot.serverTick);
			SceneState state = snapshot.state;
			out.writeInt(state.nextResourceId);
			out.writeInt(state.nextNodeId);
			out.writeInt(state.resources.size());
			for (Map.Entry<Integer, ResourceInfo> e : state.resources.entrySet()) {
				ResourceInfo res = e.getValue();
				out.writeInt(res.id);
				out.writeByte(res.type);
				out.writeInt(res.width);
				out.writeInt(res.height);
				out.writeInt(res.sizeBytes);
				out.writeLong(res.hash);
				if (res.type == V2Wire.RES_CANVAS) {
					out.writeInt(res.canvas.commandCap);
					BatchCodec.writeCommands(out, res.canvas.visibleCommands());
				}
			}
			out.writeInt(state.nodes.size());
			for (Map.Entry<Integer, SceneNode> e : state.nodes.entrySet()) {
				SceneNode node = e.getValue();
				out.writeInt(node.id);
				out.writeByte(node.type);
				out.writeInt(node.ref);
				out.writeDouble(node.x);
				out.writeDouble(node.y);
				out.writeDouble(node.rot);
				out.writeDouble(node.sx);
				out.writeDouble(node.sy);
				out.writeInt(node.z);
				out.writeBoolean(node.visible);
				out.writeInt(node.tint);
			}
			byte[] encoded = bytes.toByteArray();
			// "Legal to create" must imply "deliverable": a snapshot the chunker cannot carry
			// would make the scene permanently unresyncable. Budget caps (open numbers in the
			// design doc) are bounded by this ceiling.
			if (encoded.length > FrameChunker.MAX_TRANSFER_BYTES)
				throw new IllegalStateException(
						"Snapshot exceeds transport capacity: " + encoded.length + " bytes");
			return encoded;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static SceneSnapshot decode(byte[] data) throws CodecException {
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
			SceneState state = new SceneState();
			state.nextResourceId = in.readInt();
			state.nextNodeId = in.readInt();
			int resCount = in.readInt();
			if (resCount < 0 || resCount > MAX_ENTRIES)
				throw new CodecException("Resource count out of range: " + resCount);
			for (int i = 0; i < resCount; i++) {
				int id = in.readInt();
				byte type = in.readByte();
				if (!V2Wire.isKnownResType(type))
					throw new CodecException("Unknown resource type " + type);
				int width = in.readInt();
				int height = in.readInt();
				int sizeBytes = in.readInt();
				long hash = in.readLong();
				if (width <= 0 || height <= 0
						|| width > V2Wire.MAX_TEXTURE_DIM || height > V2Wire.MAX_TEXTURE_DIM)
					throw new CodecException("Resource " + id + " has invalid dimensions");
				if (type == V2Wire.RES_TEXTURE && sizeBytes != (long) width * height * 4L)
					throw new CodecException("Resource " + id + " size does not match dimensions");
				if (state.resources.containsKey(id))
					throw new CodecException("Duplicate resource id " + id);
				ResourceInfo res = new ResourceInfo(id, type, width, height, sizeBytes, hash);
				if (type == V2Wire.RES_CANVAS) {
					int cap = in.readInt();
					ArrayList<opengpu.v2.scene.CanvasCommand> commands = BatchCodec.readCommands(in);
					SceneCanvas canvas = new SceneCanvas(width, height, cap);
					canvas.publish(commands);
					res.canvas = canvas;
				}
				state.resources.put(id, res);
			}
			int nodeCount = in.readInt();
			if (nodeCount < 0 || nodeCount > MAX_ENTRIES)
				throw new CodecException("Node count out of range: " + nodeCount);
			for (int i = 0; i < nodeCount; i++) {
				int id = in.readInt();
				byte type = in.readByte();
				if (!V2Wire.isKnownNodeType(type))
					throw new CodecException("Unknown node type " + type);
				int ref = in.readInt();
				SceneNode node = new SceneNode(id, type, ref);
				node.x = in.readDouble();
				node.y = in.readDouble();
				node.rot = in.readDouble();
				node.sx = in.readDouble();
				node.sy = in.readDouble();
				node.z = in.readInt();
				node.visible = in.readBoolean();
				node.tint = in.readInt();
				if (state.nodes.containsKey(id))
					throw new CodecException("Duplicate node id " + id);
				state.nodes.put(id, node);
			}
			if (in.read() != -1)
				throw new CodecException("Trailing data after snapshot");
			// Counter consistency: ids must never be reallocatable below existing entries
			// (recorded command lists reference them). A crafted snapshot must not be able to
			// plant a state that violates SceneState's no-reuse contract.
			if (state.nextResourceId < 1
					|| (!state.resources.isEmpty() && state.nextResourceId <= state.resources.lastKey()))
				throw new CodecException("Resource id counter inconsistent with contents");
			if (state.nextNodeId < 1
					|| (!state.nodes.isEmpty() && state.nextNodeId <= state.nodes.lastKey()))
				throw new CodecException("Node id counter inconsistent with contents");
			return new SceneSnapshot(sceneId, epoch, seq, tick, state);
		} catch (EOFException e) {
			throw new CodecException("Truncated snapshot", e);
		} catch (IOException e) {
			throw new CodecException("Malformed snapshot", e);
		} catch (IllegalArgumentException e) {
			throw new CodecException("Malformed snapshot: " + e.getMessage(), e);
		} catch (IllegalStateException e) {
			// SceneCanvas.publish cap breach on crafted input.
			throw new CodecException("Malformed snapshot: " + e.getMessage(), e);
		}
	}
}
