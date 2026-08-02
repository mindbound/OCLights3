package opengpu.v2.mc.client;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.opengl.GL11;

import opengpu.v2.mc.server.TileEntityGpu2;
import opengpu.v2.protocol.MessageCodec;

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

	// Geometry of the last drawn frame, so input can be mapped back through the letterbox.
	private int drawX, drawY, drawW, drawH;
	private boolean drawnThisFrame;
	/** Which buttons are held, indexed by button id — a drag may use more than one. */
	private final boolean[] pressed = new boolean[3];
	/**
	 * Character typed for each held key code. LWJGL reports '\0' on release, so the char has
	 * to be remembered from the press — OC's own key_up carries the character, and a program
	 * matching on it would silently never fire otherwise.
	 */
	private final java.util.Map<Integer, Character> heldKeyChars =
			new java.util.HashMap<Integer, Character>();

	/**
	 * Screen pixels to LOGICAL scene coordinates, or null when the click missed the image.
	 * The scene is letterboxed into the GUI, so the inverse of that mapping lives here —
	 * the server and Lua only ever see logical coordinates.
	 */
	private int[] toLogical(int mouseX, int mouseY) {
		// Never map against a rect from an older frame: if the last draw bailed out (texture
		// not ready yet), the stored rect describes an image that is not on screen.
		if (!drawnThisFrame || drawW <= 0 || drawH <= 0) {
			return null;
		}
		int[] size = sceneSize();
		if (size == null) {
			return null;
		}
		if (mouseX < drawX || mouseY < drawY || mouseX >= drawX + drawW || mouseY >= drawY + drawH) {
			return null;
		}
		int lx = (int) ((double) (mouseX - drawX) / drawW * size[0]);
		int ly = (int) ((double) (mouseY - drawY) / drawH * size[1]);
		return new int[] { Math.max(0, Math.min(size[0] - 1, lx)),
				Math.max(0, Math.min(size[1] - 1, ly)) };
	}

	private int[] sceneSize() {
		String sceneId = gpu.clientSceneId();
		return sceneId == null ? null : V2ClientRuntime.get().renderer().sizeFor(sceneId);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		super.mouseClicked(mouseX, mouseY, button);
		int[] logical = toLogical(mouseX, mouseY);
		if (logical != null && button >= 0 && button < pressed.length) {
			pressed[button] = true;
			V2ClientRuntime.get().sendInput(gpu.clientSceneId(),
					MessageCodec.INPUT_POINTER_DOWN, logical[0], logical[1], button);
		}
	}

	@Override
	protected void mouseClickMove(int mouseX, int mouseY, int button, long heldMillis) {
		super.mouseClickMove(mouseX, mouseY, button, heldMillis);
		if (button < 0 || button >= pressed.length || !pressed[button]) {
			return; // a drag whose press landed outside the image is not ours
		}
		int[] logical = toLogical(mouseX, mouseY);
		if (logical != null) {
			V2ClientRuntime.get().sendInput(gpu.clientSceneId(),
					MessageCodec.INPUT_POINTER_MOVE, logical[0], logical[1], button);
		}
	}

	@Override
	protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
		super.mouseMovedOrUp(mouseX, mouseY, state);
		// state == -1 is a move with no button held; only a real release ends the gesture.
		if (state >= 0 && state < pressed.length && pressed[state]) {
			pressed[state] = false;
			int[] logical = toLogical(mouseX, mouseY);
			// Release outside the image still ends the press, clamped to the edge, so Lua
			// never sees a gesture that began and never finished.
			int[] target = logical != null ? logical : clampToEdge(mouseX, mouseY);
			if (target != null) {
				V2ClientRuntime.get().sendInput(gpu.clientSceneId(),
						MessageCodec.INPUT_POINTER_UP, target[0], target[1], state);
			}
		}
	}

	/**
	 * Closing the GUI mid-drag must still end the gesture. Otherwise the server keeps the
	 * press slot allocated, Lua sees a button that is held forever, and the next press
	 * cannot start cleanly.
	 */
	@Override
	public void onGuiClosed() {
		super.onGuiClosed();
		for (int button = 0; button < pressed.length; button++) {
			if (pressed[button]) {
				pressed[button] = false;
				V2ClientRuntime.get().sendInput(gpu.clientSceneId(),
						MessageCodec.INPUT_POINTER_UP, 0, 0, button);
			}
		}
		// Same reasoning for keys: a key held when the GUI closes never sees its release
		// event, so release them all rather than leave Lua believing they are still down.
		for (java.util.Map.Entry<Integer, Character> held : heldKeyChars.entrySet()) {
			V2ClientRuntime.get().sendInput(gpu.clientSceneId(), MessageCodec.INPUT_KEY_UP,
					held.getValue().charValue(), held.getKey().intValue(), 0);
		}
		heldKeyChars.clear();
	}

	/**
	 * Key RELEASE. GuiScreen only routes presses to keyTyped, so a release has to be read
	 * from the LWJGL event directly — without this, monitor_key_up has no producer at all
	 * and any program waiting on it hangs.
	 */
	@Override
	public void handleKeyboardInput() {
		super.handleKeyboardInput();
		if (!org.lwjgl.input.Keyboard.getEventKeyState()) {
			int keyCode = org.lwjgl.input.Keyboard.getEventKey();
			if (keyCode != org.lwjgl.input.Keyboard.KEY_ESCAPE) {
				Character held = heldKeyChars.remove(Integer.valueOf(keyCode));
				V2ClientRuntime.get().sendInput(gpu.clientSceneId(), MessageCodec.INPUT_KEY_UP,
						held == null ? 0 : held.charValue(), keyCode, 0);
			}
		}
	}

	private int[] clampToEdge(int mouseX, int mouseY) {
		int[] size = sceneSize();
		if (size == null || drawW <= 0) {
			return null;
		}
		int lx = (int) ((double) (mouseX - drawX) / drawW * size[0]);
		int ly = (int) ((double) (mouseY - drawY) / drawH * size[1]);
		return new int[] { Math.max(0, Math.min(size[0] - 1, lx)),
				Math.max(0, Math.min(size[1] - 1, ly)) };
	}

	@Override
	public void handleMouseInput() {
		super.handleMouseInput();
		int wheel = org.lwjgl.input.Mouse.getEventDWheel();
		if (wheel == 0) {
			return;
		}
		int mouseX = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
		int mouseY = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
		int[] logical = toLogical(mouseX, mouseY);
		if (logical != null) {
			// One notch per event: the server refuses anything else, so a fast wheel
			// produces several events rather than one amplified one.
			V2ClientRuntime.get().sendInput(gpu.clientSceneId(), MessageCodec.INPUT_SCROLL,
					logical[0], logical[1], wheel > 0 ? 1 : -1);
		}
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) {
		// ESC still closes the GUI; everything else goes to the scene.
		if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
			super.keyTyped(typedChar, keyCode);
			return;
		}
		heldKeyChars.put(Integer.valueOf(keyCode), Character.valueOf(typedChar));
		V2ClientRuntime.get().sendInput(gpu.clientSceneId(), MessageCodec.INPUT_KEY_DOWN,
				typedChar, keyCode, 0);
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
			drawnThisFrame = false;
			String status = sceneId == null ? "GPU is initializing..." : "Awaiting scene sync...";
			drawCenteredString(fontRendererObj, status, width / 2, height / 2, 0xFFFFFF);
			super.drawScreen(mouseX, mouseY, partialTicks);
			return;
		}

		// Deliberately NOT SurfaceFit: that fills its surface, this insets to 90% of the GUI
		// and snaps to whole pixels, and — unlike the wall — the forward and inverse here
		// share the stored draw rect below, so they cannot drift from each other. Sharing
		// them would mean parameterising the margin and the integer snapping to buy nothing.
		// Fit the scene into 90% of the GUI, preserving aspect.
		double maxW = width * 0.9, maxH = height * 0.9;
		double scale = Math.min(maxW / size[0], maxH / size[1]);
		int drawW = (int) (size[0] * scale);
		int drawH = (int) (size[1] * scale);
		int x0 = (width - drawW) / 2;
		int y0 = (height - drawH) / 2;
		// Remembered for input mapping: the inverse of this letterbox is how a click becomes
		// a logical coordinate.
		this.drawX = x0;
		this.drawY = y0;
		this.drawW = drawW;
		this.drawH = drawH;
		this.drawnThisFrame = true;

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
