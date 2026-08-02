package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import org.junit.Test;

import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.MessageCodec;
import opengpu.v2.protocol.V2Wire;

/**
 * C-&gt;S input framing. Input is the one inbound path a client drives at will, so the decoder
 * carries the same strictness as the rest of the protocol: unknown kinds, epoch 0, and
 * trailing data are all refused.
 */
public class InputCodecTest {

	private static final String SCENE = "scene-input";
	private static final int EPOCH = 0x51DE;

	@Test
	public void everyInputKindRoundTrips() throws Exception {
		byte[] kinds = { MessageCodec.INPUT_POINTER_DOWN, MessageCodec.INPUT_POINTER_MOVE,
				MessageCodec.INPUT_POINTER_UP, MessageCodec.INPUT_SCROLL,
				MessageCodec.INPUT_KEY_DOWN, MessageCodec.INPUT_KEY_UP };
		for (byte kind : kinds) {
			MessageCodec.Input in = new MessageCodec.Input(SCENE, EPOCH, kind, 11, 22, 1);
			MessageCodec.Input out = MessageCodec.decodeInput(MessageCodec.encodeInput(in));
			assertEquals(SCENE, out.sceneId);
			assertEquals(EPOCH, out.epoch);
			assertEquals(kind, out.kind);
			assertEquals(11, out.a);
			assertEquals(22, out.b);
			assertEquals(1, out.c);
		}
	}

	@Test
	public void inputIsAKnownEnvelopeKind() throws Exception {
		byte[] envelope = MessageCodec.envelope(MessageCodec.MSG_INPUT,
				MessageCodec.encodeInput(new MessageCodec.Input(SCENE, EPOCH,
						MessageCodec.INPUT_POINTER_DOWN, 1, 2, 0)));
		assertEquals(MessageCodec.MSG_INPUT, MessageCodec.kindOf(envelope));
		assertEquals(SCENE, MessageCodec.decodeInput(MessageCodec.payloadOf(envelope)).sceneId);
	}

	private static byte[] forge(int epoch, byte kind, boolean trailing) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(SCENE);
			out.writeInt(epoch);
			out.writeByte(kind);
			out.writeInt(0);
			out.writeInt(0);
			out.writeInt(0);
			if (trailing) {
				out.writeByte(0x7F);
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		return bytes.toByteArray();
	}

	private static void expectReject(byte[] data, String why) {
		try {
			MessageCodec.decodeInput(data);
			fail("expected rejection: " + why);
		} catch (CodecException expected) {
		}
	}

	@Test
	public void malformedInputIsRefused() {
		expectReject(forge(0, MessageCodec.INPUT_POINTER_DOWN, false), "epoch 0");
		expectReject(forge(EPOCH, (byte) 0, false), "kind 0");
		expectReject(forge(EPOCH, (byte) 7, false), "kind above the known range");
		expectReject(forge(EPOCH, (byte) -1, false), "negative kind");
		expectReject(forge(EPOCH, MessageCodec.INPUT_POINTER_DOWN, true), "trailing data");
	}

	@Test
	public void pointerKindsAreClassifiedCorrectly() {
		assertTrue(MessageCodec.isPointerInput(MessageCodec.INPUT_POINTER_DOWN));
		assertTrue(MessageCodec.isPointerInput(MessageCodec.INPUT_POINTER_MOVE));
		assertTrue(MessageCodec.isPointerInput(MessageCodec.INPUT_POINTER_UP));
		assertFalse(MessageCodec.isPointerInput(MessageCodec.INPUT_SCROLL));
		assertFalse(MessageCodec.isPointerInput(MessageCodec.INPUT_KEY_DOWN));
		assertFalse(MessageCodec.isPointerInput(MessageCodec.INPUT_KEY_UP));
	}
}
