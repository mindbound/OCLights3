package opengpu.v2.scene;

import java.util.ArrayList;
import java.util.List;

import opengpu.v2.protocol.V2Wire;

/**
 * A recorded 2D command list with the decided v2 presentation semantics:
 *
 * - append (autopresent mode): commands accumulate; a command that provably covers the whole
 *   canvas truncates the list (compaction) so long-running accumulate-style programs stay
 *   bounded in practice.
 * - publish (present mode): the given list atomically replaces the visible list.
 *
 * Compaction is deliberately conservative — it only fires when replay-from-scratch of the
 * truncated list is provably identical to replay of the full list:
 * - OP_FILL truncates only if the current color is fully opaque (a translucent fill blends
 *   with prior content) and no transform op has been recorded (transforms recorded earlier
 *   would be lost by truncation, changing later commands' meaning).
 * - OP_CLEAR_RECT (hard set, no blending) truncates regardless of alpha if its rect covers
 *   the full canvas and no transform op has been recorded.
 * After truncation the list becomes [SET_COLOR(current), coveringCommand] so the covering
 * command replays with the color it was issued under.
 *
 * Both sides of the wire run this exact logic on the same command stream, so server state
 * and mirrors stay convergent. The command-list cap applies to the visible list; exceeding
 * it throws IllegalStateException (surfaced as a Lua error by the component layer, treated
 * as a resync trigger by mirrors).
 */
public final class SceneCanvas {
	public final int width;
	public final int height;
	public final int commandCap;

	private final ArrayList<CanvasCommand> visible = new ArrayList<CanvasCommand>();
	// Replay-state tracking for compaction decisions.
	private int colorR = 255, colorG = 255, colorB = 255, colorA = 255;
	private boolean transformTouched = false;
	private int pushDepth = 0;
	/**
	 * Running encoded size of {@link #visible}, maintained at every point that list changes.
	 *
	 * Kept incrementally rather than computed on demand because the caller that needs it is an
	 * admission check on a path that runs every tick, and the list can hold thousands of
	 * commands. The commandCap bounds the COUNT, but a count says nothing about size: an
	 * OP_DRAW_TEXT slot can carry MAX_TEXT_CHARS while an OP_FILL slot is one byte.
	 */
	private long encodedBytes = 0;

	public SceneCanvas(int width, int height, int commandCap) {
		if (width <= 0 || height <= 0)
			throw new IllegalArgumentException("Canvas size must be positive");
		if (commandCap <= 0 || commandCap > V2Wire.MAX_COMMANDS - 2)
			throw new IllegalArgumentException(
					"Command cap must be in 1.." + (V2Wire.MAX_COMMANDS - 2));
		this.width = width;
		this.height = height;
		this.commandCap = commandCap;
	}

	/** Read-only view; all mutation flows through append/publish so both wire sides converge. */
	public List<CanvasCommand> visibleCommands() {
		return java.util.Collections.unmodifiableList(visible);
	}

	/**
	 * All-or-nothing: the cap is prechecked against the worst case (no compaction) before any
	 * command is applied, so a rejected append leaves the canvas untouched — identically on
	 * server and mirror. The precheck is conservative (compaction may have shrunk the list),
	 * but conservatively deterministic on both sides.
	 */
	public void append(List<CanvasCommand> commands) {
		if (visible.size() + commands.size() > commandCap)
			throw new IllegalStateException(
					"canvas command list full (" + commandCap + "); fill()/clear() or use present()");
		for (CanvasCommand cmd : commands) {
			appendOne(cmd);
		}
	}

	private void appendOne(CanvasCommand cmd) {
		if (cmd.op == V2Wire.OP_SET_COLOR) {
			colorR = clampChannel(cmd.args[0]);
			colorG = clampChannel(cmd.args[1]);
			colorB = clampChannel(cmd.args[2]);
			colorA = clampChannel(cmd.args[3]);
		} else if (V2Wire.isTransformOp(cmd.op)) {
			trackTransform(cmd.op);
		} else if (covers(cmd)) {
			truncateTo(cmd);
			return;
		}
		visible.add(cmd);
		encodedBytes += cmd.encodedBytes();
	}

