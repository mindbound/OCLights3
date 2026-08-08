package opengpu.v2.font;

import opengpu.v2.protocol.V2Wire;

/**
 * Maps wire font ids to glyph sources, for both sides.
 *
 * ONE REGISTRY, CONSULTED BY BOTH, is the whole point. The server answers getTextWidth from
 * it and the client draws from it, so a string measured as font 1 is drawn with font 1 by
 * construction rather than by two implementations agreeing. Every layout bug this project has
 * had in text came from two sources of truth for one number.
 *
 * Loaded lazily and cached per id. The server asks for widths only; the client asks for
 * bitmaps too and gets a separate, fuller instance — see {@link #get(int, boolean)}. That
 * split matters on a dedicated server, where the glyph bitmaps are megabytes of data that
 * would never be drawn.
 *
 * Minecraft-free, so a headless server can measure text.
 */
public final class FontRegistry {

	private static final GlyphSource[] WIDTHS_ONLY = new GlyphSource[V2Wire.FONT_COUNT];
	private static final GlyphSource[] WITH_GLYPHS = new GlyphSource[V2Wire.FONT_COUNT];

	private FontRegistry() {}

	/**
	 * The font for a wire id, never null.
	 *
	 * @param withBitmaps true for the client, which must rasterize; false for the server,
	 *                    which only measures.
	 */
	public static synchronized GlyphSource get(int fontId, boolean withBitmaps) {
		if (!V2Wire.isValidFont(fontId)) {
			fontId = V2Wire.FONT_DEFAULT;
		}
		GlyphSource[] cache = withBitmaps ? WITH_GLYPHS : WIDTHS_ONLY;
		GlyphSource cached = cache[fontId];
		if (cached != null) {
			return cached;
		}
		GlyphSource loaded = load(fontId, withBitmaps);
		if (loaded == null) {
			// Degraded but DETERMINISTIC and identical on both sides. A font that failed to
			// load must not mean "each side guesses": a server measuring differently from the
			// client's pen corrupts layout silently, which is worse than measuring crudely.
			opengpu.OpenGPU.logger.warn("v2: font id " + fontId + " could not be loaded;"
					+ " falling back to blank monospace metrics. Text will measure but not"
					+ " render.");
			loaded = new MonospaceFallback(fontId == V2Wire.FONT_UNSCII8 ? 8 : 16);
		}
		cache[fontId] = loaded;
		return loaded;
	}

	private static GlyphSource load(int fontId, boolean withBitmaps) {
		switch (fontId) {
			case V2Wire.FONT_UNSCII8:
				return HexFont.loadUnscii8(withBitmaps);
			case V2Wire.FONT_DEFAULT:
			default:
				return HexFont.loadFromOpenComputers(withBitmaps);
		}
	}

	/** Human-readable name for logs and the Lua-facing limits table. */
	public static String nameOf(int fontId) {
		switch (fontId) {
			case V2Wire.FONT_UNSCII8:
				return "unscii8";
			case V2Wire.FONT_DEFAULT:
				return "unifont";
			default:
				return "font" + fontId;
		}
	}

	/**
	 * Wire id for a Lua-facing name, or -1 when unknown. Kept beside {@link #nameOf} so the
	 * two cannot drift; a name the library accepts but the server rejects would be a confusing
	 * failure at draw time rather than at selection time.
	 */
	public static int idOf(String name) {
		if ("unifont".equals(name) || "default".equals(name)) {
			return V2Wire.FONT_DEFAULT;
		}
		if ("unscii8".equals(name) || "unscii-8".equals(name)) {
			return V2Wire.FONT_UNSCII8;
		}
		return -1;
	}

	/** Drop cached fonts. Client resource reload only; the server has no reason to call it. */
	public static synchronized void invalidate() {
		for (int i = 0; i < V2Wire.FONT_COUNT; i++) {
			WIDTHS_ONLY[i] = null;
			WITH_GLYPHS[i] = null;
		}
	}

	/** Every codepoint one cell wide, no glyphs. Reachable only if a font fails to load. */
	private static final class MonospaceFallback implements GlyphSource {
		private final int height;

		MonospaceFallback(int height) {
			this.height = height;
		}

		@Override
		public int cellWidth() {
			return 8;
		}

		@Override
		public int cellHeight() {
			return height;
		}

		@Override
		public int advanceCells(int codepoint) {
			return 1;
		}

		@Override
		public byte[] bitmap(int codepoint) {
			return null;
		}
	}
}
