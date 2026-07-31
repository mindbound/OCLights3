package ds.mods.OCLights2.v2.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * The message envelope and the small-message codecs of protocol v2. Every v2 payload on the
 * wire travels as [byte msgKind][kind-specific payload]; batch payloads are
 * {@link BatchCodec} output, snapshots are {@link SnapshotCodec} output, and the small
 * messages (heartbeat, resync request, resource request/body) are encoded here. Each payload
 * carries the PROTOCOL_VERSION short as its first field, so a version mismatch fails at the
 * payload codec regardless of kind.
 *
 * Heartbeats are deliberately their own kind: a heartbeat is a seq-only probe
 * (SceneMirror.observeSeq), never an apply-able batch.
 */
public final class MessageCodec {
	private MessageCodec() {}

	public static final byte MSG_BATCH = 1;
	public static final byte MSG_SNAPSHOT = 2;
	public static final byte MSG_HEARTBEAT = 3;
	public static final byte MSG_RESYNC_REQUEST = 4;
	public static final byte MSG_RESOURCE_REQUEST = 5;
	public static final byte MSG_RESOURCE_BODY = 6;
	/** Scene destroyed / not served: mirrors evict on receipt (otherwise indistinguishable from loss). */
	public static final byte MSG_SCENE_GONE = 7;

	/** Max texture body: MAX_TEXTURE_DIM^2 * 4 bytes RGBA. */
	public static final long MAX_RESOURCE_BODY = (long) V2Wire.MAX_TEXTURE_DIM * V2Wire.MAX_TEXTURE_DIM * 4L;

	public static final class Heartbeat {
		public final String sceneId;
		public final int seq;

		public Heartbeat(String sceneId, int seq) {
			this.sceneId = sceneId;
			this.seq = seq;
		}
	}

	public static final class ResyncRequest {
		public final String sceneId;
		public final int lastSeq;

		public ResyncRequest(String sceneId, int lastSeq) {
			this.sceneId = sceneId;
			this.lastSeq = lastSeq;
		}
	}

	public static final class ResourceRequest {
		public final String sceneId;
		public final int resId;

		public ResourceRequest(String sceneId, int resId) {
			this.sceneId = sceneId;
			this.resId = resId;
		}
	}

	public static final class ResourceBody {
		public final String sceneId;
		public final int resId;
		public final byte[] bytes;

		public ResourceBody(String sceneId, int resId, byte[] bytes) {
			this.sceneId = sceneId;
			this.resId = resId;
			this.bytes = bytes;
		}
	}

	public static byte[] envelope(byte kind, byte[] payload) {
		byte[] out = new byte[payload.length + 1];
		out[0] = kind;
		System.arraycopy(payload, 0, out, 1, payload.length);
		return out;
	}

	public static byte kindOf(byte[] envelope) throws CodecException {
		if (envelope.length < 1)
			throw new CodecException("Empty envelope");
		byte kind = envelope[0];
		if (kind < MSG_BATCH || kind > MSG_SCENE_GONE)
			throw new CodecException("Unknown message kind " + kind);
		return kind;
	}

	public static byte[] payloadOf(byte[] envelope) throws CodecException {
		if (envelope.length < 1)
			throw new CodecException("Empty envelope");
		byte[] payload = new byte[envelope.length - 1];
		System.arraycopy(envelope, 1, payload, 0, payload.length);
		return payload;
	}

	// --- Small-message payload codecs ---

	public static byte[] encodeHeartbeat(Heartbeat hb) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		try {
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(hb.sceneId);
			out.writeInt(hb.seq);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return bytes.toByteArray();
	}

	public static Heartbeat decodeHeartbeat(byte[] data) throws CodecException {
		DataInputStream in = open(data);
		try {
			Heartbeat hb = new Heartbeat(in.readUTF(), in.readInt());
			expectEnd(in);
			return hb;
		} catch (IOException e) {
			throw wrap(e);
		}
	}

	public static byte[] encodeResyncRequest(ResyncRequest req) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		try {
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(req.sceneId);
			out.writeInt(req.lastSeq);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return bytes.toByteArray();
	}

	public static ResyncRequest decodeResyncRequest(byte[] data) throws CodecException {
		DataInputStream in = open(data);
		try {
			ResyncRequest req = new ResyncRequest(in.readUTF(), in.readInt());
			expectEnd(in);
			return req;
		} catch (IOException e) {
			throw wrap(e);
		}
	}

	public static byte[] encodeResourceRequest(ResourceRequest req) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		try {
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(req.sceneId);
			out.writeInt(req.resId);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return bytes.toByteArray();
	}

	public static ResourceRequest decodeResourceRequest(byte[] data) throws CodecException {
		DataInputStream in = open(data);
		try {
			ResourceRequest req = new ResourceRequest(in.readUTF(), in.readInt());
			expectEnd(in);
			return req;
		} catch (IOException e) {
			throw wrap(e);
		}
	}

	public static byte[] encodeResourceBody(ResourceBody body) {
		if (body.bytes.length > MAX_RESOURCE_BODY)
			throw new IllegalArgumentException("Resource body exceeds wire cap: " + body.bytes.length);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		try {
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(body.sceneId);
			out.writeInt(body.resId);
			out.writeInt(body.bytes.length);
			out.write(body.bytes);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return bytes.toByteArray();
	}

	public static ResourceBody decodeResourceBody(byte[] data) throws CodecException {
		DataInputStream in = open(data);
		try {
			String sceneId = in.readUTF();
			int resId = in.readInt();
			int len = in.readInt();
			// Also bound by the bytes actually present, so a tiny crafted message cannot
			// force a huge allocation from a claimed length (available() is exact here).
			if (len < 0 || len > MAX_RESOURCE_BODY || len > in.available())
				throw new CodecException("Resource body length out of range: " + len);
			byte[] bytes = new byte[len];
			in.readFully(bytes);
			ResourceBody body = new ResourceBody(sceneId, resId, bytes);
			expectEnd(in);
			return body;
		} catch (IOException e) {
			throw wrap(e);
		}
	}

	public static final class SceneGone {
		public final String sceneId;

		public SceneGone(String sceneId) {
			this.sceneId = sceneId;
		}
	}

	public static byte[] encodeSceneGone(SceneGone gone) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		try {
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(gone.sceneId);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return bytes.toByteArray();
	}

	public static SceneGone decodeSceneGone(byte[] data) throws CodecException {
		DataInputStream in = open(data);
		try {
			SceneGone gone = new SceneGone(in.readUTF());
			expectEnd(in);
			return gone;
		} catch (IOException e) {
			throw wrap(e);
		}
	}

	private static DataInputStream open(byte[] data) throws CodecException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		try {
			short version = in.readShort();
			if (version != V2Wire.PROTOCOL_VERSION)
				throw new CodecException("Unsupported protocol version " + version);
		} catch (IOException e) {
			throw wrap(e);
		}
		return in;
	}

	private static void expectEnd(DataInputStream in) throws IOException, CodecException {
		if (in.read() != -1)
			throw new CodecException("Trailing data after message");
	}

	private static CodecException wrap(IOException e) {
		if (e instanceof EOFException)
			return new CodecException("Truncated message", e);
		return new CodecException("Malformed message", e);
	}
}
