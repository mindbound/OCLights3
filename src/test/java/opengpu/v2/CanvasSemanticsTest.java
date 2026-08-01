package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;
import opengpu.v2.scene.SceneCanvas;

public class CanvasSemanticsTest {

	private static List<CanvasCommand> cmds(CanvasCommand... commands) {
		return new ArrayList<CanvasCommand>(Arrays.asList(commands));
	}

	@Test
	public void opaqueFillCompactsTheList() {
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 10, 20, 30, 255),
				CanvasCommand.of(V2Wire.OP_FILL_RECT, 0, 0, 50, 50),
				CanvasCommand.text(0, 0, "history"),
				CanvasCommand.of(V2Wire.OP_FILL)));
		// [SET_COLOR(current), FILL] — the accumulate-forever program stays bounded.
		assertEquals(2, canvas.visibleCommands().size());
		assertEquals(V2Wire.OP_SET_COLOR, canvas.visibleCommands().get(0).op);
		assertEquals(V2Wire.OP_FILL, canvas.visibleCommands().get(1).op);
		assertEquals(10, canvas.visibleCommands().get(0).args[0], 0);
	}

	@Test
	public void translucentFillDoesNotCompact() {
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(
				CanvasCommand.text(0, 0, "history"),
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 0, 0, 0, 16),
				CanvasCommand.of(V2Wire.OP_FILL)));
		// A translucent fill blends with prior content — everything must be kept.
		assertEquals(3, canvas.visibleCommands().size());
	}

	@Test
	public void transformPreventsCompaction() {
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_TRANSLATE, 10, 0),
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 1, 2, 3, 255),
				CanvasCommand.of(V2Wire.OP_FILL)));
		// Truncation would lose the recorded transform and change later replay — keep all.
		assertEquals(3, canvas.visibleCommands().size());
	}

	@Test
	public void fullCanvasClearRectCompactsRegardlessOfAlpha() {
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(
				CanvasCommand.text(0, 0, "history"),
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 0, 0, 0, 16),
				CanvasCommand.of(V2Wire.OP_CLEAR_RECT, 0, 0, 256, 144)));
		// CLEAR_RECT hard-sets pixels (no blending) — full coverage compacts.
		assertEquals(2, canvas.visibleCommands().size());
		assertEquals(V2Wire.OP_CLEAR_RECT, canvas.visibleCommands().get(1).op);
	}

	@Test
	public void partialClearRectDoesNotCompact() {
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(
				CanvasCommand.text(0, 0, "history"),
				CanvasCommand.of(V2Wire.OP_CLEAR_RECT, 10, 10, 50, 50)));
		assertEquals(2, canvas.visibleCommands().size());
		assertEquals(V2Wire.OP_DRAW_TEXT, canvas.visibleCommands().get(0).op);
	}

	@Test
	public void publishReplacesEverything() {
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(CanvasCommand.text(0, 0, "old")));
		canvas.publish(cmds(CanvasCommand.of(V2Wire.OP_PLOT, 1, 1)));
		assertEquals(1, canvas.visibleCommands().size());
		assertEquals(V2Wire.OP_PLOT, canvas.visibleCommands().get(0).op);
	}

	@Test
	public void appendCapIsAllOrNothing() {
		SceneCanvas canvas = new SceneCanvas(16, 16, 4);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_PLOT, 0, 0),
				CanvasCommand.of(V2Wire.OP_PLOT, 1, 1),
				CanvasCommand.of(V2Wire.OP_PLOT, 2, 2)));
		try {
			canvas.append(cmds(
					CanvasCommand.of(V2Wire.OP_PLOT, 3, 3),
					CanvasCommand.of(V2Wire.OP_PLOT, 4, 4)));
			fail("expected cap breach");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("full"));
		}
		// Nothing from the rejected append landed.
		assertEquals(3, canvas.visibleCommands().size());
	}

	@Test
	public void compactionResetsAfterTruncationSoLaterFillsCompactAgain() {
		SceneCanvas canvas = new SceneCanvas(64, 64, 4096);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 1, 1, 1, 255),
				CanvasCommand.of(V2Wire.OP_FILL),
				CanvasCommand.text(0, 0, "frame content"),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertEquals(2, canvas.visibleCommands().size());
	}

	@Test
	public void originRearmsCompaction() {
		SceneCanvas canvas = new SceneCanvas(64, 64, 4096);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_TRANSLATE, 5, 5),
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 1, 1, 1, 255),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertEquals(3, canvas.visibleCommands().size()); // transform blocks compaction
		// The legacy origin()-then-fill() clear idiom: ORIGIN resets to identity, so the
		// following fill compacts again.
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_ORIGIN),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertEquals(2, canvas.visibleCommands().size());
	}

	@Test
	public void pushStackBlocksOriginRearm() {
		SceneCanvas canvas = new SceneCanvas(64, 64, 4096);
		// A later POP would restore an unknown transform, so ORIGIN under a non-empty stack
		// must not re-arm compaction.
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_PUSH),
				CanvasCommand.of(V2Wire.OP_TRANSLATE, 5, 5),
				CanvasCommand.of(V2Wire.OP_ORIGIN),
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 1, 1, 1, 255),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertEquals(5, canvas.visibleCommands().size());
	}

	@Test
	public void publishOfVisibleListIsTheCanonicalRestorePath() {
		SceneCanvas original = new SceneCanvas(64, 64, 4096);
		original.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 9, 9, 9, 255),
				CanvasCommand.of(V2Wire.OP_FILL),
				CanvasCommand.of(V2Wire.OP_TRANSLATE, 1, 1),
				CanvasCommand.text(0, 0, "content")));

		SceneCanvas restored = new SceneCanvas(64, 64, 4096);
		restored.publish(new ArrayList<CanvasCommand>(original.visibleCommands()));
		assertTrue(original.contentEquals(restored));

		// Replay-state equivalence: identical future appends must compact identically.
		List<CanvasCommand> next = cmds(
				CanvasCommand.of(V2Wire.OP_ORIGIN),
				CanvasCommand.of(V2Wire.OP_FILL));
		original.append(next);
		restored.append(cmds(
				CanvasCommand.of(V2Wire.OP_ORIGIN),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertTrue(original.contentEquals(restored));
		assertEquals(2, original.visibleCommands().size());
	}

	@Test
	public void oversizedTextIsRejectedAtConstruction() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < V2Wire.MAX_TEXT_CHARS + 1; i++) {
			sb.append('x');
		}
		try {
			CanvasCommand.text(0, 0, sb.toString());
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("too long"));
		}
		// The boundary length itself is fine.
		CanvasCommand.text(0, 0, sb.substring(1));
	}

	@Test
	public void setColorChannelsAreNormalizedAtConstruction() {
		CanvasCommand cmd = CanvasCommand.of(V2Wire.OP_SET_COLOR, 10.7, -5, 300, 255);
		assertEquals(10, cmd.args[0], 0);
		assertEquals(0, cmd.args[1], 0);
		assertEquals(255, cmd.args[2], 0);
		assertEquals(255, cmd.args[3], 0);
	}
}
