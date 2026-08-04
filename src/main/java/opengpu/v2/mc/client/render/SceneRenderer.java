package opengpu.v2.mc.client.render;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import opengpu.OpenGPU;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.stats.RenderStats;
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
		// Version, NOT array identity: writeRegion mutates the array in place, so an
		// identity check would report "already uploaded" forever while the pixels change.
		int uploadedEpoch;
		int uploadedVersion;
		/** Dimensions of the allocated GL texture; a sub-upload is only valid against these. */
		int glWidth;
		int glHeight;
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
		/**
		 * Logical size whose FBO creation already failed. One attempt per distinct size:
		 * retrying costs a real glTexImage2D plus teardown on EVERY pre-pass frame and will
		 * keep failing for the same reason. A later resize clears this and retries naturally.
		 */
		int failedWidth = -1;
		int failedHeight = -1;
		/** Smooths node transforms across the 20 tps channel; see NodeInterpolator. */
		final NodeInterpolator interp = new NodeInterpolator();
		/** Last mirror epoch this cache saw; a change means a new timeline, so snap. */
		int knownEpoch;
		final Map<Integer, TexEntry> textures = new HashMap<Integer, TexEntry>();
	}

	private final Map<String, SceneGl> scenes = new HashMap<String, SceneGl>();
	private final FramebufferPass pass = new FramebufferPass();
	private final Canvas2dRenderer canvasRenderer = new Canvas2dRenderer();
	/**
	 * One-shot for "this machine has no FBO support at all", which is a session-wide fact.
	 * Per-scene allocation failures are NOT logged through this — they used to be, so the
	 * second failure anywhere in a session was silent forever.
	 */
	private boolean fboUnsupportedLogged;
	private boolean fboUnsupportedTold;

	/**
	 * Which half of {@code framebufferSupported && gameSettings.fboEnable} actually failed.
	 *
	 * Worth distinguishing every time it is reported: one is a video option two clicks away,
	 * the other is a machine that cannot run this mod. Telling a player "unsupported" when
	 * they merely switched FBOs off is the misdiagnosis this whole path exists to avoid.
	 */
	public static String fboDiagnosis() {
		if (!OpenGlHelper.framebufferSupported) {
			return "This GPU/driver reports no framebuffer-object support.";
		}
		return "Framebuffer objects are switched OFF in Video Settings.";
	}

	/** The player will never read a log line; say it once where they are actually looking. */
	private void notifyPlayerOnce() {
		if (fboUnsupportedTold) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.thePlayer == null) {
			return; // not in a world yet — try again next frame, still only once
		}
		fboUnsupportedTold = true;
		mc.thePlayer.addChatMessage(new ChatComponentText(
				EnumChatFormatting.RED + "[OpenGPU] " + EnumChatFormatting.RESET
						+ fboDiagnosis() + " Screens will stay blank until "
						+ (OpenGlHelper.framebufferSupported
								? "you re-enable them (Options → Video Settings)." : "run on hardware that supports them.")));
	}

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
			if (!fboUnsupportedLogged) {
				fboUnsupportedLogged = true;
				// No fallback renderer is coming, and the old message promising one sent
				// anyone who read it looking for a bug instead of a setting. OpenGPU requires
				// framebuffer objects, full stop -- but note WHICH half of the gate failed:
				// OpenGlHelper.isFramebufferEnabled() is `framebufferSupported &&
				// gameSettings.fboEnable`, so the overwhelmingly likely cause is the video
				// option, which the player can simply turn back on.
				OpenGPU.logger.warn(fboDiagnosis()
						+ " OpenGPU screens require framebuffer objects and will stay blank.");
			}
			notifyPlayerOnce();
			return;
		}
		pruneDeadScenes(mirrors);
		long budget = UPLOAD_BUDGET_PER_FRAME;
		long rendersBefore = RenderStats.sceneRenders;
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
		// "Did this frame have to draw anything" is the interpolation overhead stated directly,
		// and it is measured HERE rather than inside renderIfNeeded because several scenes may
		// be visible and the question is about the frame, not about any one of them.
		RenderStats.onPrePass(RenderStats.sceneRenders > rendersBefore);
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
		// Part of the upload key: an epoch change reuses resource ids for different content,
		// so a matching version alone would wrongly suppress the re-upload.
		final int mirrorEpoch = mirror.knownEpoch();
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
			if (entry != null && entry.uploadedEpoch == mirrorEpoch
					&& entry.uploadedVersion == res.version) {
				continue;
			}
			// A sub-upload is legal whenever the GL texture holds SOME EARLIER version of
			// this resource at matching dimensions and epoch.
			//
			// The bound is "earlier", not "exactly one behind": the dirty rect accumulates
			// every write since the last upload (clearDirty runs only when we upload, and
			// unionDirtyRect on every applied blit), so it describes the delta from
			// uploadedVersion however many versions have passed. Requiring version - 1 would
			// disable the whole optimisation for any tick containing more than one write —
			// i.e. exactly the streaming workload it exists for — and for any texture that
			// was skipped for budget. A body install and a snapshot carry-over both call
			// markFullDirty, so those arrive as a full-rect sub-upload rather than a stale
			// partial one.
			boolean canSubUpload = entry != null
					&& entry.uploadedEpoch == mirrorEpoch
					&& entry.uploadedVersion > 0
					&& entry.uploadedVersion < res.version
					&& entry.glWidth == res.width && entry.glHeight == res.height
					&& res.dirtyW > 0
					&& res.dirtyX >= 0 && res.dirtyY >= 0
					&& res.dirtyX + res.dirtyW <= res.width
					&& res.dirtyY + res.dirtyH <= res.height;
			long size = canSubUpload
					? (long) res.dirtyW * res.dirtyH * 4L : res.bytes.length;
			// Always admit the head of the queue: a texture bigger than one frame's budget
			// would otherwise be skipped forever (the budget resets to the same value every
			// frame), leaving a legally-created texture permanently invisible. Admitting it
			// against an untouched budget costs one hitchy frame instead.
			if (size > budget && budget < UPLOAD_BUDGET_PER_FRAME) {
				// Counted because a budget that is regularly exhausted means textures are
				// arriving faster than they can be uploaded, which shows up to a player as a
				// picture that lags behind the program rather than as anything measurable.
				RenderStats.onTextureDeferred();
				continue; // over budget this frame; a later pre-pass picks it up
			}
			budget -= size;
			RenderStats.onUpload((int) Math.min(size, Integer.MAX_VALUE));
			if (canSubUpload) {
				uploadSubRgba(entry.glId, res.width, res.bytes,
						res.dirtyX, res.dirtyY, res.dirtyW, res.dirtyH);
				entry.uploadedVersion = res.version;
				res.clearDirty();
				gl.uploadDirty = true;
				continue;
			}
			if (entry == null) {
				entry = new TexEntry();
				entry.glId = GL11.glGenTextures();
				gl.textures.put(res.id, entry);
			}
			uploadRgba(entry.glId, res.width, res.height, res.bytes);
			entry.uploadedEpoch = mirrorEpoch;
			entry.uploadedVersion = res.version;
			entry.glWidth = res.width;
			entry.glHeight = res.height;
			res.clearDirty();
			gl.uploadDirty = true;
		}
		return budget;
	}

	/** Full (re)allocation of the GL texture: first upload, or after a resize. */
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

	/**
	 * Upload only the rectangle that changed.
	 *
	 * A streaming program rewrites a small region per tick; re-uploading the whole image
	 * makes the cost proportional to the texture size rather than the edit size — a 4-byte
	 * write to a 1 MB texture moving a megabyte per frame. The rows are contiguous in the
	 * source only when the rect spans the full width, so a partial-width rect is packed row
	 * by row into a staging buffer.
	 */
	private static void uploadSubRgba(int glId, int texWidth, byte[] rgba,
			int x, int y, int w, int h) {
		ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
		if (w == texWidth) {
			// Full-width rect: the rows are already contiguous, so one bulk copy suffices.
			buffer.put(rgba, y * texWidth * 4, w * h * 4);
		} else {
			for (int row = 0; row < h; row++) {
				buffer.put(rgba, ((y + row) * texWidth + x) * 4, w * 4);
			}
		}
		buffer.flip();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
	}

	private void renderIfNeeded(SceneGl gl, SceneMirror mirror) {
		int[] size = sceneLogicalSize(mirror);
		if (size == null) {
			return; // no canvas node yet — nothing to render
		}
		boolean resized = gl.fbo != -1 && (gl.width != size[0] || gl.height != size[1]);
		if (gl.fbo == -1 || resized) {
			if (size[0] == gl.failedWidth && size[1] == gl.failedHeight) {
				return; // already tried this size; keep showing whatever is on screen
			}
			// Create BEFORE deleting. The old FBO is a WORKING display: tearing it down first
			// meant a failed resize turned a visible screen black, and then re-attempted the
			// same doomed allocation every single frame. sizeFor() reports the FBO's real
			// dimensions, so keeping the old one also keeps the surface letterbox coherent
			// with what is actually in the texture.
			int[] created = FramebufferPass.createSceneFbo(size[0], size[1]);
			if (created == null) {
				gl.failedWidth = size[0];
				gl.failedHeight = size[1];
				int max = FramebufferPass.maxSceneDimension();
				OpenGPU.logger.warn("Scene FBO creation failed (" + size[0] + "x" + size[1]
						+ "; this context allows up to " + max + "x" + max
						+ "); keeping the previous surface");
				return;
			}
			if (gl.fbo != -1) {
				FramebufferPass.deleteSceneFbo(gl.fbo, gl.colorTex);
			}
			gl.fbo = created[0];
			gl.colorTex = created[1];
			gl.width = size[0];
			gl.height = size[1];
			gl.everRendered = false;
			gl.failedWidth = -1;
			gl.failedHeight = -1;
		}
		long now = System.nanoTime();
		// Fold a freshly applied batch into the interpolator BEFORE deciding whether to draw:
		// capture() is what starts a node moving, so skipping it would drop the motion
		// entirely on the frame the batch lands.
		if (gl.knownEpoch != mirror.knownEpoch()) {
			// A new incarnation is a different timeline: tick stamps from the old one describe
			// nothing in the new one, so the clock estimate and every keyframe must be dropped
			// rather than interpolated across. DESIGN: "Resync always snaps."
			gl.knownEpoch = mirror.knownEpoch();
			gl.interp.reset();
		}
		if (mirror.isDirty()) {
			gl.interp.capture(mirror.state(), mirror.lastServerTick(), now, mirror.teleportedNodes());
		}
		// Re-render while anything is still mid-flight, not only when a batch arrived — that
		// is the whole point, since batches land at 20 Hz and we draw at 60+. A settled scene
		// still short-circuits here, so a static display costs nothing per frame.
		boolean interpolating = gl.interp.active(now);
		if (!mirror.isDirty() && !gl.uploadDirty && gl.everRendered && !interpolating) {
			return;
		}
		Map<Integer, Integer> glMap = new HashMap<Integer, Integer>();
		for (Map.Entry<Integer, TexEntry> entry : gl.textures.entrySet()) {
			glMap.put(entry.getKey(), entry.getValue().glId);
		}
		// The render is attributed to interpolation when nothing else asked for it. That is the
		// number the whole measurement exists for: a re-render caused by fresh server state is
		// work that always had to happen, while one caused purely by a node still gliding is
		// the cost interpolation added — and at 60-200 Hz against a former ceiling of 20.
		boolean interpolationDriven = interpolating && !mirror.isDirty() && !gl.uploadDirty
				&& gl.everRendered;
		int commandCount = countCommands(mirror);
		long renderStart = System.nanoTime();
		pass.begin(gl.fbo, gl.width, gl.height);
		try {
			canvasRenderer.renderScene(mirror.state(), gl.width, gl.height, glMap, gl.interp, now);
		} finally {
			pass.end();
		}
		RenderStats.onSceneRender(System.nanoTime() - renderStart, commandCount, interpolationDriven);
		gl.everRendered = true;
		gl.uploadDirty = false;
		mirror.clearDirty();
	}

	/**
	 * Commands about to be replayed, summed across every canvas in the scene.
	 *
	 * Counted rather than timed per command: the unit of work here is a glBegin/glEnd pair, so
	 * a nanoTime around each one would cost more than the thing it measures. Dividing the whole
	 * render by this count gives the per-command figure without perturbing it. The walk is over
	 * canvases, not commands, so it stays cheap.
	 */
	private static int countCommands(SceneMirror mirror) {
		int total = 0;
		for (ResourceInfo res : mirror.state().resources.values()) {
			if (res.type == V2Wire.RES_CANVAS && res.canvas != null) {
				total += res.canvas.visibleCommands().size();
			}
		}
		return total;
	}

	/**
	 * Scene logical size, from the one place that defines which canvas is the display.
	 * The rule and its fragility live on {@link opengpu.v2.scene.SceneState#displayCanvas()}
	 * rather than here, so the server side and this pre-pass cannot drift on it.
	 */
	private static int[] sceneLogicalSize(SceneMirror mirror) {
		ResourceInfo res = mirror.state().displayCanvas();
		return res == null ? null : new int[] { res.width, res.height };
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
