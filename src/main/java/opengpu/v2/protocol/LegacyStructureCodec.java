package opengpu.v2.protocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;

import opengpu.v2.scene.CanvasCommand;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneCanvas;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.SceneState;

/**
 * Reads the v2 persisted structure layout so existing worlds survive the v3 upgrade.
 *
 * PERSISTENCE ONLY. The network decoders stay strict at the current PROTOCOL_VERSION: a v2
 * *peer* is an error, a v2 *save* is not. Dispatch happens by peeking the leading version
 * short BEFORE attempting a v3 decode — never from inside a catch, because the caller
 * responds to a codec failure by deleting the scene's stored bodies.
 *
 * The only difference from v3 is the resource manifest record: v2 carried a single content
 * hash where v3 carries (version, knownHashVersion, knownHash).
 *
 * DELETE AT FORMAT FREEZE.
 */
public final class LegacyStructureCodec {
	private LegacyStructureCodec() {}

	public static final short V2_VERSION = 2;

	/** The protocol version a persisted structure was written with, or -1 if unreadable. */
	public static short peekVersion(byte[] data) {
		if (data == null || data.length < 2)
			return -1;
		return (short) (((data[0] & 0xFF) << 8) | (data[1] & 0xFF));
	}

	public static SceneSnapshot decodeV2(byte[] data) throws CodecException {
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
			short version = in.readShort();
			if (version != V2_VERSION)
				throw new CodecException("Not a v2 structure: version " + version);
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
			if (resCount < 0 || resCount > 1 << 16)
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
				ResourceInfo res = new ResourceInfo(id, type, width, height, sizeBytes);
				// Everything a v2 world contains is, by definition, version 1 content.
				res.version = 0; // set when the body is attached
				res.latestVersion = 1;
				res.knownHash = hash;
				res.knownHashVersion = 1;
				if (type == V2Wire.RES_CANVAS) {
					int cap = in.readInt();
					ArrayList<CanvasCommand> commands = BatchCodec.readCommands(in);
					SceneCanvas canvas = new SceneCanvas(width, height, cap);
					canvas.publish(commands);
					res.canvas = canvas;
				}
				state.resources.put(id, res);
			}
			int nodeCount = in.readInt();
			if (nodeCount < 0 || nodeCount > 1 << 16)
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
				throw new CodecException("Trailing data after v2 structure");
			if (!state.resources.isEmpty() && state.nextResourceId <= state.resources.lastKey())
				throw new CodecException("nextResourceId is behind the highest resource id");
			if (!state.nodes.isEmpty() && state.nextNodeId <= state.nodes.lastKey())
				throw new CodecException("nextNodeId is behind the highest node id");
			return new SceneSnapshot(sceneId, epoch, seq, tick, state);
		} catch (EOFException e) {
			throw new CodecException("Truncated v2 structure", e);
		} catch (IOException e) {
			throw new CodecException("Malformed v2 structure", e);
		} catch (IllegalArgumentException e) {
			throw new CodecException("Malformed v2 structure: " + e.getMessage(), e);
		} catch (IllegalStateException e) {
			// SceneCanvas.publish throws this on a command-list cap overflow. Without the
			// catch a corrupt v2 save escapes as an unchecked exception through chunk load
			// instead of degrading to a fresh scene.
			throw new CodecException("Malformed v2 structure: " + e.getMessage(), e);
		}
	}
}
