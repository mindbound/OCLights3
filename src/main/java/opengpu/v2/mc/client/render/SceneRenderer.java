package opengpu.v2.mc.client.render;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import opengpu.OpenGPU;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.sync.MirrorClient;

/**
 * The central per-scene GL cache and pre-pass driver (never TE-owned, per the design).
 * Runs exclusively on the render thread, in RenderTickEvent.START.
 *
 * Responsibilities: FBO-per-scene lifecycle, resource-texture uploads under a per-frame
 * byte budget, pruning GL objects whose resources/mirrors are gone, and re-rendering dirty
 * scenes through {@link FramebufferPass} + {@link Canvas2dRenderer}.
 */
public final class SceneRenderer {
	/** Per-frame texture upload budget: one 1024x512 RGBA texture's worth of bytes. */
	public static final long UPLOAD_BUDGET_PER_FRAME = 2L * 1024 * 1024;

	private static final class TexEntry {
		int glId;
		byte[] uploadedRef;
	}

	private static final class SceneGl {
		int fbo = -1;
		int colorTex = -1;
		int width, height;
		boolean everRendered;
		/**
		 * Set whenever the GL texture set changes. A body that arrives after the batch that
		 * referenced it (budget-deferred, or simply delivered later) does not dirty the
		 * mirror, so without this the scene keeps showing the placeholder until some
		 * unrelated batch happens to arrive.
		 */
		boolean uploadDirty;
		final Map<Integer, TexEntry> textures = new HashMap<Integer, TexEntry>();
	}

	private final Map<String, SceneGl> scenes = new HashMap<String, SceneGl>();
	private final FramebufferPass pass = new FramebufferPass();
	private final Canvas2dRenderer canvasRenderer = new Canvas2dRenderer();
	private boolean fboFailureLogged;

	/** The scene's rendered texture for surfaces to draw, or -1 if not yet rendered. */
	public int colorTextureFor(String sceneId) {
		SceneGl gl = scenes.get(sceneId);
		return gl != null && gl.everRendered ? gl.colorTex : -1;
	}

	/** Logical size of the rendered scene texture, or null. */
	public int[] sizeFor(String sceneId) {
		SceneGl gl = scenes.get(sceneId);
		return gl != null && gl.everRendered ? new int[] { gl.width, gl.height } : null;
	}

	/**
	 * The pre-pass: prune dead GL state, upload pending texture bytes under the frame
	 * budget, then render every used-and-dirty (or never-rendered) scene.
	 */
	public void prePass(MirrorClient mirrors, Set<String> usedScenes) {
		if (!FramebufferPass.isSupported()) {
			if (!fboFailureLogged) {
				fboFailureLogged = true;
				OpenGPU.logger.warn("Framebuffer objects unavailable; v2 scene rendering disabled "
						+ "(the non-FBO fallback arrives in a later increment)");
			}
			return;
		}
		pruneDeadScenes(mirrors);
		long budget = UPLOAD_BUDGET_PER_FRAME;
		for (String sceneId : usedScenes) {
			if (!mirrors.hasMirror(sceneId)) {
				continue;
			}
			SceneMirror mirror = mirrors.mirror(sceneId);
			SceneGl gl = scenes.get(sceneId);
			if (gl == null) {
				gl = new SceneGl();
				scenes.put(sceneId, gl);
			}
			budget = uploadTextures(gl, mirror, budget);
			renderIfNeeded(gl, mirror);
		}
	}

