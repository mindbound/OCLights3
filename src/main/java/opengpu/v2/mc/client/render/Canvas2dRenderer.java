package opengpu.v2.mc.client.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import opengpu.v2.mc.FontMetrics;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneCanvas;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;

/**
 * Replays a scene's node list into the currently bound FBO (a FramebufferPass must be
 * active: ortho projection, logical y-down, blend on). Canvas transform ops run on a
 * CPU-side affine stack — vertices are transformed before submission, so no GL matrix
 * state is touched during replay (the design's record-time-capped matrix stack).
 *
 * Command semantics mirrored from the server-side normative rules: FILL ignores the
 * transform (whole-canvas raster fill, the compaction anchor); CLEAR_RECT is a hard set
 * (blend off); pending textures draw nothing (the defined transparent placeholder).
 */
public final class Canvas2dRenderer {
	private static final ResourceLocation FONT = new ResourceLocation("oclights", "textures/gui/ascii.png");
	private static final int OVAL_SEGMENTS = 48;

	/** Row-major 2D affine: x' = a*x + c*y + e; y' = b*x + d*y + f. */
	private static final class Affine {
		double a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;

		void set(Affine o) {
			a = o.a; b = o.b; c = o.c; d = o.d; e = o.e; f = o.f;
		}

		void identity() {
			a = 1; b = 0; c = 0; d = 1; e = 0; f = 0;
		}

		void translate(double dx, double dy) {
			e += a * dx + c * dy;
			f += b * dx + d * dy;
		}

		void rotate(double rad) {
			double cos = Math.cos(rad), sin = Math.sin(rad);
			double na = a * cos + c * sin;
			double nb = b * cos + d * sin;
			double nc = -a * sin + c * cos;
			double nd = -b * sin + d * cos;
			a = na; b = nb; c = nc; d = nd;
		}

		void scale(double sx, double sy) {
			a *= sx; b *= sx; c *= sy; d *= sy;
		}

		double tx(double x, double y) {
			return a * x + c * y + e;
		}

		double ty(double x, double y) {
			return b * x + d * y + f;
		}
	}

	// Replay state (single-threaded render use).
	private final Affine node = new Affine();
	private final Affine local = new Affine();
	private final Affine effective = new Affine();
	private final List<double[]> stack = new ArrayList<double[]>();
	private double colR, colG, colB, colA;
	private boolean texturing;

	public void renderScene(SceneState state, int width, int height, Map<Integer, Integer> glTextures) {
		// This renderer is shared across passes, so the texturing shadow must be re-synced
		// with real GL at every entry — never inherited from the previous scene's tail.
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		texturing = false;
		// The framebuffer is cleared by FramebufferPass.begin(), which owns the ordering
		// between the clear and the alpha mask that keeps the attachment opaque.

		List<SceneNode> ordered = new ArrayList<SceneNode>(state.nodes.values());
		Collections.sort(ordered, new Comparator<SceneNode>() {
			@Override
			public int compare(SceneNode n1, SceneNode n2) {
				if (n1.z != n2.z) {
					return n1.z < n2.z ? -1 : 1;
				}
				return n1.id < n2.id ? -1 : n1.id == n2.id ? 0 : 1;
			}
		});
		for (SceneNode sceneNode : ordered) {
			if (!sceneNode.visible) {
				continue;
			}
			if (sceneNode.type == V2Wire.NODE_CANVAS) {
				ResourceInfo res = state.resources.get(sceneNode.ref);
				if (res != null && res.type == V2Wire.RES_CANVAS && res.canvas != null) {
					replayCanvas(res.canvas, sceneNode, state, glTextures);
				}
			} else if (sceneNode.type == V2Wire.NODE_SPRITE) {
				ResourceInfo res = state.resources.get(sceneNode.ref);
				if (res != null && res.type == V2Wire.RES_TEXTURE) {
					drawSprite(sceneNode, res, glTextures);
				}
			}
			// NODE_GROUP: Stage B.
		}
	}

	private void beginNode(SceneNode sceneNode) {
		node.identity();
		node.translate(sceneNode.x, sceneNode.y);
		node.rotate(sceneNode.rot);
		node.scale(sceneNode.sx, sceneNode.sy);
		local.identity();
		stack.clear();
		colR = 1; colG = 1; colB = 1; colA = 1;
		setTexturing(false);
		updateEffective();
	}

	private void updateEffective() {
		// effective = node ∘ local
		effective.a = node.a * local.a + node.c * local.b;
		effective.b = node.b * local.a + node.d * local.b;
		effective.c = node.a * local.c + node.c * local.d;
		effective.d = node.b * local.c + node.d * local.d;
		effective.e = node.a * local.e + node.c * local.f + node.e;
		effective.f = node.b * local.e + node.d * local.f + node.f;
	}

