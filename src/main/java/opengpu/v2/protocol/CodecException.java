package opengpu.v2.protocol;

/**
 * Thrown when a v2 wire payload cannot be decoded: unsupported protocol version, unknown
 * delta/op id, truncated data, or a count that fails sanity caps. Decoders throw this for
 * every malformed input — never OOM, never a hang, never a partial silent result.
 */
public class CodecException extends Exception {
	public CodecException(String message) {
		super(message);
	}

	public CodecException(String message, Throwable cause) {
		super(message, cause);
	}
}
