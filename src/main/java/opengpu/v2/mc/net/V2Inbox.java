package opengpu.v2.mc.net;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Side-neutral inbound frame queues. Netty handlers enqueue here (any thread); each side's
 * runtime drains on its own main thread. This class deliberately references nothing but
 * plain JVM types so the message handlers are safe to classload on either side.
 */
public final class V2Inbox {
	private V2Inbox() {}

	public static final class ServerBound {
		public final String senderUuid;
		public final byte[] frame;

		public ServerBound(String senderUuid, byte[] frame) {
			this.senderUuid = senderUuid;
			this.frame = frame;
		}
	}

	private static final ConcurrentLinkedQueue<byte[]> toClient = new ConcurrentLinkedQueue<byte[]>();
	private static final ConcurrentLinkedQueue<ServerBound> toServer = new ConcurrentLinkedQueue<ServerBound>();

	public static void enqueueToClient(byte[] frame) {
		toClient.add(frame);
	}

	public static void enqueueToServer(String senderUuid, byte[] frame) {
		toServer.add(new ServerBound(senderUuid, frame));
	}

	public static byte[] pollToClient() {
		return toClient.poll();
	}

	public static ServerBound pollToServer() {
		return toServer.poll();
	}

	/** Disconnect/reset hygiene: drop anything queued for the client side. */
	public static void clearClientQueue() {
		toClient.clear();
	}

	/** Server stop hygiene. */
	public static void clearServerQueue() {
		toServer.clear();
	}
}