	private void replayCanvas(SceneCanvas canvas, SceneNode sceneNode, SceneState state,
			Map<Integer, Integer> glTextures) {
		beginNode(sceneNode);
		for (CanvasCommand cmd : canvas.visibleCommands()) {
			double[] a = cmd.args;
			switch (cmd.op) {
				case V2Wire.OP_SET_COLOR:
					colR = a[0] / 255.0; colG = a[1] / 255.0; colB = a[2] / 255.0; colA = a[3] / 255.0;
					break;
				case V2Wire.OP_FILL:
					fillWholeCanvas(canvas.width, canvas.height, false);
					break;
				case V2Wire.OP_PLOT:
					quad(a[0], a[1], 1, 1);
					break;
				case V2Wire.OP_LINE:
					line(a[0], a[1], a[2], a[3]);
					break;
				case V2Wire.OP_RECT:
					rectOutline(a[0], a[1], a[2], a[3]);
					break;
				case V2Wire.OP_FILL_RECT:
					quad(a[0], a[1], a[2], a[3]);
					break;
				case V2Wire.OP_TRIANGLE:
					triangle(a, false);
					break;
				case V2Wire.OP_FILL_TRIANGLE:
					triangle(a, true);
					break;
				case V2Wire.OP_OVAL:
					oval(a[0], a[1], a[2], a[3], false);
					break;
				case V2Wire.OP_FILL_OVAL:
					oval(a[0], a[1], a[2], a[3], true);
					break;
				case V2Wire.OP_CLEAR_RECT:
					GL11.glDisable(GL11.GL_BLEND);
					quad(a[0], a[1], a[2], a[3]);
					GL11.glEnable(GL11.GL_BLEND);
					break;
				case V2Wire.OP_DRAW_TEXT:
					drawText(cmd.text, a[0], a[1]);
					break;
				case V2Wire.OP_DRAW_TEXTURE: {
					ResourceInfo res = state.resources.get((int) a[0]);
					Integer glId = glTextures.get((int) a[0]);
					if (res != null && glId != null) {
						untintedQuad(glId, a[1], a[2], res.width, res.height, 0, 0, 1, 1);
					}
					break;
				}
				case V2Wire.OP_DRAW_TEXTURE_SUB: {
					ResourceInfo res = state.resources.get((int) a[0]);
					Integer glId = glTextures.get((int) a[0]);
					if (res != null && glId != null && res.width > 0 && res.height > 0) {
						double u0 = a[3] / res.width, v0 = a[4] / res.height;
						double u1 = (a[3] + a[5]) / res.width, v1 = (a[4] + a[6]) / res.height;
						untintedQuad(glId, a[1], a[2], a[5], a[6], u0, v0, u1, v1);
					}
					break;
				}
				case V2Wire.OP_TRANSLATE:
					local.translate(a[0], a[1]);
					updateEffective();
					break;
				case V2Wire.OP_ROTATE:
					local.rotate(a[0]);
					updateEffective();
					break;
				case V2Wire.OP_ROTATE_AROUND:
					local.translate(a[1], a[2]);
					local.rotate(a[0]);
					local.translate(-a[1], -a[2]);
					updateEffective();
					break;
				case V2Wire.OP_SCALE:
					local.scale(a[0], a[1]);
					updateEffective();
					break;
				case V2Wire.OP_PUSH:
					stack.add(new double[] { local.a, local.b, local.c, local.d, local.e, local.f });
					break;
				case V2Wire.OP_POP:
					if (!stack.isEmpty()) {
						double[] m = stack.remove(stack.size() - 1);
						local.a = m[0]; local.b = m[1]; local.c = m[2];
						local.d = m[3]; local.e = m[4]; local.f = m[5];
						updateEffective();
					}
					break;
				case V2Wire.OP_ORIGIN:
					local.identity();
					updateEffective();
					break;
				default:
					// Unknown ops cannot arrive: the codec rejects them at decode time.
					break;
			}
		}
	}

	private void drawSprite(SceneNode sceneNode, ResourceInfo res, Map<Integer, Integer> glTextures) {
		Integer glId = glTextures.get(res.id);
		if (glId == null) {
			return; // pending
		}
		beginNode(sceneNode);
		int tint = sceneNode.tint;
		colA = (tint >>> 24 & 0xFF) / 255.0;
		colR = (tint >>> 16 & 0xFF) / 255.0;
		colG = (tint >>> 8 & 0xFF) / 255.0;
		colB = (tint & 0xFF) / 255.0;
		texturedQuad(glId, 0, 0, res.width, res.height, 0, 0, 1, 1);
	}

	// ------------------------------------------------------------------
	// Primitives (all vertices go through the effective affine)

	private void setTexturing(boolean on) {
		if (texturing != on) {
			texturing = on;
			if (on) {
				GL11.glEnable(GL11.GL_TEXTURE_2D);
			} else {
				GL11.glDisable(GL11.GL_TEXTURE_2D);
			}
		}
	}

	private void color() {
		GL11.glColor4d(colR, colG, colB, colA);
	}

	private void vertex(double x, double y) {
		GL11.glVertex2d(effective.tx(x, y), effective.ty(x, y));
	}

