package opengpu.v2.mc.client.render;

import java.nio.IntBuffer;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * The only way any OpenGPU code touches FBO binding. Captures the observable GL state it
 * will disturb BY VALUE before switching to a scene FBO, and restores those exact values
 * after — never "0", never framebufferMc-by-name, never attrib stacks (all three fail under
 * Angelica in documented scenarios; see ANGELICA-NOTES.md, rules 3/5/9).
 *
 * Every GL call goes through plain GL11/OpenGlHelper entry points, which Angelica's
 * redirector tracks. glGetInteger reads are served from GLSM's cache under Angelica and from
 * the driver on vanilla — both correct.
 *
 * Render thread only.
 */
public final class FramebufferPass {
	// GL constants not exposed by the 1.7.10 class constants we use.
	private static final int GL_FRAMEBUFFER_BINDING = 36006;
	private static final int GL_ACTIVE_TEXTURE = 34016;
	private static final int GL_BLEND_DST_ALPHA = 32970;
	private static final int GL_BLEND_SRC_ALPHA = 32971;
	private static final int TEX_UNIT0 = OpenGlHelper.defaultTexUnit;   // 33984
	private static final int TEX_UNIT1 = OpenGlHelper.lightmapTexUnit;  // 33985

	private final IntBuffer viewport = BufferUtils.createIntBuffer(16);

	private final java.nio.FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(16);
	private final java.nio.ByteBuffer writeMaskBuffer = BufferUtils.createByteBuffer(16);

	private int savedFbo;
	private int savedViewportX, savedViewportY, savedViewportW, savedViewportH;
	private boolean savedBlend;
	private int savedBlendSrc, savedBlendDst;
	private int savedBlendSrcAlpha, savedBlendDstAlpha;
	private float savedLineWidth;
	private float savedColorR, savedColorG, savedColorB, savedColorA;
	private boolean savedMaskR, savedMaskG, savedMaskB, savedMaskA;
	private float savedClearR, savedClearG, savedClearB, savedClearA;
	private boolean savedAlphaTest;
	private boolean savedDepthTest;
	private boolean savedDepthMask;
	private boolean savedScissor;
	private boolean savedFog;
	private boolean savedLighting;
	private boolean savedCull;
	private int savedActiveTexture;
	private boolean savedTex2dUnit0;
	private boolean savedTex2dUnit1;
	private boolean active;

	public static boolean isSupported() {
		return OpenGlHelper.isFramebufferEnabled();
	}

