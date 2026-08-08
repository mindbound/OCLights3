package opengpu.v2.font;

/**
 * Where this package reports font-loading trouble.
 *
 * IT EXISTS BECAUSE THE PACKAGE IS MINECRAFT-FREE AND MUST STAY THAT WAY. {@link FontRegistry}'s
 * own javadoc promises exactly that — a headless server measures text through it — yet it used to
 * call {@code opengpu.OpenGPU.logger} directly. That was wrong twice over: it made the "no
 * Minecraft" claim false, and {@code logger} is a static assigned during preInit, so any load
 * reached before then would have thrown NullPointerException from inside the degraded path
 * intended to keep things running.
 *
 * The default sink writes to {@code System.err}. Not silence: a font that fails to load makes
 * every string on every screen measure crudely, and the one thing that must not happen is for
 * that to happen quietly. The Minecraft layer replaces this in preInit so the message lands in
 * the game log with everything else.
 */
public final class FontDiagnostics {

	/** Somewhere to send a message. Deliberately tiny — this is not a logging framework. */
	public interface Sink {
		void warn(String message);

		void error(String message);
	}

	/**
	 * Named rather than inline so {@link #setSink} can actually restore it. It was an anonymous
	 * instance in the field initializer, which made the "passing null restores the default"
	 * promise below not merely unimplemented but unreachable.
	 */
	private static final Sink DEFAULT = new Sink() {
		@Override
		public void warn(String message) {
			System.err.println("[OpenGPU:font] WARN " + message);
		}

		@Override
		public void error(String message) {
			System.err.println("[OpenGPU:font] ERROR " + message);
		}
	};

	private static volatile Sink sink = DEFAULT;

	private FontDiagnostics() {}

	/**
	 * Install the real logger. Called once from preInit; passing null restores the stderr
	 * default rather than disabling reporting, because there is no case where losing these
	 * messages is the right outcome.
	 *
	 * The restore is not decoration: the test suite runs in ONE JVM (no {@code forkEvery} in
	 * build.gradle.kts), so a capturing sink installed by one test class would otherwise leak
	 * into every class that ran after it.
	 */
	public static void setSink(Sink replacement) {
		sink = replacement != null ? replacement : DEFAULT;
	}

	static void warn(String message) {
		sink.warn(message);
	}

	static void error(String message) {
		sink.error(message);
	}
}
