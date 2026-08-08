package opengpu.v2.mc.client.render;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import opengpu.v2.font.GlyphSource;

/**
 * An OpenGL texture cache of rasterized glyphs, filled on demand.
 *
 * Unifont carries ~75,000 glyphs. Pre-baking those into a single image is not an option — at
 * one 8x16 cell each that is a texture no driver will accept — so glyphs are rasterized into a
 * page the first time they are drawn, exactly as OpenComputers' DynamicFontRenderer does. A
 * page holds {@link #COLS}x{@link #ROWS} cells; when one fills, the next is created. In
 * practice a session touching Latin, punctuation and a little Cyrillic never leaves page zero.
 *
 * A double-width glyph occupies TWO adjacent cells in the same row, and never straddles a page
 * boundary — the allocator skips the last column rather than split one, because a glyph split
 * across two textures cannot be drawn in a single quad and the wasted cell costs nothing.
 *
 * Client-only. The server never rasterizes anything; it consults the same {@link GlyphSource}
 * for advances alone, which is what keeps its measurements and this atlas in agreement.
 */
final class GlyphAtlas {

	/** Cells per page edge. 32x32 single-width cells = a 256x512 texture per page. */
	private static final int COLS = 32;
	private static final int ROWS = 32;

	private final GlyphSource font;
	private final int cellW;
	private final int cellH;

	private final Map<Integer, Entry> entries = new HashMap<Integer, Entry>();
	private final java.util.List<Integer> pageTextures = new java.util.ArrayList<Integer>();

	private int cursorCol;
	private int cursorRow;

	/** Where a glyph landed: which page, and its UV rectangle within it. */
	static final class Entry {
		final int texture;
		final float u0, v0, u1, v1;
		final int cells;

		Entry(int texture, float u0, float v0, float u1, float v1, int cells) {
			this.texture = texture;
			this.u0 = u0;
			this.v0 = v0;
			this.u1 = u1;
			this.v1 = v1;
			this.cells = cells;
		}
	}

	GlyphAtlas(GlyphSource font) {
		this.font = font;
		this.cellW = font.cellWidth();
		this.cellH = font.cellHeight();
	}

	int pageWidth() {
		return COLS * cellW;
	}

	int pageHeight() {
		return ROWS * cellH;
	}

	/**
	 * Look up a glyph, rasterizing and uploading it if this is its first use.
	 *
	 * @return null when the font has no bitmap for this codepoint. Callers must still advance
	 *         the pen by {@code font.advanceCells}: a missing glyph occupies its cell, so the
	 *         text does not reflow around the gap and the client's pen stays in step with the
	 *         width the server already reported.
	 */
	Entry get(int codepoint) {
		Integer key = Integer.valueOf(codepoint);
		Entry cached = entries.get(key);
		if (cached != null) {
			return cached;
		}
		byte[] bits = font.bitmap(codepoint);
		if (bits == null) {
			return null;
		}
		int cells = font.advanceCells(codepoint);
		if (cells < 1) {
			return null; // zero-width (combining) glyphs are not drawn standalone
		}
		Entry e = allocate(bits, cells);
		entries.put(key, e);
		return e;
	}

	private Entry allocate(byte[] bits, int cells) {
		// Never split a wide glyph across a page edge: it could not be drawn as one quad.
		if (cursorCol + cells > COLS) {
			cursorCol = 0;
			cursorRow++;
		}
		if (pageTextures.isEmpty() || cursorRow >= ROWS) {
			newPage();
		}
		int texture = pageTextures.get(pageTextures.size() - 1).intValue();
		int px = cursorCol * cellW;
		int py = cursorRow * cellH;
		int w = cells * cellW;

		upload(texture, px, py, w, cellH, bits, cells);

		float u0 = (float) px / pageWidth();
		float v0 = (float) py / pageHeight();
		float u1 = (float) (px + w) / pageWidth();
		float v1 = (float) (py + cellH) / pageHeight();

		cursorCol += cells;
		if (cursorCol >= COLS) {
			cursorCol = 0;
			cursorRow++;
		}
		return new Entry(texture, u0, v0, u1, v1, cells);
	}

	private void newPage() {
		int id = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
		// NEAREST, not LINEAR: these are 1-bit pixel glyphs on a pixel-art screen, and
		// filtering them bleeds neighbouring cells into each other at the edges — visible as
		// faint ghosts of adjacent glyphs, which is exactly the artefact a texture atlas is
		// prone to. CLAMP for the same reason at the page border.
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
		ByteBuffer blank = BufferUtils.createByteBuffer(pageWidth() * pageHeight() * 4);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, pageWidth(), pageHeight(), 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, blank);
		pageTextures.add(Integer.valueOf(id));
		cursorCol = 0;
		cursorRow = 0;
	}

	/**
	 * Expand a 1-bit glyph into RGBA and upload it into its cell.
	 *
	 * White with alpha 0/255, so the draw colour multiplies cleanly: the renderer sets the
	 * colour and the glyph acts purely as a mask. Writing the glyph's own colour here instead
	 * would make coloured text impossible without re-uploading.
	 */
	private void upload(int texture, int px, int py, int w, int h, byte[] bits, int cells) {
		ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
		int bytesPerRow = cells * cellW / 8;
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				int byteIndex = row * bytesPerRow + col / 8;
				int bit = 7 - (col & 7);
				boolean on = byteIndex < bits.length && ((bits[byteIndex] >> bit) & 1) != 0;
				buf.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) (on ? 255 : 0));
			}
		}
		buf.flip();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, px, py, w, h,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
	}

	/** Drop every page. Called on resource reload, where the GL context may have changed. */
	void dispose() {
		for (Integer id : pageTextures) {
			GL11.glDeleteTextures(id.intValue());
		}
		pageTextures.clear();
		entries.clear();
		cursorCol = 0;
		cursorRow = 0;
	}
}
