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
 * After truncation the list becomes [SET_COLOR(current), SET_FONT(current) if non-default,
 * coveringCommand] — the covering command replays with the color it was issued under, and any
 * ambient state that outlives it is re-established for the commands appended afterwards.
 *
 * THE RULE FOR ADDING AMBIENT STATE: any op whose effect persists past the command that
 * follows it must be tracked here and re-emitted by truncateTo. Truncation deletes the command
 * that set it while the renderer starts each replay from defaults, so an untracked one is not
 * just lost — it silently reverts, and only for canvases that happened to compact.
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
	 * The selected font, tracked for exactly the reason the colour is: truncation throws away
	 * the commands that established it, so the truncated list has to re-establish it or replay
	 * stops being identical.
	 *
	 * It is easy to think a font does not need this — a FILL draws no text. But the list is
	 * replayed FROM SCRATCH against a renderer that resets to FONT_DEFAULT at the start of every
	 * canvas, so a SET_FONT deleted by truncation is not merely absent, it is undone: text
	 * appended AFTER the covering command silently renders in the wrong font, at the wrong cell
	 * height. That is why the default value here is FONT_DEFAULT and not "unknown".
	 */
	private int currentFont = V2Wire.FONT_DEFAULT;
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
		} else if (cmd.op == V2Wire.OP_SET_FONT) {
			currentFont = clampFont(cmd.args[0]);
		} else if (V2Wire.isTransformOp(cmd.op)) {
			trackTransform(cmd.op);
		} else if (covers(cmd) && truncationFits()) {
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

	/**
	 * Whether the truncated list would fit inside {@link #commandCap}.
	 *
	 * Compaction REPLACES the list rather than shrinking it, so its output has a floor: two
	 * commands, or three once a font is selected. Nothing bounded that floor against the cap. The
	 * append precheck bounds the INPUT — visible + incoming — and is satisfied before compaction
	 * rewrites anything, so a small-cap canvas could finish an append holding more commands than
	 * its own cap declares.
	 *
	 * That is not a cosmetic overflow. {@code SnapshotCodec.encode} writes the visible list
	 * without checking the cap, and decode rebuilds the canvas through {@code publish}, which
	 * DOES check — so the scene's own snapshot stops decoding, every client resync fails, and
	 * {@code ScenePersistence.restoreOrFresh} answers the CodecException by deleting the scene
	 * and every texture body it owns.
	 *
	 * Declining to compact is always safe: compaction is a bound on GROWTH, never a correctness
	 * requirement, and the append precheck has already guaranteed the un-compacted list fits.
	 * The decision converges across the wire because it reads only commandCap — which rides both
	 * the snapshot and the persisted record — and currentFont, which is tracked identically on
	 * both sides.
	 *
	 * The cap-1 case here predates font tracking: [SET_COLOR, FILL] is two commands against a cap
	 * of one. The font re-emission widened the broken set rather than creating it, which is why
	 * this guard covers the shape rather than just the extra command.
	 */
	private boolean truncationFits() {
		return 2 + (currentFont != V2Wire.FONT_DEFAULT ? 1 : 0) <= commandCap;
	}

	private void truncateTo(CanvasCommand covering) {
		visible.clear();
		encodedBytes = 0;
		visible.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, colorR, colorG, colorB, colorA));
		// Only when it is not the default, so the ordinary two-command shape is preserved for
		// every canvas that never selects a font — which is all of them until one does.
		if (currentFont != V2Wire.FONT_DEFAULT) {
			visible.add(CanvasCommand.of(V2Wire.OP_SET_FONT, currentFont));
		}
		visible.add(covering);
		encodedBytes = 0;
		for (CanvasCommand cmd : visible) {
			encodedBytes += cmd.encodedBytes();
		}
		transformTouched = false;
		pushDepth = 0;
		// currentFont deliberately NOT reset: it is ambient state that survives the covering
		// command, and the re-emitted SET_FONT above is what keeps replay in step with it.
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
		// FONT_DEFAULT, matching the renderer's per-canvas reset: a published list that selects
		// no font must mean Unifont on both sides, not "whatever this canvas held before".
		currentFont = V2Wire.FONT_DEFAULT;
		// Rebuild replay-state tracking so later appends compact correctly. This scan is also
		// the canonical restore path: new SceneCanvas + publish(savedVisibleList) must yield a
		// canvas whose future appends compact identically to the original (pinned by test).
		for (CanvasCommand cmd : commands) {
			if (cmd.op == V2Wire.OP_SET_COLOR) {
				colorR = clampChannel(cmd.args[0]);
				colorG = clampChannel(cmd.args[1]);
				colorB = clampChannel(cmd.args[2]);
				colorA = clampChannel(cmd.args[3]);
			} else if (cmd.op == V2Wire.OP_SET_FONT) {
				currentFont = clampFont(cmd.args[0]);
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

	/**
	 * Clamped, not rejected, and identically on both sides of the wire.
	 *
	 * Both doors that can record a SET_FONT validate the id first (the callback and
	 * canvasSubmit), so an out-of-range value should be unreachable here. But this same code
	 * runs on the MIRROR, against a command list that arrived over the network, and a mirror
	 * that threw where the server did not would diverge instead of converging — the one failure
	 * this class exists to prevent. Clamping matches what the renderer does with the same value.
	 */
	private static int clampFont(double v) {
		int id = (int) v;
		return V2Wire.isValidFont(id) ? id : V2Wire.FONT_DEFAULT;
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
		// currentFont is the same kind of state, and omitting it would have the same shape of
		// consequence: the copy's next truncation would re-emit the wrong font, or none.
		c.currentFont = currentFont;
		return c;
	}

	/**
	 * Compares the VISIBLE LIST, not the replay state behind it.
	 *
	 * Deliberately narrow, and worth knowing what that costs: two canvases can be contentEqual
	 * while holding different colour, transform or font tracking, and then diverge on their next
	 * compaction. Callers use this as a convergence oracle, so it hides exactly the class of bug
	 * that {@link #copy} carries comments about. Widening it is recorded in ROADMAP under
	 * Defects; it is left alone here because doing it properly means auditing every caller that
	 * currently passes, which is not this change.
	 */
	public boolean contentEquals(SceneCanvas other) {
		return width == other.width && height == other.height
				&& commandCap == other.commandCap && visible.equals(other.visible);
	}
}
