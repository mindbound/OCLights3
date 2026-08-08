package opengpu.v2.mc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import opengpu.v2.font.FontRegistry;
import opengpu.v2.protocol.V2Wire;

/**
 * Pins the text metrics the server reports and the client's pen follows.
 *
 * NOT a table of per-glyph widths. An earlier version of this file pinned 256 hand-derived
 * pixel advances, back when they were scanned out of a PNG atlas and each one was an
 * independent fact that could drift. Advances are now one or two cells straight from the font
 * record, so re-asserting them would only restate the font file.
 *
 * What is worth pinning is structural, and it is what a change to the metrics layer would
 * actually get wrong: the units, that each font reports its own geometry, that iteration is by
 * codepoint rather than by char, that a missing glyph still occupies its cell, and — since
 * fonts became selectable — that the two fonts genuinely disagree about the same string.
 */
public class FontMetricsGoldenTest {

	@Test
	public void eachFontReportsItsOwnGeometry() {
		assertEquals("unifont cells are 8 wide", 8, FontMetrics.cellWidth(V2Wire.FONT_DEFAULT));
		assertEquals("unifont is 16 tall", 16, FontMetrics.glyphHeight(V2Wire.FONT_DEFAULT));
		assertEquals("unscii-8 cells are 8 wide", 8, FontMetrics.cellWidth(V2Wire.FONT_UNSCII8));
		assertEquals("unscii-8 is 8 tall", 8, FontMetrics.glyphHeight(V2Wire.FONT_UNSCII8));
	}

	/**
	 * The reason glyph height stopped being a constant. A program stacking rows by a hardcoded
	 * 16 overlaps every unscii line by 8 pixels, and one hardcoding 8 overlaps every unifont
	 * line by the same.
	 */
	@Test
	public void lineHeightDiffersBetweenFonts() {
		assertNotEquals("the two fonts must not report the same line pitch",
				FontMetrics.glyphHeight(V2Wire.FONT_DEFAULT),
				FontMetrics.glyphHeight(V2Wire.FONT_UNSCII8));
	}

	/**
	 * Both fonts must load for real. Each falls back to blank monospace when its file is
	 * missing, and every other assertion here would still pass under that fallback — so
	 * without this the suite goes green on a build that renders no text at all.
	 */
	@Test
	public void bothFontsLoadRatherThanFallingBack() {
		// The fallback has no glyphs at all, so a real bitmap is the distinguishing evidence.
		assertNotNull("unifont must be readable from OpenComputers",
				FontMetrics.fontWithGlyphs(V2Wire.FONT_DEFAULT).bitmap('A'));
		assertNotNull("unscii-8 must be bundled and readable",
				FontMetrics.fontWithGlyphs(V2Wire.FONT_UNSCII8).bitmap('A'));
	}

	@Test
	public void advancesAreWholeCells() {
		int cell = FontMetrics.cellWidth(V2Wire.FONT_DEFAULT);
		assertEquals("ASCII is one cell", cell, FontMetrics.charAdvance(V2Wire.FONT_DEFAULT, 'A'));
		assertEquals("space is one cell, not the old 4px",
				cell, FontMetrics.charAdvance(V2Wire.FONT_DEFAULT, ' '));
		// 'i' and 'l' were 2 and 3 pixels under the scanned atlas. Monospace now.
		assertEquals("'i' is a full cell", cell, FontMetrics.charAdvance(V2Wire.FONT_DEFAULT, 'i'));
		assertEquals("CJK is two cells", 2 * cell,
				FontMetrics.charAdvance(V2Wire.FONT_DEFAULT, 0x4E00));
		assertEquals("Cyrillic stays single", cell,
				FontMetrics.charAdvance(V2Wire.FONT_DEFAULT, 0x0416));
	}