	/** Encoded size of the visible list, an upper bound. See {@link #encodedBytes}. */
	public long encodedBytes() {
		return encodedBytes;
	}

	/**
	 * OP_ORIGIN resets the transform to identity, so with an empty push stack it re-arms
	 * compaction (replay-from-scratch is identity too — the legacy origin()-then-fill() clear
	 * idiom keeps compacting). With entries on the stack a later POP restores an unknown
	 * transform, so the latch must stay conservative.
	 */
	private void trackTransform(byte op) {
		if (op == V2Wire.OP_PUSH) {
			pushDepth++;
			transformTouched = true;
		} else if (op == V2Wire.OP_POP) {
			if (pushDepth > 0)
				pushDepth--;
			transformTouched = true;
		} else if (op == V2Wire.OP_ORIGIN) {
			if (pushDepth == 0)
				transformTouched = false;
		} else {
			transformTouched = true;
		}
	}

	private boolean covers(CanvasCommand cmd) {
		if (transformTouched)
			return false;
		if (cmd.op == V2Wire.OP_FILL)
			return colorA == 255;
		if (cmd.op == V2Wire.OP_CLEAR_RECT)
			return cmd.args[0] <= 0 && cmd.args[1] <= 0
					&& cmd.args[0] + cmd.args[2] >= width && cmd.args[1] + cmd.args[3] >= height;
		return false;
	}

	private void truncateTo(CanvasCommand covering) {
		visible.clear();
		encodedBytes = 0;
		visible.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, colorR, colorG, colorB, colorA));
		visible.add(covering);
		encodedBytes = visible.get(0).encodedBytes() + covering.encodedBytes();
		transformTouched = false;
		pushDepth = 0;
	}

	public void publish(List<CanvasCommand> commands) {
		if (commands.size() > commandCap)
			throw new IllegalStateException(
					"canvas command list full (" + commandCap + "); reduce the published frame");
		visible.clear();
		encodedBytes = 0;
		colorR = 255;
		colorG = 255;
		colorB = 255;
		colorA = 255;
		transformTouched = false;
		pushDepth = 0;
		// Rebuild replay-state tracking so later appends compact correctly. This scan is also
		// the canonical restore path: new SceneCanvas + publish(savedVisibleList) must yield a
		// canvas whose future appends compact identically to the original (pinned by test).
		for (CanvasCommand cmd : commands) {
			if (cmd.op == V2Wire.OP_SET_COLOR) {
				colorR = clampChannel(cmd.args[0]);
				colorG = clampChannel(cmd.args[1]);
				colorB = clampChannel(cmd.args[2]);
				colorA = clampChannel(cmd.args[3]);
			} else if (V2Wire.isTransformOp(cmd.op)) {
				trackTransform(cmd.op);
			}
			visible.add(cmd);
			encodedBytes += cmd.encodedBytes();
		}
	}

	private static int clampChannel(double v) {
		if (v < 0)
			return 0;
		if (v > 255)
			return 255;
		return (int) v;
	}

	public SceneCanvas copy() {
		SceneCanvas c = new SceneCanvas(width, height, commandCap);
		c.visible.addAll(visible);
		c.encodedBytes = encodedBytes;
		c.colorR = colorR;
		c.colorG = colorG;
		c.colorB = colorB;
		c.colorA = colorA;
		c.transformTouched = transformTouched;
		// pushDepth is replay state like the rest: dropping it here caused silent
		// post-resync compaction divergence (ORIGIN re-armed on one side only).
		c.pushDepth = pushDepth;
		return c;
	}

	public boolean contentEquals(SceneCanvas other) {
		return width == other.width && height == other.height
				&& commandCap == other.commandCap && visible.equals(other.visible);
	}
}
