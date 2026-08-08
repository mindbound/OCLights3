package opengpu.v2.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/**
 * That font-loading trouble is REPORTED, and that a healthy load stays quiet.
 *
 * Both halves matter and this project has been bitten by having only one. A diagnostic nobody
 * emits is the defect being fixed here — {@code loadResource} returned null for both "absent" and
 * "unreadable" while {@code parse}'s javadoc promised a wrong cell height would be "loud, and
 * obvious in a log". A diagnostic that fires on healthy input is worse, because it trains
 * everyone to ignore the channel.
 *
 * Needs no Minecraft: {@link FontDiagnostics} exists precisely so the font package can report
 * without reaching for the mod's logger, which was a static assigned in preInit and would have
 * thrown NullPointerException from inside the degraded path meant to keep things running.
 */
public class FontDiagnosticsTest {

	/** Records instead of printing, so a test can assert on what would have been logged. */
	private static final class Capture implements FontDiagnostics.Sink {
		final List<String> warns = new ArrayList<String>();
		final List<String> errors = new ArrayList<String>();

		@Override
		public void warn(String message) {
			warns.add(message);
		}

		@Override
		public void error(String message) {
			errors.add(message);
		}
	}

	/**
	 * The suite runs in ONE JVM — there is no {@code forkEvery} in build.gradle.kts — so a
	 * capturing sink left installed here would swallow every later class's diagnostics for the
	 * rest of the run. Restoring is what {@code setSink(null)} is for, and this is the caller
	 * that makes that contract worth having.
	 */
	@After
	public void restoreDefaultSink() {
		FontDiagnostics.setSink(null);
	}

	private Capture install() {
		Capture c = new Capture();
		FontDiagnostics.setSink(c);
		return c;
	}

	@Test
	public void anAbsentResourceIsReportedAndNamed() {
		Capture c = install();
		HexFont font = HexFont.loadResource("/assets/opengpu/font/no-such-font.hex", 16, false);

		assertNull("an absent resource still yields null, as the contract says", font);
		assertEquals("exactly one error, not none and not a storm", 1, c.errors.size());
		assertTrue("the message must name the resource, or it cannot be acted on: "
				+ c.errors.get(0), c.errors.get(0).contains("no-such-font.hex"));
		assertTrue("and no spurious warning", c.warns.isEmpty());
	}

	@Test
	public void aHealthyLoadSaysNothing() {
		Capture c = install();
		HexFont font = HexFont.loadUnscii8(false);

		assertNotNull("the bundled font must be on the test classpath", font);
		assertTrue("a good load must be silent, or the channel becomes noise: " + c.warns,
				c.warns.isEmpty());
		assertTrue("and must not error: " + c.errors, c.errors.isEmpty());
	}

	/**
	 * The case {@code parse}'s javadoc promises is loud. Parsing the bundled 8px font at height
	 * 16 rejects nearly every record — no fixture file needed, and it uses a resource already
	 * proven present by the test above.
	 */
	@Test
	public void aWrongCellHeightIsLoudRatherThanQuietlyEmpty() {
		Capture c = install();
		HexFont font = HexFont.loadResource(HexFont.UNSCII8_RESOURCE, 16, false);

		assertNotNull("a wrong height parses to a near-empty font, not to null", font);
		assertTrue("nearly everything should have been rejected, got "
				+ font.malformedLines() + " malformed / " + font.glyphCount() + " glyphs",
				font.malformedLines() > 1000);
		assertEquals("and that must produce exactly one warning", 1, c.warns.size());
		assertTrue("naming the counts, so the cause is diagnosable from the log alone: "
				+ c.warns.get(0), c.warns.get(0).contains(String.valueOf(font.malformedLines())));
		assertTrue("a wrong height is not an error — the load did not fail, it produced a"
				+ " useless font, and the distinction is what tells you which to look for",
				c.errors.isEmpty());
	}

	@Test
	public void settingANullSinkRestoresTheDefaultRatherThanSilencing() {
		// The behaviour the javadoc claims. It previously claimed it while IGNORING null, which
		// left the last installed sink in place — the exact "documentation promising what the
		// code does not do" this whole change set exists to remove.
		Capture c = install();
		FontDiagnostics.setSink(null);
		HexFont.loadResource("/assets/opengpu/font/still-not-there.hex", 16, false);
		assertTrue("after restoring the default, the old sink must stop receiving", c.errors.isEmpty());
	}
}
