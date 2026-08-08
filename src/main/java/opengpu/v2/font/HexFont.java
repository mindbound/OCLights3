package opengpu.v2.font;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link GlyphSource} over the GNU Unifont ".hex" format — one glyph per line, as
 * {@code CODEPOINT:BITS}, where CODEPOINT is hex and BITS is the glyph's rows concatenated as
 * hex bytes:
 *
 * <pre>
 *   0041:0000000018242442427E424242420000    32 chars -> 8x16,  one cell
 *   4E00:000000000000000000007FFE0000...     64 chars -> 16x16, two cells
 * </pre>
 *
 * WHERE THE FONT COMES FROM. OpenGPU does not ship a font. OpenComputers is a hard dependency
 * — this mod cannot load without it — and it already ships Unifont as
 * {@code /assets/opencomputers/font.hex} under the SIL Open Font License. Reading its copy
 * means no redistribution obligation, ~5 MB less jar, and text that matches OC's own screens.
 *
 * LOADED FROM THE CLASSPATH ON BOTH SIDES, deliberately, and NOT through Minecraft's resource
 * manager. OC's own client renderer goes through the resource manager, so a resource pack can
 * replace its font; its server-side width table does not, and cannot. Following that split
 * would let a client render from a pack the server has never seen, desyncing every layout from
 * the measurement that produced it. One classpath read on both sides keeps
 * {@code getTextWidth} and the pen bit-identical by construction. Resource-pack fonts would
 * need the pack's glyph data synced to the server, which is a feature, not a default.
 *
 * The width rule is Unifont's own and needs no Unicode tables: a 32-character record is one
 * cell, a 64-character record is two. OpenComputers reaches the same answers by precomputing
 * musl's {@code wcwidth} over the whole codepoint space and then letting the font file
 * override it — the override is what actually decides, so this reads the deciding half
 * directly.
 */
public final class HexFont implements GlyphSource {

	/** OpenComputers' shipped Unifont. See the class javadoc for why we read theirs. */
	public static final String OC_FONT_RESOURCE = "/assets/opencomputers/font.hex";

	/** unscii-8, bundled: public domain, 8x8, dense box-drawing and Braille, no CJK. */
	public static final String UNSCII8_RESOURCE = "/assets/opengpu/font/unscii-8.hex";

	/**
	 * Always 8. The format encodes each row as whole bytes, so a single-width cell is one byte
	 * per row and a double-width cell is two — there is no way to express any other width, and
	 * a "16-wide" glyph IS the double-width case rather than a different cell size.
	 */
	private static final int CELL_W = 8;

	/** Codepoint -> packed rows. Absent key means the font has no glyph. */
	private final Map<Integer, byte[]> glyphs;
	/** Codepoints the font records as two cells wide, kept even when bitmaps are dropped. */
	private final Map<Integer, Boolean> wide;

	private final int cellHeight;
	private final int malformedLines;

	private HexFont(Map<Integer, byte[]> glyphs, Map<Integer, Boolean> wide, int cellHeight,
			int malformedLines) {
		this.glyphs = glyphs;
		this.wide = wide;
		this.cellHeight = cellHeight;
		this.malformedLines = malformedLines;
	}

