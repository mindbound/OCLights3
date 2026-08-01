package opengpu.v2.mc.client;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.opengl.GL11;

import opengpu.v2.mc.server.TileEntityGpu2;

/**
 * The Stage A development surface: a GUI that shows a GPU's scene texture. It draws only
 * the already-rendered texture and marks the scene used — actual rendering happens in the
 * next frame's pre-pass, never here (the mandatory Angelica discipline for mid-frame
 * surfaces).
 */
public class GuiScene extends GuiScreen {
	private final TileEntityGpu2 gpu;

	public GuiScene(TileEntityGpu2 gpu) {
		this.gpu = gpu;
	}

	@Override
	public boolean doesGuiPauseGame() {
		// Pausing the integrated server would stall scene sync while the viewer is open.
		return false;
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		String sceneId = gpu.clientSceneId();
		V2ClientRuntime runtime = V2ClientRuntime.get();
		runtime.markUsed(sceneId);

		int texture = sceneId != null ? runtime.renderer().colorTextureFor(sceneId) : -1;
		int[] size = sceneId != null ? runtime.renderer().sizeFor(sceneId) : null;
		if (texture == -1 || size == null) {
			String status = sceneId == null ? "GPU is initializing..." : "Awaiting scene sync...";
			drawCenteredString(fontRendererObj, status, width / 2, height / 2, 0xFFFFFF);
			super.drawScreen(mouseX, mouseY, partialTicks);
			return;
		}

		// Fit the scene into 90% of the GUI, preserving aspect.
		double maxW = width * 0.9, maxH = height * 0.9;
		double scale = Math.min(maxW / size[0], maxH / size[1]);
		int drawW = (int) (size[0] * scale);
		int drawH = (int) (size[1] * scale);
		int x0 = (width - drawW) / 2;
		int y0 = (height - drawH) / 2;

		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(1f, 1f, 1f, 1f);
		// The FBO was rendered with a y-down ortho, which lands the canvas top at texture
		// v=1 — so the quad samples v=1 at its top edge.
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2d(0, 1);
		GL11.glVertex2d(x0, y0);
		GL11.glTexCoord2d(0, 0);
		GL11.glVertex2d(x0, y0 + drawH);
		GL11.glTexCoord2d(1, 0);
		GL11.glVertex2d(x0 + drawW, y0 + drawH);
		GL11.glTexCoord2d(1, 1);
		GL11.glVertex2d(x0 + drawW, y0);
		GL11.glEnd();

		super.drawScreen(mouseX, mouseY, partialTicks);
	}
}
