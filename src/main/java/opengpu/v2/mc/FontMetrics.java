package opengpu.v2.mc;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * Metrics for the built-in 6x8-style bitmap font atlas (16x16 glyph grid). Loaded from the
 * shipped ascii.png on BOTH sides from the same code path, so the server's getTextWidth and
 * the client's glyph advances are bit-identical by construction (the design's "layout must
 * match client rendering" requirement for the built-in font).
 *
 * The advance table is the vanilla FontRenderer pixel-scan: rightmost non-transparent column
 * + 1, glyph space normalized to 8 logical units of height; space is fixed at 4. (The legacy
 * port's accidental 1px space is not preserved — v2 owns its metrics.)
 *
 * ImageIO decode only — no AWT rasterization, headless-safe on dedicated servers.
 */
public final class FontMetrics {
	/**
	 * Deliberately a literal rather than {@code OpenGPU.ASSET_DOMAIN}, which holds the same
	 * value. This class must stay Minecraft-free so a dedicated server can answer
	 * {@code getTextWidth} headlessly, and referencing the {@code @Mod} class would drag
	 * Block, Item and CreativeTabs onto its classpath. Keep the two in step by hand: if the
	 * asset domain is ever renamed again, this line changes with it.
	 */
	public static final String ATLAS_RESOURCE = "/assets/opengpu/textures/gui/ascii.png";
	/** Logical glyph height; the atlas cell is normalized to 8 units like vanilla. */
	public static final int GLYPH_HEIGHT = 8;

	private static volatile FontMetrics instance;

	private final int[] charWidth = new int[256];

	public static FontMetrics get() {
		FontMetrics local = instance;
		if (local == null) {
			synchronized (FontMetrics.class) {
				local = instance;
				if (local == null) {
					local = new FontMetrics();
					instance = local;
				}
			}
		}
		return local;
	}

	private FontMetrics() {
		BufferedImage atlas = null;
		try {
			InputStream in = FontMetrics.class.getResourceAsStream(ATLAS_RESOURCE);
			if (in != null) {
				try {
					atlas = ImageIO.read(in);
				} finally {
					in.close();
				}
			}
		} catch (IOException ignored) {
			// Fall through to the fixed-width fallback below.
		}
		if (atlas == null) {
			// Degraded but deterministic on both sides: plain 6px monospace.
			for (int i = 0; i < 256; i++) {
				charWidth[i] = 6;
			}
			return;
		}
		int atlasW = atlas.getWidth();
		int atlasH = atlas.getHeight();
		int[] pixels = new int[atlasW * atlasH];
		atlas.getRGB(0, 0, atlasW, atlasH, pixels, 0, atlasW);
		int cellH = atlasH / 16;
		int cellW = atlasW / 16;
		float scale = 8.0F / cellW;
		for (int ch = 0; ch < 256; ch++) {
			if (ch == 32) {
				charWidth[ch] = 4;
				continue;
			}
			int col = ch % 16;
			int row = ch / 16;
			int x = cellW - 1;
			while (x >= 0) {
				int px = col * cellW + x;
				boolean empty = true;
				for (int y = 0; y < cellH && empty; y++) {
					int py = (row * cellH + y) * atlasW;
					if ((pixels[px + py] >> 24 & 255) != 0) {
						empty = false;
					}
				}
				if (empty) {
					x--;
				} else {
					break;
				}
			}
			charWidth[ch] = (int) (0.5D + (x + 1) * scale) + 1;
		}
	}

	/** Advance of one character in logical units; unknown code points advance like '?'. */
	public int charAdvance(char c) {
		return charWidth[c < 256 ? c : '?'];
	}

	/** Logical width of a string — the server-side getTextWidth answer. */
	public int textWidth(String text) {
		int w = 0;
		for (int i = 0; i < text.length(); i++) {
			w += charAdvance(text.charAt(i));
		}
		return w;
	}
}
