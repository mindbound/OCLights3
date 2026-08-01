package opengpu.v2.mc.client.render;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import opengpu.v2.mc.client.V2ClientRuntime;
import opengpu.v2.mc.server.TileEntityScreen2;

/**
 * Draws a bound scene onto the screen block's face.
 *
 * This renderer ONLY draws an already-rendered texture — it never renders scene content
 * here. Two Angelica rules force that: a TESR is dispatched twice per frame under
 * shadow-casting packs (with the shadow FBO and viewport bound), and mid-frame blend state
 * can be locked so lazily-issued draws are silently deferred. Instead we mark the scene
 * used, and the next RenderTickEvent.START pre-pass renders it (one frame of latency,
 * invisible at 20 tps).
 *
 * All state touched here is restored explicitly by value — no attrib stacks (rule 3).
 */
public class ScreenRenderer extends TileEntitySpecialRenderer {
	/** Inset from the block face so the quad never z-fights with the block itself. */
	private static final double FACE_OFFSET = 0.501;
	private static final ResourceLocation BLOCK_ATLAS =
			new ResourceLocation("textures/atlas/blocks.png");

	@Override
	public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTicks) {
		if (!(tile instanceof TileEntityScreen2)) {
			return;
		}
		TileEntityScreen2 screen = (TileEntityScreen2) tile;
		String sceneId = screen.sceneId();
		if (sceneId == null) {
			return;
		}
		V2ClientRuntime runtime = V2ClientRuntime.get();
		// Ask for this scene to be rendered in the next pre-pass, whether or not we can
		// draw it this frame.
		runtime.markUsed(sceneId);

		int texture = runtime.renderer().colorTextureFor(sceneId);
		if (texture == -1) {
			return; // not rendered yet: the block's own face texture shows through
		}
		// Letterbox: preserve the scene's aspect inside the square face, so circles stay
		// circular and a future 16:9 multiblock wall fills edge to edge unchanged.
		int[] size = runtime.renderer().sizeFor(sceneId);
		double halfW = 0.5, halfH = 0.5;
		if (size != null && size[0] > 0 && size[1] > 0) {
			double aspect = (double) size[0] / size[1];
			if (aspect >= 1.0) {
				halfH = 0.5 / aspect;
			} else {
				halfW = 0.5 * aspect;
			}
		}

		boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
		// GL_BLEND is deliberately NOT touched here. Under an Iris `blend.` directive the
		// pipeline saves and overrides blend state, and our disable/enable pair — balanced
		// though it is — corrupts what it restores. The scene FBO is written fully opaque
		// (FramebufferPass masks alpha), so blending the face quad is a no-op anyway.
		// A screen emits its own light. Disabling GL_LIGHTING is not enough: the world
		// lightmap on texture unit 1 still multiplies the fragment, so a screen in a dim
		// room renders at the room's brightness. Force full-bright for the quad and restore
		// the previous coords by value afterwards (reading lastBrightnessX/Y is allowed;
		// writing those fields directly is not — GLSM owns them).
		float lastBrightnessX = OpenGlHelper.lastBrightnessX;
		float lastBrightnessY = OpenGlHelper.lastBrightnessY;
		OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

		GL11.glPushMatrix();
		GL11.glTranslated(x + 0.5, y + 0.5, z + 0.5);
		GL11.glRotatef(faceRotation(screen.facing()), 0.0F, 1.0F, 0.0F);
		GL11.glTranslated(0.0, 0.0, -FACE_OFFSET);

		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);

		// The scene FBO was rendered under a y-down ortho, so the canvas top edge lives at
		// v = 1 — the same flip GuiScene applies.
		GL11.glBegin(GL11.GL_QUADS);
		// Outward face normal in the rotated local frame. Without it the quad inherits
		// whatever normal the previous entity/TESR left current, which Iris writes into
		// the normal buffer and shader packs then light from.
		GL11.glNormal3f(0.0F, 0.0F, -1.0F);
		GL11.glTexCoord2d(0.0, 1.0);
		GL11.glVertex3d(halfW, halfH, 0.0);
		GL11.glTexCoord2d(0.0, 0.0);
		GL11.glVertex3d(halfW, -halfH, 0.0);
		GL11.glTexCoord2d(1.0, 0.0);
		GL11.glVertex3d(-halfW, -halfH, 0.0);
		GL11.glTexCoord2d(1.0, 1.0);
		GL11.glVertex3d(-halfW, halfH, 0.0);
		GL11.glEnd();

		GL11.glPopMatrix();

		// Restore by value, and hand the block atlas back to whatever renders next — we
		// bound a raw GL texture that Minecraft's TextureManager knows nothing about.
		OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastBrightnessX, lastBrightnessY);
		if (lighting) {
			GL11.glEnable(GL11.GL_LIGHTING);
		}
		bindTexture(BLOCK_ATLAS);
	}

	/** Rotation that puts the quad on the block face named by the metadata (2..5). */
	private static float faceRotation(int facing) {
		switch (facing) {
			case 3:  return 180.0F; // south (+Z)
			case 4:  return 90.0F;  // west  (-X)
			case 5:  return 270.0F; // east  (+X)
			default: return 0.0F;   // north (-Z)
		}
	}
}