	/** Bind the scene FBO and establish the canonical 2D state. Pair with {@link #end()}. */
	public void begin(int fbo, int width, int height) {
		if (active) {
			throw new IllegalStateException("FramebufferPass is not reentrant");
		}
		active = true;

		savedFbo = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
		viewport.clear();
		GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
		savedViewportX = viewport.get(0);
		savedViewportY = viewport.get(1);
		savedViewportW = viewport.get(2);
		savedViewportH = viewport.get(3);
		savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
		// GL_BLEND_SRC/DST alias the *RGB* factors; vanilla sets separate alpha factors via
		// OpenGlHelper.glBlendFunc nearly every frame, so restoring with the 2-arg call
		// would silently overwrite them with the RGB pair.
		savedBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
		savedBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
		savedBlendSrcAlpha = GL11.glGetInteger(GL_BLEND_SRC_ALPHA);
		savedBlendDstAlpha = GL11.glGetInteger(GL_BLEND_DST_ALPHA);
		savedLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
		colorBuffer.clear();
		GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, colorBuffer);
		savedClearR = colorBuffer.get(0);
		savedClearG = colorBuffer.get(1);
		savedClearB = colorBuffer.get(2);
		savedClearA = colorBuffer.get(3);
		writeMaskBuffer.clear();
		GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, writeMaskBuffer);
		savedMaskR = writeMaskBuffer.get(0) != 0;
		savedMaskG = writeMaskBuffer.get(1) != 0;
		savedMaskB = writeMaskBuffer.get(2) != 0;
		savedMaskA = writeMaskBuffer.get(3) != 0;
		colorBuffer.clear();
		GL11.glGetFloat(GL11.GL_CURRENT_COLOR, colorBuffer);
		savedColorR = colorBuffer.get(0);
		savedColorG = colorBuffer.get(1);
		savedColorB = colorBuffer.get(2);
		savedColorA = colorBuffer.get(3);
		savedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
		savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		savedScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		savedFog = GL11.glIsEnabled(GL11.GL_FOG);
		savedLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
		savedCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		savedActiveTexture = GL11.glGetInteger(GL_ACTIVE_TEXTURE);

		// Texture-2D enable state of units 0 and 1 — toggling these selects Iris program
		// variants, so they are part of the by-value contract (ANGELICA-NOTES rule 5).
		OpenGlHelper.setActiveTexture(TEX_UNIT1);
		savedTex2dUnit1 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		OpenGlHelper.setActiveTexture(TEX_UNIT0);
		savedTex2dUnit0 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
		// Canonicalize unit 0 too: the replay's texturing tracker assumes a known starting
		// value, and inheriting the HUD's (virtually always enabled) state draws untextured
		// primitives through a stale texel. end() restores the saved value either way.
		GL11.glDisable(GL11.GL_TEXTURE_2D);

		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, fbo);
		GL11.glViewport(0, 0, width, height);

		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		GL11.glDisable(GL11.GL_FOG);
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDepthMask(true);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		// Canvas outlines are 1px; the world's block-highlight pass leaves this at 2.
		GL11.glLineWidth(1.0F);
		// Clear FIRST, with every channel writable — glClear obeys the colour mask, so
		// masking alpha before this point leaves the attachment's alpha at its undefined
		// (in practice zero) initial contents and the whole scene reads as transparent.
		GL11.glColorMask(true, true, true, true);
		GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		// NOW pin alpha at the opaque value the clear just wrote: no recorded draw can
		// lower it. Blending math is unaffected (it reads the *source* alpha), so
		// translucent draws still composite correctly onto RGB. This is what lets surfaces
		// draw the scene texture without touching GL_BLEND, which is unsafe mid-world
		// under an Iris blend lock.
		GL11.glColorMask(true, true, true, false);

		// Matrices from scratch; the previous ones come back via the paired pops in end().
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glPushMatrix();
		GL11.glLoadIdentity();
		// Logical y-down: (0,0) is the canvas top-left, like every 2D canvas API.
		GL11.glOrtho(0, width, height, 0, -1, 1);
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPushMatrix();
		GL11.glLoadIdentity();
	}

	/** Restore every captured value. */
	public void end() {
		if (!active) {
			throw new IllegalStateException("end() without begin()");
		}
		active = false;

		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glPopMatrix();
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPopMatrix();

		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, savedFbo);
		GL11.glViewport(savedViewportX, savedViewportY, savedViewportW, savedViewportH);

		setEnabled(GL11.GL_BLEND, savedBlend);
		// The 4-arg wrapper degrades to plain glBlendFunc internally when separate blending
		// is unavailable, so this is safe unconditionally (and redirected under Angelica).
		OpenGlHelper.glBlendFunc(savedBlendSrc, savedBlendDst, savedBlendSrcAlpha, savedBlendDstAlpha);
		GL11.glLineWidth(savedLineWidth);
		GL11.glColor4f(savedColorR, savedColorG, savedColorB, savedColorA);
		GL11.glColorMask(savedMaskR, savedMaskG, savedMaskB, savedMaskA);
		GL11.glClearColor(savedClearR, savedClearG, savedClearB, savedClearA);
		setEnabled(GL11.GL_ALPHA_TEST, savedAlphaTest);
		setEnabled(GL11.GL_DEPTH_TEST, savedDepthTest);
		GL11.glDepthMask(savedDepthMask);
		setEnabled(GL11.GL_SCISSOR_TEST, savedScissor);
		setEnabled(GL11.GL_FOG, savedFog);
		setEnabled(GL11.GL_LIGHTING, savedLighting);
		setEnabled(GL11.GL_CULL_FACE, savedCull);

		OpenGlHelper.setActiveTexture(TEX_UNIT1);
		setEnabled(GL11.GL_TEXTURE_2D, savedTex2dUnit1);
		OpenGlHelper.setActiveTexture(TEX_UNIT0);
		setEnabled(GL11.GL_TEXTURE_2D, savedTex2dUnit0);
		// Restore whichever unit was active by value (it may be neither 0 nor 1).
		OpenGlHelper.setActiveTexture(savedActiveTexture);
	}

	private static void setEnabled(int cap, boolean enabled) {
		if (enabled) {
			GL11.glEnable(cap);
		} else {
			GL11.glDisable(cap);
		}
	}

	// ------------------------------------------------------------------
	// FBO lifecycle helpers (OpenGlHelper wrappers only; see ANGELICA-NOTES rule 1)

	/** Create an FBO with an RGBA8 color attachment. Returns {fbo, colorTexture} or null. */
	/**
	 * Largest scene dimension this GL context can allocate.
	 *
	 * GL_MAX_TEXTURE_SIZE is the binding limit here: the scene FBO's only attachment is a
	 * colour TEXTURE, so GL_MAX_RENDERBUFFER_SIZE — which the design names alongside it —
	 * does not apply until something attaches a renderbuffer. Queried lazily because it
	 * needs a current context, and cached because the answer cannot change within one.
	 */
	public static int maxSceneDimension() {
		if (maxDimension < 0) {
			maxDimension = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
		}
		return maxDimension;
	}

	private static int maxDimension = -1;

	public static int[] createSceneFbo(int width, int height) {
		// Checked BEFORE any GL call. glTexImage2D past GL_MAX_TEXTURE_SIZE raises
		// GL_INVALID_VALUE and allocates nothing, which then surfaces only as an incomplete
		// framebuffer — a confusing symptom for a knowable cause — and leaves an error in the
		// queue for whichever mod calls glGetError next. Unreachable while the scene size is
		// a compile-time constant; the moment resolution becomes settable it is the first
		// thing a program can drive out of range.
		int max = maxSceneDimension();
		if (width <= 0 || height <= 0 || width > max || height > max) {
			return null;
		}
		// Restored before returning: leaving the new color attachment bound would let a
		// later draw sample a texture attached to the active render target.
		int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int tex = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);

		int fbo = OpenGlHelper.func_153165_e();
		int previous = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, fbo);
		OpenGlHelper.func_153188_a(OpenGlHelper.field_153198_e, OpenGlHelper.field_153200_g,
				GL11.GL_TEXTURE_2D, tex, 0);
		int status = OpenGlHelper.func_153167_i(OpenGlHelper.field_153198_e);
		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, previous);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
		if (status != OpenGlHelper.field_153202_i) {
			OpenGlHelper.func_153174_h(fbo);
			GL11.glDeleteTextures(tex);
			return null;
		}
		return new int[] { fbo, tex };
	}

	public static void deleteSceneFbo(int fbo, int colorTexture) {
		OpenGlHelper.func_153174_h(fbo);
		GL11.glDeleteTextures(colorTexture);
	}
}
