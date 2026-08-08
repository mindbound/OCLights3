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

	/*
	 * ON DETECTING A ONE-SIDED FALLBACK — what is delivered here, and what is not.
	 *
	 * The fallback's whole safety argument is that BOTH sides take it: it advances every
	 * codepoint one cell, so two peers that both fell back still agree, while one that fell back
	 * and one that did not disagree on every double-width glyph — CJK measured at half its drawn
	 * width, permanently, and convergence checking blind to it because the command lists are
	 * identical and only the drawing differs.
	 *
	 * The delivered mechanism is the ERROR logged below, naming the side. That is all. A first
	 * draft also recorded the fact in a pair of arrays behind a hasFallenBack() accessor; it was
	 * removed because NOTHING READ IT — state with no reader is the same kind of decoration as a
	 * javadoc with no behaviour behind it, and this file has already paid for one of those.
	 *
	 * Actually detecting the mismatch needs the loaded glyph count on the wire so the two sides
	 * can compare. That is a protocol change, deliberately not made here, and recorded in
	 * ROADMAP under Defects.
	 */

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
			//
			// ERROR rather than warn, and via FontDiagnostics rather than OpenGPU.logger. The
			// old call made this class's "Minecraft-free" promise false, and OpenGPU.logger is
			// assigned in preInit -- so a load reached before then threw NullPointerException
			// from inside the very path that exists to keep things working.
			FontDiagnostics.error("font '" + nameOf(fontId) + "' (id " + fontId + ", "
					+ (withBitmaps ? "client/with bitmaps" : "server/widths only")
					+ ") could not be loaded; falling back to blank monospace metrics. Text will"
					+ " measure but not render, and EVERY codepoint counts as one cell -- so if"
					+ " the other side loaded this font successfully, their layouts now disagree"
					+ " on every double-width glyph. Check that OpenComputers' font.hex is"
					+ " readable on BOTH the client and the server.");
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

	/**
	 * Drop cached fonts, so the next {@link #get} reloads from the classpath.
	 *
	 * NO CALLER ANYWHERE TODAY — not in production and not in the tests. It was written for a
	 * client resource reload and nothing wires one up; the fonts are read from the classpath
	 * rather than through Minecraft's resource manager (see {@link HexFont}'s javadoc for why),
	 * so a resource-pack reload cannot change them and there is nothing for a reload hook to do.
	 * Kept as what a future "reload fonts" command would call.
	 *
	 * If one is ever wired up it must also dispose the client's {@code GlyphAtlas} instances:
	 * each atlas holds a hard reference to the {@code GlyphSource} it was built from, so
	 * invalidating here alone would leave the pen reading advances from a new font while the
	 * quads still come from the old atlas.
	 */
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
