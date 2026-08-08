package opengpu.v2.font;

/**
 * A source of bitmap glyphs on a fixed cell grid, used identically by the server (to answer
 * getTextWidth) and the client (to render). Deliberately free of any Minecraft type so a
 * dedicated server can measure text headlessly.
 *
 * ADVANCES COME FROM THE FONT DATA, NEVER FROM SCANNING A RENDERED ATLAS. That rule is the
 * whole reason this interface exists. The previous implementation derived each advance by
 * scanning the shipped PNG for its rightmost opaque column, which made the atlas and the
 * metrics two sources of truth that had to agree — and nothing checked that they did, so
 * replacing the atlas would have silently changed every layout the mod produces. Here a
 * glyph's cell count is a property of the glyph record itself, so the two cannot diverge.
 *
 * Widths are counted in CELLS, not pixels, because that is the only unit both uses agree on:
 * terminal-style callers lay out in cells, graphics-style callers multiply by
 * {@link #cellWidth()}. Zero is legal (combining marks), one is normal, two is the East Asian
 * wide case.
 */
public interface GlyphSource {

	/** Pixel width of one cell. A two-cell glyph is {@code 2 * cellWidth()} wide. */
	int cellWidth();

	/** Pixel height of every glyph. Uniform by construction — no per-glyph ascent/descent. */
	int cellHeight();

	/**
	 * Cells this codepoint advances the pen: 0, 1 or 2.
	 *
	 * Must be total: every codepoint, including ones the font has no glyph for, must return a
	 * defined advance. A caller laying out text cannot be asked to handle "unknown width", and
	 * an undefined answer here would desync the server's measurement from the client's pen.
	 */
	int advanceCells(int codepoint);

	/**
	 * Row-major 1-bit bitmap for this codepoint, MSB first within each byte, or {@code null}
	 * when the font has no glyph. Length is {@code cellHeight() * bytesPerRow}, where
	 * bytesPerRow is {@code advanceCells(cp) * cellWidth() / 8}.
	 *
	 * A null return is NOT an error and must not change the advance — a missing glyph still
	 * occupies its cell, so text does not reflow depending on which glyphs a font happens to
	 * carry. Renderers draw nothing, or their own substitute.
	 */
	byte[] bitmap(int codepoint);
}
