package ds.mods.OCLights2.v2.scene;

import java.util.Arrays;

import ds.mods.OCLights2.v2.protocol.V2Wire;

/**
 * One recorded 2D canvas command: an op id from {@link V2Wire}, its numeric arguments, and
 * (for OP_DRAW_TEXT only) a string. Immutable once constructed; commands are shared freely
 * between server state, staged deltas, and mirrors.
 */
public final class CanvasCommand {
	public final byte op;
	public final double[] args;
	public final String text;

	public CanvasCommand(byte op, double[] args, String text) {
		int expected = V2Wire.canvasOpArgCount(op);
		if (expected < 0)
			throw new IllegalArgumentException("Unknown canvas op " + op);
		if (args == null)
			args = new double[0];
		if (args.length != expected)
			throw new IllegalArgumentException("Canvas op " + op + " expects " + expected + " args, got " + args.length);
		if ((op == V2Wire.OP_DRAW_TEXT) != (text != null))
			throw new IllegalArgumentException("Canvas op " + op + " text argument mismatch");
		if (text != null && text.length() > V2Wire.MAX_TEXT_CHARS)
			throw new IllegalArgumentException(
					"drawText string too long (" + text.length() + " > " + V2Wire.MAX_TEXT_CHARS + " chars)");
		if (op == V2Wire.OP_SET_COLOR) {
			// Color channels are integral 0-255 on the wire: normalizing here keeps recorded
			// commands identical to the compaction-emitted SET_COLOR built from tracked state,
			// so replay identity never depends on how a renderer rounds fractional channels.
			double[] normalized = new double[args.length];
			for (int i = 0; i < args.length; i++) {
				normalized[i] = clampChannel(args[i]);
			}
			args = normalized;
		}
		this.op = op;
		this.args = args;
		this.text = text;
	}

	private static int clampChannel(double v) {
		if (Double.isNaN(v) || v < 0)
			return 0;
		if (v > 255)
			return 255;
		return (int) v;
	}

	public static CanvasCommand of(byte op, double... args) {
		return new CanvasCommand(op, args, null);
	}

	public static CanvasCommand text(double x, double y, String str) {
		return new CanvasCommand(V2Wire.OP_DRAW_TEXT, new double[] { x, y }, str);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof CanvasCommand))
			return false;
		CanvasCommand c = (CanvasCommand) o;
		return op == c.op && Arrays.equals(args, c.args)
				&& (text == null ? c.text == null : text.equals(c.text));
	}

	@Override
	public int hashCode() {
		int h = op;
		h = 31 * h + Arrays.hashCode(args);
		h = 31 * h + (text == null ? 0 : text.hashCode());
		return h;
	}

	@Override
	public String toString() {
		return "CanvasCommand(op=" + op + ", args=" + Arrays.toString(args)
				+ (text != null ? ", text=" + text : "") + ")";
	}
}