	/** FILL ignores the transform: a raster fill of the whole canvas (compaction anchor). */
	private void fillWholeCanvas(int width, int height, boolean unused) {
		setTexturing(false);
		color();
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex2d(0, 0);
		GL11.glVertex2d(0, height);
		GL11.glVertex2d(width, height);
		GL11.glVertex2d(width, 0);
		GL11.glEnd();
	}

	private void quad(double x, double y, double w, double h) {
		setTexturing(false);
		color();
		GL11.glBegin(GL11.GL_QUADS);
		vertex(x, y);
		vertex(x, y + h);
		vertex(x + w, y + h);
		vertex(x + w, y);
		GL11.glEnd();
	}

	private void line(double x1, double y1, double x2, double y2) {
		setTexturing(false);
		color();
		GL11.glBegin(GL11.GL_LINES);
		vertex(x1, y1);
		vertex(x2, y2);
		GL11.glEnd();
	}

	private void rectOutline(double x, double y, double w, double h) {
		setTexturing(false);
		color();
		GL11.glBegin(GL11.GL_LINE_LOOP);
		vertex(x, y);
		vertex(x + w, y);
		vertex(x + w, y + h);
		vertex(x, y + h);
		GL11.glEnd();
	}

	private void triangle(double[] a, boolean filled) {
		setTexturing(false);
		color();
		GL11.glBegin(filled ? GL11.GL_TRIANGLES : GL11.GL_LINE_LOOP);
		vertex(a[0], a[1]);
		vertex(a[2], a[3]);
		vertex(a[4], a[5]);
		GL11.glEnd();
	}

	/** Center-anchored: (cx, cy) with full width w and height h. */
	private void oval(double cx, double cy, double w, double h, boolean filled) {
		setTexturing(false);
		color();
		double rx = w / 2.0, ry = h / 2.0;
		if (filled) {
			GL11.glBegin(GL11.GL_TRIANGLE_FAN);
			vertex(cx, cy);
			for (int i = 0; i <= OVAL_SEGMENTS; i++) {
				double t = 2 * Math.PI * i / OVAL_SEGMENTS;
				vertex(cx + rx * Math.cos(t), cy + ry * Math.sin(t));
			}
			GL11.glEnd();
		} else {
			GL11.glBegin(GL11.GL_LINE_LOOP);
			for (int i = 0; i < OVAL_SEGMENTS; i++) {
				double t = 2 * Math.PI * i / OVAL_SEGMENTS;
				vertex(cx + rx * Math.cos(t), cy + ry * Math.sin(t));
			}
			GL11.glEnd();
		}
	}

	private void drawText(String text, double x, double y) {
		Minecraft.getMinecraft().getTextureManager().bindTexture(FONT);
		setTexturing(true);
		color();
		FontMetrics metrics = FontMetrics.get();
		double pen = x;
		GL11.glBegin(GL11.GL_QUADS);
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			int glyph = ch < 256 ? ch : '?';
			double u0 = (glyph % 16) / 16.0;
			double v0 = (glyph / 16) / 16.0;
			double u1 = u0 + 1 / 16.0;
			double v1 = v0 + 1 / 16.0;
			// Full 8x8 cell quad; the pen advances by the glyph's scanned width.
			GL11.glTexCoord2d(u0, v0);
			vertex(pen, y);
			GL11.glTexCoord2d(u0, v1);
			vertex(pen, y + FontMetrics.GLYPH_HEIGHT);
			GL11.glTexCoord2d(u1, v1);
			vertex(pen + 8, y + FontMetrics.GLYPH_HEIGHT);
			GL11.glTexCoord2d(u1, v0);
			vertex(pen + 8, y);
			pen += metrics.charAdvance(ch);
		}
		GL11.glEnd();
	}

	/**
	 * Draw a texture at its own colours, ignoring the canvas draw colour.
	 *
	 * The draw colour is ambient state meant for shapes and text; letting it modulate blits
	 * too means a fill colour set several commands earlier silently darkens or recolours
	 * every later texture — a footgun that costs an image and gives no error. Per-object
	 * tinting lives on Sprite nodes, which carry an explicit {@code tint} property.
	 */
	private void untintedQuad(int glId, double x, double y, double w, double h,
			double u0, double v0, double u1, double v1) {
		double r = colR, g = colG, b = colB, a = colA;
		colR = 1; colG = 1; colB = 1; colA = 1;
		texturedQuad(glId, x, y, w, h, u0, v0, u1, v1);
		colR = r; colG = g; colB = b; colA = a;
	}

	private void texturedQuad(int glId, double x, double y, double w, double h,
			double u0, double v0, double u1, double v1) {
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
		setTexturing(true);
		color();
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2d(u0, v0);
		vertex(x, y);
		GL11.glTexCoord2d(u0, v1);
		vertex(x, y + h);
		GL11.glTexCoord2d(u1, v1);
		vertex(x + w, y + h);
		GL11.glTexCoord2d(u1, v0);
		vertex(x + w, y);
		GL11.glEnd();
	}
}
