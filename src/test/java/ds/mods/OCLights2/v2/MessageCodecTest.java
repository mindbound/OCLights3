package ds.mods.OCLights2.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;

import org.junit.Test;

import ds.mods.OCLights2.v2.protocol.CodecException;
import ds.mods.OCLights2.v2.protocol.Delta;
import ds.mods.OCLights2.v2.protocol.MessageCodec;
import ds.mods.OCLights2.v2.protocol.SnapshotCodec;
import ds.mods.OCLights2.v2.protocol.V2Wire;
import ds.mods.OCLights2.v2.scene.CanvasCommand;
import ds.mods.OCLights2.v2.scene.SceneMirror;
import ds.mods.OCLights2.v2.scene.SceneSnapshot;
import ds.mods.OCLights2.v2.scene.ServerScene;

public class MessageCodecTest {

	@Test
	public void envelopeRoundTripsKindAndPayload() throws Exception {
		byte[] payload = { 1, 2, 3, 4 };
		byte[] envelope = MessageCodec.envelope(MessageCodec.MSG_HEARTBEAT, payload);
		assertEquals(MessageCodec.MSG_HEARTBEAT, MessageCodec.kindOf(envelope));
		assertArrayEquals(payload, MessageCodec.payloadOf(envelope));
	}

	@Test
	public void unknownKindIsRejected() {
		try {
			MessageCodec.kindOf(new byte[] { 42, 0, 0 });
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("kind"));
		}
	}

	@Test
	public void heartbeatRoundTrips() throws Exception {
		byte[] data = MessageCodec.encodeHeartbeat(new MessageCodec.Heartbeat("scene-a", 9, 41));
		MessageCodec.Heartbeat hb = MessageCodec.decodeHeartbeat(data);
		assertEquals("scene-a", hb.sceneId);
		assertEquals(9, hb.epoch);
		assertEquals(41, hb.seq);
	}

	@Test
	public void resyncRequestRoundTrips() throws Exception {
		byte[] data = MessageCodec.encodeResyncRequest(new MessageCodec.ResyncRequest("s", -7));
		MessageCodec.ResyncRequest req = MessageCodec.decodeResyncRequest(data);
		assertEquals("s", req.sceneId);
		assertEquals(-7, req.lastSeq);
	}

	@Test
	public void resourceBodyRoundTripsAndRejectsBadLength() throws Exception {
		byte[] bytes = { 9, 8, 7 };
		byte[] data = MessageCodec.encodeResourceBody(new MessageCodec.ResourceBody("s", 3, bytes));
		MessageCodec.ResourceBody body = MessageCodec.decodeResourceBody(data);
		assertEquals(3, body.resId);
		assertArrayEquals(bytes, body.bytes);

		// Corrupt the length field to something huge.
		data[2 + 2 + 1 + 4] = (byte) 0x7F;
		try {
			MessageCodec.decodeResourceBody(data);
			fail("expected CodecException");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void sceneGoneRoundTrips() throws Exception {
		byte[] data = MessageCodec.encodeSceneGone(new MessageCodec.SceneGone("dead-scene"));
		assertEquals("dead-scene", MessageCodec.decodeSceneGone(data).sceneId);
	}

	@Test
	public void resourceBodyClaimedLengthBeyondAvailableIsRejected() throws Exception {
		byte[] data = MessageCodec.encodeResourceBody(
				new MessageCodec.ResourceBody("s", 3, new byte[] { 9, 8, 7 }));
		// Bump the claimed length by one past the actual payload: must throw before
		// allocating from the claim.
		data[2 + 3 + 4 + 3] = 4;
		try {
			MessageCodec.decodeResourceBody(data);
			fail("expected CodecException");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void epochZeroHeartbeatIsRejected() {
		byte[] data = MessageCodec.encodeHeartbeat(new MessageCodec.Heartbeat("s", 1, 1));
		// Epoch int after [short version][UTF "s"]: offsets 5..8.
		for (int i = 5; i <= 8; i++) {
			data[i] = 0;
		}
		try {
			MessageCodec.decodeHeartbeat(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("Epoch"));
		}
	}

	@Test
	public void trailingDataIsRejected() {
		byte[] data = MessageCodec.encodeHeartbeat(new MessageCodec.Heartbeat("s", 1, 1));
		byte[] extended = java.util.Arrays.copyOf(data, data.length + 3);
		try {
			MessageCodec.decodeHeartbeat(extended);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("Trailing"));
		}
	}

	@Test
	public void snapshotCodecRoundTripsFullState() throws Exception {
		ServerScene server = new ServerScene("scene-x");
		int canvas = server.createCanvas(64, 48, 512);
		int texture = server.createTexture(4, 4, new byte[64]);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		ArrayList<CanvasCommand> cmds = new ArrayList<CanvasCommand>();
		cmds.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, 9, 9, 9, 255));
		cmds.add(CanvasCommand.text(1, 2, "snap"));
		server.canvasAppend(canvas, cmds);
		server.setTransform(node, 3, 4, 0.5, 1, 1);
		server.setCurrentTick(77);
		server.sealBatch();

		SceneSnapshot snapshot = server.snapshot();
		SceneSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot));
		assertEquals("scene-x", decoded.sceneId);
		assertEquals(server.epoch(), decoded.epoch);
		assertEquals(snapshot.seq, decoded.seq);
		assertEquals(77L, decoded.serverTick);
		assertTrue(snapshot.state.contentEquals(decoded.state));
		assertEquals(snapshot.state.nextResourceId, decoded.state.nextResourceId);
		assertEquals(snapshot.state.nextNodeId, decoded.state.nextNodeId);
		// Manifest-only: the decoded texture is pending on the receiving side.
		assertTrue(decoded.state.resources.get(texture).isPending());

		// A mirror recovered from the decoded snapshot compacts identically to the server:
		// the codec's publish() restore path rebuilds canvas replay state.
		SceneMirror mirror = new SceneMirror("scene-x");
		mirror.applySnapshot(decoded);
		ArrayList<CanvasCommand> clear = new ArrayList<CanvasCommand>();
		clear.add(CanvasCommand.of(V2Wire.OP_FILL));
		server.canvasAppend(canvas, clear);
		mirror.applyBatch(ds.mods.OCLights2.v2.protocol.BatchCodec.decode(
				ds.mods.OCLights2.v2.protocol.BatchCodec.encode(server.sealBatch())));
		assertTrue(server.state().contentEquals(mirror.state()));
	}

	@Test
	public void pushDepthSurvivesSnapshotRoundTrip() throws Exception {
		// SceneCanvas.copy() once dropped pushDepth: after a resync the mirror's ORIGIN
		// re-armed compaction while the server's did not — silent visible-list divergence.
		ServerScene server = new ServerScene("scene-p");
		int canvas = server.createCanvas(64, 64, 4096);
		ArrayList<CanvasCommand> setup = new ArrayList<CanvasCommand>();
		setup.add(CanvasCommand.of(V2Wire.OP_PUSH));
		setup.add(CanvasCommand.of(V2Wire.OP_TRANSLATE, 5, 5));
		setup.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, 1, 1, 1, 255));
		server.canvasAppend(canvas, setup);
		server.sealBatch();

		SceneMirror mirror = new SceneMirror("scene-p");
		mirror.applySnapshot(SnapshotCodec.decode(SnapshotCodec.encode(server.snapshot())));
		assertTrue(server.state().contentEquals(mirror.state()));

		// ORIGIN under a non-empty push stack must NOT re-arm compaction — identically on
		// both sides of the resync.
		ArrayList<CanvasCommand> next = new ArrayList<CanvasCommand>();
		next.add(CanvasCommand.of(V2Wire.OP_ORIGIN));
		next.add(CanvasCommand.of(V2Wire.OP_FILL));
		server.canvasAppend(canvas, next);
		mirror.applyBatch(ds.mods.OCLights2.v2.protocol.BatchCodec.decode(
				ds.mods.OCLights2.v2.protocol.BatchCodec.encode(server.sealBatch())));
		assertTrue(server.state().contentEquals(mirror.state()));
		assertEquals(5, mirror.state().resources.get(canvas).canvas.visibleCommands().size());
	}

	@Test
	public void snapshotCodecRejectsTruncation() {
		ServerScene server = new ServerScene("s");
		server.createCanvas(16, 16, 64);
		server.sealBatch();
		byte[] data = SnapshotCodec.encode(server.snapshot());
		for (int cut = 1; cut < data.length; cut += 5) {
			try {
				SnapshotCodec.decode(java.util.Arrays.copyOf(data, cut));
				fail("expected CodecException at cut " + cut);
			} catch (CodecException expected) {
			}
		}
	}

	@Test
	public void unexpectedDeltaInSnapshotPositionFails() throws Exception {
		// A batch payload handed to the snapshot decoder must not decode successfully.
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.NodeFree(1));
		byte[] batchPayload = ds.mods.OCLights2.v2.protocol.BatchCodec.encode(
				new ds.mods.OCLights2.v2.protocol.SceneBatch("s", 1, 1, 0L, deltas));
		try {
			SnapshotCodec.decode(batchPayload);
			fail("expected CodecException");
		} catch (CodecException expected) {
		}
	}
}
