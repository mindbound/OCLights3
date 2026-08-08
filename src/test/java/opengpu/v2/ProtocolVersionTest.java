package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;

/**
 * Guards the coupling that makes "unknown ops cannot arrive" true.
 *
 * The decoder tests PROTOCOL_VERSION for strict equality and rejects unknown ops outright, so
 * a client of the wrong vintage discards a whole batch rather than misreading it — which is
 * what lets the renderer's default case assert that an unknown op is unreachable, and what
 * removed the need for any mixed-version fallback when fonts were added.
 *
 * That guarantee holds only while the version and the op table move together. Add an op
 * without bumping the version and it inverts: two builds share a version number, disagree
 * about the op table, and an old client decodes the new op's argument as some other op's
 * payload. Silent corruption instead of a clean rejection.
 *
 * This test cannot detect a future omission by itself — nothing can compare against a build
 * that does not exist yet. What it does is pin the current pairing so that adding an op
 * without touching the version fails HERE, next to a comment explaining what to do about it.
 */
public class ProtocolVersionTest {

	/**
	 * The highest op id this version defines, and the version that defines it. Bump BOTH when
	 * adding an op — that is the entire point of this test.
	 */
	private static final int HIGHEST_OP_AT_THIS_VERSION = V2Wire.OP_SET_FONT;
	private static final short VERSION_THAT_DEFINES_IT = 4;

	@Test
	public void versionAndOpTableMoveTogether() {
		assertEquals("PROTOCOL_VERSION changed without updating this test — if you added an op,"
				+ " update BOTH constants here; if you bumped the version for another reason,"
				+ " update VERSION_THAT_DEFINES_IT only",
				VERSION_THAT_DEFINES_IT, V2Wire.PROTOCOL_VERSION);

		// Nothing may be defined above the recorded highest op without the version moving.
		for (int op = HIGHEST_OP_AT_THIS_VERSION + 1; op < HIGHEST_OP_AT_THIS_VERSION + 16; op++) {
			assertEquals("op " + op + " is defined but PROTOCOL_VERSION is still "
					+ V2Wire.PROTOCOL_VERSION + " — bump the version and this test together",
					-1, V2Wire.canvasOpArgCount(op));
		}
	}

	@Test
	public void everyDefinedOpHasAnArity() {
		for (int op = 1; op <= HIGHEST_OP_AT_THIS_VERSION; op++) {
			assertTrue("op " + op + " has no arity entry, so the decoder would reject it",
					V2Wire.canvasOpArgCount(op) >= 0);
		}
	}

	/** A batch from a different protocol version must be refused whole, not partially read. */
	@Test
	public void aBatchFromAnotherVersionIsRejected() throws Exception {
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.ResourceCreate(1, V2Wire.RES_CANVAS, 64, 64, 0, 0, 256));
		byte[] encoded = BatchCodec.encode(new SceneBatch("s", 1, 1, 1L, deltas));

		// The version is the first field; corrupt only it.
		encoded[0] = (byte) 0;
		encoded[1] = (byte) 99;
		try {
			BatchCodec.decode(encoded);
			fail("a batch claiming version 99 must be rejected");
		} catch (CodecException expected) {
			assertTrue("the error should name the version, got: " + expected.getMessage(),
					expected.getMessage().toLowerCase().contains("version"));
		}
	}

	/**
	 * The new op survives the real server-to-client path with its argument intact, carried
	 * inside a batch rather than through a standalone list — the standalone form is only ever
	 * produced by the Lua library, so the codec has no encoder for it to test against.
	 */
	@Test
	public void setFontRoundTripsInsideABatch() throws Exception {
		ArrayList<CanvasCommand> cmds = new ArrayList<CanvasCommand>();
		cmds.add(CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_UNSCII8));
		cmds.add(CanvasCommand.text(1, 2, "hi"));
		cmds.add(CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_DEFAULT));

		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.ResourceCreate(1, V2Wire.RES_CANVAS, 64, 64, 0, 0, 256));
		deltas.add(new Delta.CanvasPublish(1, cmds));

		SceneBatch back = BatchCodec.decode(
				BatchCodec.encode(new SceneBatch("s", 1, 1, 1L, deltas)));

		Delta.CanvasPublish pub = null;
		for (Delta d : back.deltas) {
			if (d instanceof Delta.CanvasPublish) {
				pub = (Delta.CanvasPublish) d;
			}
		}
		assertTrue("the publish survived", pub != null);
		assertEquals(3, pub.commands.size());
		assertEquals("op preserved", V2Wire.OP_SET_FONT, pub.commands.get(0).op);
		assertEquals("font id preserved", V2Wire.FONT_UNSCII8,
				(int) pub.commands.get(0).args[0]);
		assertEquals("the text between them is untouched", "hi", pub.commands.get(1).text);
		assertEquals("and the reset back to default", V2Wire.FONT_DEFAULT,
				(int) pub.commands.get(2).args[0]);
	}

	@Test
	public void fontIdValidityMatchesTheDefinedFonts() {
		assertTrue("default is valid", V2Wire.isValidFont(V2Wire.FONT_DEFAULT));
		assertTrue("unscii8 is valid", V2Wire.isValidFont(V2Wire.FONT_UNSCII8));
		assertTrue("negative is not", !V2Wire.isValidFont(-1));
		assertTrue("past the end is not", !V2Wire.isValidFont(V2Wire.FONT_COUNT));
	}
}
