package opengpu.v2.sync;

/**
 * Client-side outbound transport abstraction: delivers a v2 message envelope to the server.
 */
public interface ClientTransport {
	void sendToServer(byte[] envelope);
}