	@Test
	public void textWidthSumsAdvances() {
		int cell = FontMetrics.cellWidth(V2Wire.FONT_DEFAULT);
		assertEquals("empty", 0, FontMetrics.textWidth(V2Wire.FONT_DEFAULT, ""));
		assertEquals("\"hello\" is five cells", 5 * cell,
				FontMetrics.textWidth(V2Wire.FONT_DEFAULT, "hello"));
		assertEquals("mixed latin and CJK", (5 + 4) * cell,
				FontMetrics.textWidth(V2Wire.FONT_DEFAULT, "hello中文"));
		assertEquals("cells API agrees with pixels",
				FontMetrics.textWidth(V2Wire.FONT_DEFAULT, "hello中文") / cell,
				FontMetrics.textCells(V2Wire.FONT_DEFAULT, "hello中文"));
	}

	/**
	 * The point of selectable fonts, and the property that makes measuring with the wrong one
	 * a real bug rather than a rounding difference: the same string is a different width.
	 */
	@Test
	public void theTwoFontsDisagreeAboutTheSameString() {
		// unscii-8 has no CJK, so those codepoints advance one cell each there and two under
		// unifont — 8 cells versus 6 for this string.
		String s = "hi中文";
		int uni = FontMetrics.textCells(V2Wire.FONT_DEFAULT, s);
		int unscii = FontMetrics.textCells(V2Wire.FONT_UNSCII8, s);
		assertEquals("unifont: h,i + two wide", 6, uni);
		assertEquals("unscii-8: no CJK, so every codepoint is one cell", 4, unscii);
		assertNotEquals("measuring with the wrong font must be detectable", uni, unscii);
	}

	/**
	 * The bug the old char-wise loop had: an astral codepoint is two Java chars, so it measured
	 * as two glyphs and displaced everything after it.
	 *
	 * The codepoint choice is load-bearing. U+1F600 is the obvious pick and does NOT
	 * discriminate: it is East Asian Wide, so the correct answer is 2 cells — and the buggy
	 * char-wise answer is also 2, one per surrogate. U+1D400 is astral and SINGLE-width, so
	 * the two readings differ.
	 */
	@Test
	public void astralCodepointsMeasureAsOneGlyph() {
		int cell = FontMetrics.cellWidth(V2Wire.FONT_DEFAULT);
		String narrowAstral = new String(Character.toChars(0x1D400));
		assertEquals("sanity: single-width, or this test cannot discriminate",
				1, FontMetrics.charCells(V2Wire.FONT_DEFAULT, 0x1D400));
		assertEquals("two Java chars, ONE cell", cell,
				FontMetrics.textWidth(V2Wire.FONT_DEFAULT, narrowAstral));
		assertEquals("and the glyph after it is not displaced", 2 * cell,
				FontMetrics.textWidth(V2Wire.FONT_DEFAULT, narrowAstral + "A"));
	}

	/** Coverage is why unifont is the default: all of these were '?' under the old atlas. */
	@Test
	public void unifontCoversNonLatinScripts() {
		assertTrue("Cyrillic", FontMetrics.font(V2Wire.FONT_DEFAULT).advanceCells(0x0416) > 0);
		assertTrue("Greek", FontMetrics.font(V2Wire.FONT_DEFAULT).advanceCells(0x03A9) > 0);
		assertEquals("CJK is genuinely double-width", 2,
				FontMetrics.font(V2Wire.FONT_DEFAULT).advanceCells(0x4E00));
	}

	/** An unknown id resolves to the default rather than throwing deep in a render loop. */
	@Test
	public void unknownFontIdsResolveToTheDefault() {
		assertEquals("negative", FontMetrics.glyphHeight(V2Wire.FONT_DEFAULT),
				FontMetrics.glyphHeight(-1));
		assertEquals("past the end", FontMetrics.glyphHeight(V2Wire.FONT_DEFAULT),
				FontMetrics.glyphHeight(V2Wire.FONT_COUNT + 5));
	}

	/** Names and ids must round-trip, or a font the library accepts is one the server rejects. */
	@Test
	public void registryNamesAndIdsRoundTrip() {
		for (int id = 0; id < V2Wire.FONT_COUNT; id++) {
			assertEquals("round trip for id " + id, id, FontRegistry.idOf(FontRegistry.nameOf(id)));
		}
		assertEquals("unknown name is rejected, not defaulted", -1, FontRegistry.idOf("comic sans"));
	}
}