	/**
	 * Infer a .hex font's cell height by scanning it, for callers that do not already know.
	 *
	 * THE SHORTEST RECORD IS THE SINGLE-WIDTH ONE, and that is the only reliable signal. A
	 * record is {@code 2 * height} hex characters single-width and {@code 4 * height} double,
	 * so the two fonts we ship overlap: 32 characters means 8x16 single-width in Unifont and
	 * 16x8 DOUBLE-width in unscii-8. Reading one record in isolation therefore cannot tell
	 * you the geometry — and taking the first record would get unscii exactly wrong, because
	 * its very first line (U+0000) happens to be its only double-width glyph.
	 *
	 * @return the inferred cell height, or -1 if the stream held no usable record.
	 */
	public static int sniffCellHeight(InputStream in) throws IOException {
		BufferedReader reader = new BufferedReader(
				new InputStreamReader(in, Charset.forName("US-ASCII")));
		int shortest = Integer.MAX_VALUE;
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				int colon = line.indexOf(':');
				if (colon <= 0 || colon == line.length() - 1) {
					continue;
				}
				int bits = line.length() - colon - 1;
				// Must be even and a whole number of rows; odd lengths are corruption.
				if (bits > 0 && bits % 2 == 0 && bits < shortest) {
					shortest = bits;
				}
			}
		} finally {
			reader.close();
		}
		return shortest == Integer.MAX_VALUE ? -1 : shortest / 2;
	}

	/**
	 * Parse a .hex stream with a KNOWN cell height.
	 *
	 * The height is a parameter rather than a guess because guessing it wrong is silent
	 * corruption: every glyph would decode at the wrong row count and render as noise. Passing
	 * a wrong height here instead produces a font with almost no glyphs and a large
	 * {@link #malformedLines()} count — loud, and obvious in a log.
	 *
	 * @param withBitmaps false to keep only the widths. The server answers getTextWidth and
	 *                    never draws, and the glyph bitmaps are by far the bulk of the data
	 *                    (~2 MB against a few hundred KB of width bookkeeping), so a dedicated
	 *                    server should not carry them.
	 */
	public static HexFont parse(InputStream in, int cellHeight, boolean withBitmaps)
			throws IOException {
		if (cellHeight < 1 || cellHeight > 64) {
			throw new IllegalArgumentException("implausible cell height: " + cellHeight);
		}
		final int singleChars = cellHeight * 2;
		final int doubleChars = cellHeight * 4;
		Map<Integer, byte[]> glyphs = new HashMap<Integer, byte[]>();
		Map<Integer, Boolean> wide = new HashMap<Integer, Boolean>();
		int malformed = 0;
		// US-ASCII rather than the platform default: the format is hex digits and colons, and
		// a default charset differing between the building and running machine has no business
		// changing how a font parses.
		BufferedReader reader = new BufferedReader(
				new InputStreamReader(in, Charset.forName("US-ASCII")));
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				int colon = line.indexOf(':');
				if (colon <= 0 || colon == line.length() - 1) {
					if (line.trim().length() > 0) {
						malformed++;
					}
					continue;
				}
				int codepoint;
				try {
					codepoint = Integer.parseInt(line.substring(0, colon), 16);
				} catch (NumberFormatException e) {
					malformed++;
					continue;
				}
				int bits = line.length() - colon - 1;
				// Exactly two shapes are legal for a given cell height, and anything else is a
				// corrupt line rather than a font to interpret creatively: guessing a width
				// would put a wrong advance into layout, which is worse than dropping a glyph.
				// This is also what makes a wrong cellHeight fail loudly — nearly every record
				// lands here rather than decoding into noise.
				if (bits != singleChars && bits != doubleChars) {
					malformed++;
					continue;
				}
				if (codepoint < 0 || codepoint > 0x10FFFF) {
					malformed++;
					continue;
				}
				Integer key = Integer.valueOf(codepoint);
				wide.put(key, Boolean.valueOf(bits == doubleChars));
				if (withBitmaps) {
					byte[] rows = decodeHex(line, colon + 1, bits);
					if (rows == null) {
						malformed++;
						wide.remove(key);
						continue;
					}
					glyphs.put(key, rows);
				}
			}
		} finally {
			reader.close();
		}
		return new HexFont(glyphs, wide, cellHeight, malformed);
	}

	/**
	 * Load OpenComputers' Unifont from the classpath.
	 *
	 * @return null when the resource is absent or unreadable. Callers must have a deterministic
	 *         fallback: a null here means every string would otherwise measure differently on
	 *         the two sides, which is worse than measuring crudely but identically.
	 */
	public static HexFont loadFromOpenComputers(boolean withBitmaps) {
		return loadResource(OC_FONT_RESOURCE, 16, withBitmaps);
	}

	/**
	 * The bundled unscii-8: 8x8, public domain, complete box-drawing / block / geometric /
	 * Braille coverage, and no CJK, kana or Hangul at all. Half the height of Unifont, so
	 * twice the lines at a given canvas resolution, with glyphs actually drawn for an 8px box
	 * rather than a 16px one scaled down.
	 */
	public static HexFont loadUnscii8(boolean withBitmaps) {
		return loadResource(UNSCII8_RESOURCE, 8, withBitmaps);
	}

	/**
	 * Load a .hex from the classpath at a known cell height.
	 *
	 * @return null when the resource is absent or unreadable. Callers must have a
	 *         deterministic fallback: a null here means every string would otherwise measure
	 *         differently on the two sides, which is worse than measuring crudely but
	 *         identically.
	 */
	public static HexFont loadResource(String resource, int cellHeight, boolean withBitmaps) {
		InputStream in = HexFont.class.getResourceAsStream(resource);
		if (in == null) {
			return null;
		}
		try {
			try {
				return parse(in, cellHeight, withBitmaps);
			} finally {
				in.close();
			}
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Load a .hex whose geometry is not known in advance, sniffing the height first. Opens
	 * the resource twice — once to scan, once to parse — because a classpath stream cannot be
	 * rewound and buffering a 5 MB font to avoid a second read is the worse trade.
	 */
	public static HexFont loadResourceSniffing(String resource, boolean withBitmaps) {
		InputStream probe = HexFont.class.getResourceAsStream(resource);
		if (probe == null) {
			return null;
		}
		int height;
		try {
			try {
				height = sniffCellHeight(probe);
			} finally {
				probe.close();
			}
		} catch (IOException e) {
			return null;
		}
		if (height < 1) {
			return null;
		}
		return loadResource(resource, height, withBitmaps);
	}

	private static byte[] decodeHex(String line, int from, int count) {
		byte[] out = new byte[count / 2];
		for (int i = 0; i < out.length; i++) {
			int hi = hexDigit(line.charAt(from + i * 2));
			int lo = hexDigit(line.charAt(from + i * 2 + 1));
			if (hi < 0 || lo < 0) {
				return null;
			}
			out[i] = (byte) (hi << 4 | lo);
		}
		return out;
	}

	private static int hexDigit(char c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'A' && c <= 'F') {
			return c - 'A' + 10;
		}
		if (c >= 'a' && c <= 'f') {
			return c - 'a' + 10;
		}
		return -1;
	}

	@Override
	public int cellWidth() {
		return CELL_W;
	}

	@Override
	public int cellHeight() {
		return cellHeight;
	}

	@Override
	public int advanceCells(int codepoint) {
		Boolean w = wide.get(Integer.valueOf(codepoint));
		if (w == null) {
			// No glyph. One cell, not zero: a missing glyph still occupies its place, so text
			// does not reflow depending on which codepoints this particular font happens to
			// carry. Both sides agree because both consult the same font.
			return 1;
		}
		return w.booleanValue() ? 2 : 1;
	}

	@Override
	public byte[] bitmap(int codepoint) {
		return glyphs.get(Integer.valueOf(codepoint));
	}

	/** How many glyphs the font defines. */
	public int glyphCount() {
		return wide.size();
	}

	/** Lines rejected as malformed. Non-zero is worth logging, not worth failing over. */
	public int malformedLines() {
		return malformedLines;
	}

	/** True when bitmaps were kept; false for a widths-only (server) load. */
	public boolean hasBitmaps() {
		return !glyphs.isEmpty();
	}

	/**
	 * Pen advance for a whole string, in cells, iterating by CODEPOINT rather than by char.
	 *
	 * Java strings are UTF-16, so an astral character is two chars; counting chars would
	 * measure a single emoji as two cells and put every later glyph in the wrong place.
	 */
	public int advanceCells(String text) {
		int cells = 0;
		int i = 0;
		while (i < text.length()) {
			int cp = text.codePointAt(i);
			cells += advanceCells(cp);
			i += Character.charCount(cp);
		}
		return cells;
	}
}
