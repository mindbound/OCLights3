package opengpu.v2.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;

import org.junit.Test;

/**
 * Covers the .hex parser twice over: against hand-written records, where the expected bytes
 * can be read off the input, and against the real Unifont that OpenComputers ships, which is
 * the thing that will actually be loaded and the only way to catch "parses my toy input but
 * chokes on 75,000 real lines".
 *
 * The OC-backed tests are skipped rather than failed if the font is not on the classpath, so
 * this suite still runs if the dependency is ever swapped — but note it IS on the test
 * classpath today (the OC dev jar is a runtimeOnly dependency), so a skip means something
 * changed and is worth looking at.
 */
public class HexFontTest {

	/** Most tests here use 16px cells, matching Unifont. */
	private static HexFont parse(String text, boolean bitmaps) throws Exception {
		return parse(text, 16, bitmaps);
	}

	private static HexFont parse(String text, int cellHeight, boolean bitmaps) throws Exception {
		return HexFont.parse(new ByteArrayInputStream(text.getBytes(Charset.forName("US-ASCII"))),
				cellHeight, bitmaps);
	}

	private static int sniff(String text) throws Exception {
		return HexFont.sniffCellHeight(
				new ByteArrayInputStream(text.getBytes(Charset.forName("US-ASCII"))));
	}

	@Test
	public void parsesSingleAndDoubleWidthRecords() throws Exception {
		// 32 hex chars -> 8x16 -> one cell. 64 -> 16x16 -> two.
		String single = "0041:" + repeat("0F", 16);
		String dbl = "4E00:" + repeat("00FF", 16);
		HexFont f = parse(single + "\n" + dbl + "\n", true);

		assertEquals("two glyphs", 2, f.glyphCount());
		assertEquals("no malformed lines", 0, f.malformedLines());
		assertEquals("cell width", 8, f.cellWidth());
		assertEquals("cell height", 16, f.cellHeight());

		assertEquals("'A' is one cell", 1, f.advanceCells(0x41));
		assertEquals("U+4E00 is two cells", 2, f.advanceCells(0x4E00));

		assertEquals("one-cell bitmap is 16 rows x 1 byte", 16, f.bitmap(0x41).length);
		assertEquals("two-cell bitmap is 16 rows x 2 bytes", 32, f.bitmap(0x4E00).length);
		assertEquals("first row decodes MSB-first", (byte) 0x0F, f.bitmap(0x41)[0]);
		assertEquals("wide row high byte", (byte) 0x00, f.bitmap(0x4E00)[0]);
		assertEquals("wide row low byte", (byte) 0xFF, f.bitmap(0x4E00)[1]);
	}

	/**
	 * A missing glyph must still advance one cell. If it advanced zero, text would reflow
	 * depending on which codepoints the font happens to carry — and worse, differently on a
	 * server loading widths-only than on a client.
	 */
	@Test
	public void unknownCodepointsAdvanceOneCellAndDrawNothing() throws Exception {
		HexFont f = parse("0041:" + repeat("0F", 16) + "\n", true);
		assertEquals("absent codepoint still occupies a cell", 1, f.advanceCells(0x4E00));
		assertNull("but has no bitmap", f.bitmap(0x4E00));
		assertEquals("far out of range too", 1, f.advanceCells(0x10FFFF));
	}

	/**
	 * A record with a length Unifont does not define is dropped, not guessed at. Guessing
	 * would put a wrong advance into layout, which is worse than losing one glyph.
	 */
	@Test
	public void malformedRecordsAreCountedAndDropped() throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("0041:").append(repeat("0F", 16)).append("\n"); // good
		sb.append("0042:0F0F\n");                                  // wrong length
		sb.append("nothex:").append(repeat("0F", 16)).append("\n"); // bad codepoint
		sb.append("0043:").append(repeat("ZZ", 16)).append("\n");  // bad hex digits
		sb.append("0044\n");                                       // no colon
		sb.append("\n");                                           // blank, not counted
		HexFont f = parse(sb.toString(), true);

