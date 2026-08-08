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

	// ---------------------------------------------------------------- font as ambient state
	//
	// OP_SET_FONT has OP_SET_COLOR's lifecycle by definition (V2Wire), and compaction is where
	// that stops being free: truncation deletes the command that selected the font while the
	// renderer starts every canvas replay at FONT_DEFAULT. So a lost SET_FONT does not leave the
	// font unset, it REVERTS it — and only for the canvases that happened to compact, which is
	// why this needs pinning rather than reasoning about.

	@Test
	public void truncationReEmitsTheSelectedFont() {
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_UNSCII8),
				CanvasCommand.text(0, 0, "before"),
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 10, 20, 30, 255),
				CanvasCommand.of(V2Wire.OP_FILL),
				CanvasCommand.text(0, 0, "after")));

		// [SET_COLOR, SET_FONT, FILL, text] — the font must be re-established before the text
		// that follows the covering command, or "after" renders in 8x16 Unifont.
		List<CanvasCommand> v = canvas.visibleCommands();
		assertEquals(4, v.size());
		assertEquals(V2Wire.OP_SET_COLOR, v.get(0).op);
		assertEquals(V2Wire.OP_SET_FONT, v.get(1).op);
		assertEquals(V2Wire.FONT_UNSCII8, (int) v.get(1).args[0]);
		assertEquals(V2Wire.OP_FILL, v.get(2).op);
		assertEquals("after", v.get(3).text);
	}

	@Test
	public void truncationStaysTwoCommandsWhenNoFontWasSelected() {
		// The common case must not grow. Every canvas that never calls setFont keeps the exact
		// list shape other tests and the wire-size accounting already assume.
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(
				CanvasCommand.text(0, 0, "history"),
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 1, 2, 3, 255),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertEquals(2, canvas.visibleCommands().size());
	}

	@Test
	public void aFontSelectedAndThenResetDoesNotSurviveTruncation() {
		// Tracking the LATEST value, not "a font was once selected". Going back to the default
		// must return the truncated list to two commands.
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_UNSCII8),
				CanvasCommand.text(0, 0, "small"),
				CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_DEFAULT),
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 1, 2, 3, 255),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertEquals(2, canvas.visibleCommands().size());
	}

	@Test
	public void publishRebuildsFontStateSoLaterAppendsCompactCorrectly() {
		// The canonical restore path: new SceneCanvas + publish(savedList) must compact
		// identically to the canvas the list came from. Same obligation the colour and
		// pushDepth tracking already carry.
		SceneCanvas restored = new SceneCanvas(256, 144, 4096);
		restored.publish(cmds(
				CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_UNSCII8),
				CanvasCommand.text(0, 0, "restored")));

		restored.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 9, 9, 9, 255),
				CanvasCommand.of(V2Wire.OP_FILL)));

		List<CanvasCommand> v = restored.visibleCommands();
		assertEquals(3, v.size());
		assertEquals(V2Wire.OP_SET_FONT, v.get(1).op);
		assertEquals(V2Wire.FONT_UNSCII8, (int) v.get(1).args[0]);
	}

	@Test
	public void publishResetsTheFontToDefault() {
		// A published list that selects no font means Unifont, matching the renderer's reset —
		// not "whatever this canvas held before the publish".
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_UNSCII8)));
		canvas.publish(cmds(CanvasCommand.text(0, 0, "fresh")));

		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 1, 2, 3, 255),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertEquals("no font is selected any more, so no SET_FONT should be re-emitted",
				2, canvas.visibleCommands().size());
	}

	// ------------------------------------------------- compaction must respect the command cap
	//
	// truncateTo REPLACES the list rather than shrinking it, so its output has a floor — two
	// commands, or three once a font is selected. Nothing checked that floor against commandCap.
	// The append precheck bounds the INPUT (visible + incoming <= cap) and is satisfied before
	// compaction rewrites the list, so a canvas can end up holding more commands than its own cap
	// declares. That is not a cosmetic overflow: SnapshotCodec.encode writes the list without
	// checking, and decode rebuilds the canvas via publish(), which DOES check — so the scene's
	// own snapshot stops decoding, every resync fails, and ScenePersistence.restoreOrFresh
	// answers the CodecException by deleting the scene and all its texture bodies.

	@Test
	public void compactionNeverLeavesMoreCommandsThanTheCapAllows() {
		// cap 2 with a font selected: the truncated shape needs three slots, so compaction must
		// decline rather than overflow. Compaction is an optimisation; correctness outranks it.
		SceneCanvas canvas = new SceneCanvas(64, 64, 2);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_UNSCII8),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertTrue("held " + canvas.visibleCommands().size() + " commands against a cap of 2",
				canvas.visibleCommands().size() <= 2);
	}

	@Test
	public void compactionRespectsTheCapWithNoFontInvolved() {
		// The same defect one size down, and it PREDATES font tracking: [SET_COLOR, FILL] is two
		// commands against a cap of one. Pinned here so the fix covers both, not just the half
		// the font change widened.
		SceneCanvas canvas = new SceneCanvas(64, 64, 1);
		canvas.append(cmds(CanvasCommand.of(V2Wire.OP_FILL)));
		assertTrue("held " + canvas.visibleCommands().size() + " commands against a cap of 1",
				canvas.visibleCommands().size() <= 1);
	}

	@Test
	public void aCanvasAlwaysAcceptsItsOwnVisibleListBack() {
		// The consequence, stated as the invariant that actually matters: restore and resync both
		// rebuild a canvas by publishing its saved visible list, and publish enforces the cap. A
		// list its own canvas cannot re-publish is a scene that cannot be loaded or resynced.
		for (int cap = 1; cap <= 4; cap++) {
			SceneCanvas canvas = new SceneCanvas(64, 64, cap);
			try {
				canvas.append(cmds(
						CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_UNSCII8),
						CanvasCommand.of(V2Wire.OP_FILL)));
			} catch (IllegalStateException refused) {
				// A cap too small for the incoming frame refuses it whole and leaves the canvas
				// untouched. That is the designed all-or-nothing behaviour, not the defect —
				// the defect is a canvas that ACCEPTS an append and then holds more than its cap.
				continue;
			}
			new SceneCanvas(64, 64, cap).publish(canvas.visibleCommands());
		}
	}

	@Test
	public void aCapWithRoomStillCompactsAndKeepsTheFont() {
		// The other half of the pair: declining to compact when it does not fit must not become
		// declining to compact at all.
		SceneCanvas canvas = new SceneCanvas(64, 64, 3);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_UNSCII8),
				CanvasCommand.text(0, 0, "history"),
				CanvasCommand.of(V2Wire.OP_FILL)));
		List<CanvasCommand> v = canvas.visibleCommands();
		assertEquals(3, v.size());
		assertEquals(V2Wire.OP_SET_COLOR, v.get(0).op);
		assertEquals(V2Wire.OP_SET_FONT, v.get(1).op);
		assertEquals(V2Wire.OP_FILL, v.get(2).op);
	}

	@Test
	public void anOutOfRangeFontIdIsClampedRatherThanThrown() {
		// This code also runs on the MIRROR, against a list that arrived over the network. A
		// mirror that threw where the server did not would diverge instead of converge.
		SceneCanvas canvas = new SceneCanvas(256, 144, 4096);
		canvas.append(cmds(
				CanvasCommand.of(V2Wire.OP_SET_FONT, 99),
				CanvasCommand.of(V2Wire.OP_SET_COLOR, 1, 2, 3, 255),
				CanvasCommand.of(V2Wire.OP_FILL)));
		assertEquals("an invalid id tracks as the default, so nothing is re-emitted",
				2, canvas.visibleCommands().size());
	}
}