	private void pruneDeadScenes(MirrorClient mirrors) {
		Iterator<Map.Entry<String, SceneGl>> iter = scenes.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, SceneGl> entry = iter.next();
			if (!mirrors.hasMirror(entry.getKey())) {
				dispose(entry.getValue());
				iter.remove();
			}
		}
	}

	/** Upload delivered texture bytes; free GL textures whose resources are gone. */
	private long uploadTextures(SceneGl gl, SceneMirror mirror, long budget) {
		Map<Integer, ResourceInfo> resources = mirror.state().resources;
		// Prune first: freed resources release their GL objects immediately.
		Iterator<Map.Entry<Integer, TexEntry>> iter = gl.textures.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<Integer, TexEntry> entry = iter.next();
			ResourceInfo res = resources.get(entry.getKey());
			if (res == null || res.type != V2Wire.RES_TEXTURE) {
				GL11.glDeleteTextures(entry.getValue().glId);
				iter.remove();
				gl.uploadDirty = true;
			}
		}
		for (ResourceInfo res : resources.values()) {
			if (res.type != V2Wire.RES_TEXTURE || res.bytes == null) {
				continue; // pending: renders as the defined transparent placeholder
			}
			TexEntry entry = gl.textures.get(res.id);
			if (entry != null && entry.uploadedRef == res.bytes) {
				continue;
			}
			long size = res.bytes.length;
			// Always admit the head of the queue: a texture bigger than one frame's budget
			// would otherwise be skipped forever (the budget resets to the same value every
			// frame), leaving a legally-created texture permanently invisible. Admitting it
			// against an untouched budget costs one hitchy frame instead.
			if (size > budget && budget < UPLOAD_BUDGET_PER_FRAME) {
				continue; // over budget this frame; a later pre-pass picks it up
			}
			budget -= size;
			if (entry == null) {
				entry = new TexEntry();
				entry.glId = GL11.glGenTextures();
				gl.textures.put(res.id, entry);
			}
			uploadRgba(entry.glId, res.width, res.height, res.bytes);
			entry.uploadedRef = res.bytes;
			gl.uploadDirty = true;
		}
		return budget;
	}

	private static void uploadRgba(int glId, int width, int height, byte[] rgba) {
		ByteBuffer buffer = BufferUtils.createByteBuffer(rgba.length);
		buffer.put(rgba);
		buffer.flip();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
	}

	private void renderIfNeeded(SceneGl gl, SceneMirror mirror) {
		int[] size = sceneLogicalSize(mirror);
		if (size == null) {
			return; // no canvas node yet — nothing to render
		}
		boolean resized = gl.fbo != -1 && (gl.width != size[0] || gl.height != size[1]);
		if (gl.fbo == -1 || resized) {
			if (resized) {
				FramebufferPass.deleteSceneFbo(gl.fbo, gl.colorTex);
				gl.fbo = -1;
				gl.colorTex = -1;
				gl.everRendered = false;
			}
			int[] created = FramebufferPass.createSceneFbo(size[0], size[1]);
			if (created == null) {
				if (!fboFailureLogged) {
					fboFailureLogged = true;
					OpenGPU.logger.warn("Scene FBO creation failed (" + size[0] + "x" + size[1] + ")");
				}
				return;
			}
			gl.fbo = created[0];
			gl.colorTex = created[1];
			gl.width = size[0];
			gl.height = size[1];
		}
		if (!mirror.isDirty() && !gl.uploadDirty && gl.everRendered) {
			return;
		}
		Map<Integer, Integer> glMap = new HashMap<Integer, Integer>();
		for (Map.Entry<Integer, TexEntry> entry : gl.textures.entrySet()) {
			glMap.put(entry.getKey(), entry.getValue().glId);
		}
		pass.begin(gl.fbo, gl.width, gl.height);
		try {
			canvasRenderer.renderScene(mirror.state(), gl.width, gl.height, glMap);
		} finally {
			pass.end();
		}
		gl.everRendered = true;
		gl.uploadDirty = false;
		mirror.clearDirty();
	}

	/** Scene logical size = the first (lowest-id) visible-or-not canvas node's canvas. */
	private static int[] sceneLogicalSize(SceneMirror mirror) {
		for (opengpu.v2.scene.SceneNode node : mirror.state().nodes.values()) {
			if (node.type == V2Wire.NODE_CANVAS) {
				ResourceInfo res = mirror.state().resources.get(node.ref);
				if (res != null && res.type == V2Wire.RES_CANVAS) {
					return new int[] { res.width, res.height };
				}
			}
		}
		return null;
	}

	/** Full GL teardown (world unload / disconnect), render thread. */
	public void disposeAll() {
		for (SceneGl gl : scenes.values()) {
			dispose(gl);
		}
		scenes.clear();
	}

	private static void dispose(SceneGl gl) {
		if (gl.fbo != -1) {
			FramebufferPass.deleteSceneFbo(gl.fbo, gl.colorTex);
		}
		for (TexEntry entry : gl.textures.values()) {
			GL11.glDeleteTextures(entry.glId);
		}
		gl.textures.clear();
	}
}