		assertEquals("only the good record survives", 1, f.glyphCount());
		assertEquals("four bad lines counted", 4, f.malformedLines());
		assertNull("bad-hex glyph is not half-parsed", f.bitmap(0x43));
		assertEquals("and does not leave a width behind", 1, f.advanceCells(0x43));
	}

	@Test
	public void widthsOnlyLoadKeepsAdvancesButNotBitmaps() throws Exception {
		String text = "0041:" + repeat("0F", 16) + "\n4E00:" + repeat("00FF", 16) + "\n";
		HexFont f = parse(text, false);
		assertEquals("widths survive", 1, f.advanceCells(0x41));
		assertEquals("including double width", 2, f.advanceCells(0x4E00));
		assertNull("bitmaps dropped", f.bitmap(0x41));
		assertEquals("glyph count still known", 2, f.glyphCount());
		assertTrue("and reports itself as widths-only", !f.hasBitmaps());
	}

	/**
	 * THE INVARIANT THE TWO LOAD MODES EXIST UNDER: a widths-only load and a with-bitmaps load
	 * must accept and reject exactly the same records.
	 *
	 * They did not. The width was recorded before the payload was decoded and un-recorded only
	 * inside the with-bitmaps branch, so one corrupt line left the SERVER holding a width the
	 * CLIENT did not have: getTextWidth answered 2 cells for a double-width record while the
	 * client's pen, finding no entry, advanced 1. Permanent layout divergence from a single bad
	 * line, and invisible to convergence checking — both sides hold the identical command list
	 * and simply draw it differently.
	 *
	 * Parameterised over every corruption the decoder can reject, so the property is pinned
	 * rather than one example of it.
	 */
	@Test
	public void bothLoadModesAcceptAndRejectTheSameRecords() throws Exception {
		String[] corrupt = {
			"4E00:" + repeat("00FF", 15) + "00GG", // non-hex digit in the payload
			"4E00:" + repeat("00FF", 15) + "00 F", // whitespace inside the payload
			"0041:" + repeat("0F", 15) + "Z0",     // same, single-width record
		};
		for (String bad : corrupt) {
			String text = "0042:" + repeat("0F", 16) + "\n" + bad + "\n";
			HexFont withBitmaps = parse(text, true);
			HexFont widthsOnly = parse(text, false);

			int cp = bad.startsWith("0041") ? 0x41 : 0x4E00;
			assertEquals("advance must not depend on whether bitmaps were kept: " + bad,
					withBitmaps.advanceCells(cp), widthsOnly.advanceCells(cp));
			assertEquals("nor must the glyph count: " + bad,
					withBitmaps.glyphCount(), widthsOnly.glyphCount());
			assertEquals("nor the malformed count: " + bad,
					withBitmaps.malformedLines(), widthsOnly.malformedLines());
			assertEquals("the good record survives on both", 1, withBitmaps.advanceCells(0x42));
			assertEquals("the good record survives on both", 1, widthsOnly.advanceCells(0x42));
		}
	}

	/** A clean font must of course still agree, or the check above proves nothing. */
	@Test
	public void bothLoadModesAgreeOnACleanFont() throws Exception {
		String text = "0041:" + repeat("0F", 16) + "\n4E00:" + repeat("00FF", 16) + "\n";
		HexFont withBitmaps = parse(text, true);
		HexFont widthsOnly = parse(text, false);
		assertEquals(withBitmaps.glyphCount(), widthsOnly.glyphCount());
		assertEquals(withBitmaps.advanceCells(0x41), widthsOnly.advanceCells(0x41));
		assertEquals(withBitmaps.advanceCells(0x4E00), widthsOnly.advanceCells(0x4E00));
		assertEquals(0, withBitmaps.malformedLines());
		assertEquals(0, widthsOnly.malformedLines());
	}

	/** UTF-16 surrogate pairs are one codepoint, not two cells' worth of char. */
	@Test
	public void stringAdvanceIteratesByCodepoint() throws Exception {
		HexFont f = parse("0041:" + repeat("0F", 16) + "\n4E00:" + repeat("00FF", 16) + "\n", true);
		assertEquals("plain ASCII", 3, f.advanceCells("AAA"));
		assertEquals("wide char counts two", 2, f.advanceCells("一"));
		assertEquals("mixed", 4, f.advanceCells("A一A"));

		// Two Java chars, one codepoint, and absent from this toy font — so one cell. Char-wise
		// iteration would report two. (Against the REAL font the discriminating codepoint is
		// U+1D400, which is astral and single-width; an emoji is wide and so reads as 2 either
		// way. See FontMetricsGoldenTest.)
		String astral = new String(Character.toChars(0x1F600));
		assertEquals("astral char is ONE cell, not two", 1, f.advanceCells(astral));
		assertEquals("and does not corrupt what follows", 2, f.advanceCells(astral + "A"));
	}

	// ------------------------------------------------------- variable cell height

	/**
	 * The ambiguity that forces sniffing rather than per-record inference: a 32-character
	 * record is 8x16 SINGLE-width at height 16, and 16x8 DOUBLE-width at height 8. The same
	 * bytes, opposite meanings — so geometry cannot be read off one record.
	 */
	@Test
	public void identicalRecordMeansOppositeThingsAtDifferentHeights() throws Exception {
		String record = "0041:" + repeat("0F", 16); // 32 hex chars either way

		HexFont tall = parse(record + "\n", 16, true);
		assertEquals("at height 16 it is single-width", 1, tall.advanceCells(0x41));
		assertEquals(16, tall.cellHeight());

		HexFont shortCells = parse(record + "\n", 8, true);
		assertEquals("at height 8 the SAME record is double-width", 2, shortCells.advanceCells(0x41));
		assertEquals(8, shortCells.cellHeight());
	}

	/**
	 * The shortest record decides, not the first. unscii-8's very first line (U+0000) is its
	 * only double-width glyph, so first-record inference would read the whole font as 16px.
	 */
	@Test
	public void sniffUsesShortestRecordNotFirst() throws Exception {
		String font = "0000:" + repeat("00", 16) + "\n"     // 32 chars, double-width at h=8
				+ "0041:" + repeat("0F", 8) + "\n"           // 16 chars, single-width at h=8
				+ "0042:" + repeat("F0", 8) + "\n";
		assertEquals("height comes from the 16-char records", 8, sniff(font));

		HexFont f = parse(font, sniff(font), true);
		assertEquals("the leading long record is the wide one", 2, f.advanceCells(0x00));
		assertEquals("and the rest are narrow", 1, f.advanceCells(0x41));
		assertEquals("8 rows x 1 byte", 8, f.bitmap(0x41).length);
		assertEquals("8 rows x 2 bytes", 16, f.bitmap(0x00).length);
		assertEquals("no malformed lines", 0, f.malformedLines());
	}

	/**
	 * A wrong height must fail LOUDLY. Silent misparsing would decode every glyph at the wrong
	 * row count and render as noise, which is far harder to diagnose than an empty font.
	 */
	@Test
	public void wrongCellHeightRejectsEverythingRatherThanDecodingNoise() throws Exception {
		String eightPx = "0041:" + repeat("0F", 8) + "\n0042:" + repeat("F0", 8) + "\n";
		HexFont wrong = parse(eightPx, 16, true);
		assertEquals("nothing parsed", 0, wrong.glyphCount());
		assertEquals("and every line is reported malformed", 2, wrong.malformedLines());
	}

	@Test
	public void implausibleHeightsAreRejectedOutright() throws Exception {
		try {
			parse("0041:0F\n", 0, true);
			org.junit.Assert.fail("height 0 should raise");
		} catch (IllegalArgumentException expected) {
			// intended
		}
		try {
			parse("0041:0F\n", 999, true);
			org.junit.Assert.fail("absurd height should raise");
		} catch (IllegalArgumentException expected) {
			// intended
		}
	}

	@Test
	public void sniffReportsFailureOnAnEmptyOrJunkStream() throws Exception {
		assertEquals("empty stream", -1, sniff(""));
		assertEquals("no usable records", -1, sniff("not a font\nstill not\n"));
	}

	// ------------------------------------------------------------- bundled unscii

	/**
	 * The bundled unscii-8, which is why the parser had to stop being Unifont-specific. It is
	 * the complement of Unifont rather than a replacement: half the height, complete
	 * box-drawing and Braille, and no CJK whatsoever.
	 */
	@Test
	public void parsesBundledUnscii8() {
		HexFont f = HexFont.loadUnscii8(true);
		assertNotNull("unscii-8 must be bundled at " + HexFont.UNSCII8_RESOURCE, f);

		assertEquals("8px cells", 8, f.cellHeight());
		assertEquals("8px wide cells", 8, f.cellWidth());
		assertEquals("no malformed lines", 0, f.malformedLines());
		assertTrue("a few thousand glyphs, not tens of thousands: " + f.glyphCount(),
				f.glyphCount() > 3000 && f.glyphCount() < 5000);

		assertEquals("ASCII covered", 1, f.advanceCells('A'));
		assertEquals("one-cell glyph is 8 rows x 1 byte", 8, f.bitmap('A').length);

		// What it is FOR: complete box-drawing, block and Braille coverage, which is exactly
		// where Unifont is weakest for terminal-style UI.
		assertNotNull("box drawing U+250C", f.bitmap(0x250C));
		assertNotNull("block element U+2588", f.bitmap(0x2588));
		assertNotNull("braille U+2800", f.bitmap(0x2800));
		assertNotNull("braille U+28FF", f.bitmap(0x28FF));

		// What it is NOT for. These must be absent, so anyone switching a CJK-bearing display
		// to unscii finds out here rather than from a screen of blanks.
		assertNull("no CJK", f.bitmap(0x4E00));
		assertNull("no hiragana", f.bitmap(0x3042));
		assertEquals("but a missing glyph still advances one cell", 1, f.advanceCells(0x4E00));
	}

	/** Sniffing the bundled font must agree with the height we hardcode for it. */
	@Test
	public void sniffingBundledUnsciiAgreesWithItsDeclaredHeight() {
		HexFont sniffed = HexFont.loadResourceSniffing(HexFont.UNSCII8_RESOURCE, false);
		assertNotNull(sniffed);
		assertEquals("sniffed height matches loadUnscii8's hardcoded 8", 8, sniffed.cellHeight());
		assertEquals("and yields the same glyph count",
				HexFont.loadUnscii8(false).glyphCount(), sniffed.glyphCount());
	}

	// ---------------------------------------------------------------- real font

	@Test
	public void parsesOpenComputersUnifont() {
		HexFont f = HexFont.loadFromOpenComputers(true);
		if (f == null) {
			// Not a failure: see class javadoc. But it should not happen today.
			System.out.println("SKIP: " + HexFont.OC_FONT_RESOURCE + " not on the classpath");
			return;
		}
		// Unifont covers essentially the whole BMP; a parse that silently produced a handful
		// of glyphs would still pass every hand-written test above.
		assertTrue("expected tens of thousands of glyphs, got " + f.glyphCount(),
				f.glyphCount() > 50000);
		assertEquals("real font should have no malformed lines", 0, f.malformedLines());

		assertEquals("ASCII 'A' is one cell", 1, f.advanceCells('A'));
		assertEquals("space is one cell", 1, f.advanceCells(' '));
		assertEquals("CJK U+4E00 is two cells", 2, f.advanceCells(0x4E00));
		assertEquals("Hiragana U+3042 is two cells", 2, f.advanceCells(0x3042));
		assertEquals("Cyrillic U+0416 is one cell", 1, f.advanceCells(0x0416));
		assertEquals("Greek U+03A9 is one cell", 1, f.advanceCells(0x03A9));

		assertNotNull("'A' has a glyph", f.bitmap('A'));
		assertEquals("one-cell glyph is 16 bytes", 16, f.bitmap('A').length);
		assertEquals("two-cell glyph is 32 bytes", 32, f.bitmap(0x4E00).length);

		// A blank 'A' would mean the hex decoded to zeros — parsed, but useless.
		boolean anyInk = false;
		for (byte b : f.bitmap('A')) {
			if (b != 0) {
				anyInk = true;
				break;
			}
		}
		assertTrue("'A' must actually have pixels set", anyInk);
	}

	/**
	 * The point of the whole exercise: a mixed-script string measures correctly, with the East
	 * Asian characters taking two cells. The current PNG-atlas font cannot represent any of
	 * these at all — every one of them renders as '?'.
	 */
	@Test
	public void measuresMixedScriptText() {
		HexFont f = HexFont.loadFromOpenComputers(false);
		if (f == null) {
			System.out.println("SKIP: " + HexFont.OC_FONT_RESOURCE + " not on the classpath");
			return;
		}
		assertEquals("latin", 5, f.advanceCells("hello"));
		assertEquals("cyrillic is single-width", 6, f.advanceCells("Привет"));
		assertEquals("greek is single-width", 5, f.advanceCells("Γειά!".substring(0, 5)));
		assertEquals("CJK doubles", 4, f.advanceCells("中文"));
		assertEquals("mixed latin + CJK", 5 + 4, f.advanceCells("hello中文"));
	}

	private static String repeat(String s, int times) {
		StringBuilder sb = new StringBuilder(s.length() * times);
		for (int i = 0; i < times; i++) {
			sb.append(s);
		}
		return sb.toString();
	}
}
