package opengpu.v2.mc;

import opengpu.v2.font.FontRegistry;
import opengpu.v2.font.GlyphSource;
import opengpu.v2.protocol.V2Wire;

/**
 * Text measurement — the server's {@code getTextWidth} answer, and the same numbers the
 * client's pen uses.
 *
 * PER FONT, since 2026-08-08. Every method takes a wire font id and resolves it through
 * {@link FontRegistry}, which is the single place ids become glyph sources. The client draws
 * from that same registry, so a string measured as font N is drawn with font N by
 * construction; nothing has to keep two tables agreeing.
 *
 * WIDTHS COME FROM THE FONT DATA, NOT FROM SCANNING AN ATLAS. The original implementation
 * derived advances by scanning a PNG for each glyph's rightmost opaque column, which made the
 * image and the metrics two sources of truth with nothing checking they agreed. A glyph is now
 * one or two cells because its font record says so.
 *
 * Minecraft-free by design, so a dedicated server can measure headlessly.
 */
public final class FontMetrics {

	private FontMetrics() {}

	/**
	 * Cell height for a font, in pixels: 16 for Unifont, 8 for unscii-8.
	 *
	 * Was a public constant until fonts became selectable. It could not stay one — a program
	 * laying out rows needs the height of the font it is actually drawing with, and a constant
	 * would have silently meant "Unifont's" everywhere.
	 */
	public static int glyphHeight(int fontId) {
		return font(fontId).cellHeight();
	}

	/** Pixel width of one cell; a double-width glyph is twice this. */
	public static int cellWidth(int fontId) {
		return font(fontId).cellWidth();
	}

	/**
	 * Advance of one codepoint in pixels.
	 *
	 * A codepoint the font has no glyph for still advances one cell rather than being
	 * remapped to '?'. With whole scripts absent from some fonts — unscii-8 has no CJK at all
	 * — substituting a visible character would misreport the width of text that is simply not
	 * renderable in that font.
	 */
	public static int charAdvance(int fontId, int codepoint) {
		GlyphSource f = font(fontId);
		return f.advanceCells(codepoint) * f.cellWidth();
	}

	/** Cells advanced, for callers laying out on a character grid. */
	public static int charCells(int fontId, int codepoint) {
		return font(fontId).advanceCells(codepoint);
	}

	/**
	 * Logical width of a string in pixels — the server-side getTextWidth answer.
	 *
	 * Iterates by CODEPOINT, not by char. Java strings are UTF-16, so an astral character is
	 * two chars; a char-wise loop measures one emoji as two glyphs and displaces everything
	 * after it.
	 */
	public static int textWidth(int fontId, String text) {
		GlyphSource f = font(fontId);
		return textCells(fontId, text) * f.cellWidth();
	}

	/** Width in cells, for grid layout. */
	public static int textCells(int fontId, String text) {
		GlyphSource f = font(fontId);
		int cells = 0;
		int i = 0;
		while (i < text.length()) {
			int cp = text.codePointAt(i);
			cells += f.advanceCells(cp);
			i += Character.charCount(cp);
		}
		return cells;
	}

	/** The glyph source behind a font id, widths only — the server never rasterizes. */
	public static GlyphSource font(int fontId) {
		return FontRegistry.get(fontId, false);
	}

	/** As {@link #font}, with bitmaps. Client only. */
	public static GlyphSource fontWithGlyphs(int fontId) {
		return FontRegistry.get(fontId, true);
	}

	/** Convenience for the many call sites that only ever meant the default font. */
	public static int textWidth(String text) {
		return textWidth(V2Wire.FONT_DEFAULT, text);
	}
}
