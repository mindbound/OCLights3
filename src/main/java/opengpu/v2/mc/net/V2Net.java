package opengpu.v2.mc.net;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import opengpu.v2.protocol.FrameChunker;

/**
 * The v2 wire: one dedicated channel carrying FrameChunker frames in both directions.
 *
 * Message handlers run on netty IO threads — they do nothing but enqueue the frame into the
 * receiving runtime's inbound queue; all decoding, reassembly, and v2 logic happens on the
 * owning side's main thread (server tick / client tick). This keeps the entire v2 core on
 * one thread per side, matching its documented single-threaded contracts.
 *
 * Frame size: chunk payloads are capped at {@link FrameChunker#DEFAULT_CHUNK_SIZE} plus the
 * chunk header; the C->S vanilla payload ceiling (32767) comfortably clears it. Reads reject
 * oversized frames outright so a hostile peer cannot force large allocations.
 */
public final class V2Net {
	private V2Net() {}

	/** Chunk frame + chunk header slack; anything bigger is a protocol violation. */
	public static final int MAX_FRAME_BYTES = FrameChunker.DEFAULT_CHUNK_SIZE + 64;

	public static SimpleNetworkWrapper channel;

	public static void init() {
		channel = NetworkRegistry.INSTANCE.newSimpleChannel("OpenGPUv2");
		channel.registerMessage(ToClientHandler.class, FrameToClient.class, 0, Side.CLIENT);
		channel.registerMessage(ToServerHandler.class, FrameToServer.class, 1, Side.SERVER);
	}

	private static byte[] readFrame(ByteBuf buf) {
		int len = buf.readInt();
		if (len < 0 || len > MAX_FRAME_BYTES) {
			// Poison the message rather than allocate: the handler drops null frames.
			return null;
		}
		if (buf.readableBytes() < len) {
			return null;
		}
		byte[] frame = new byte[len];
		buf.readBytes(frame);
		return frame;
	}

	private static void writeFrame(ByteBuf buf, byte[] frame) {
		buf.writeInt(frame.length);
		buf.writeBytes(frame);
	}

	public static class FrameToClient implements IMessage {
		public byte[] frame;

		public FrameToClient() {}

		public FrameToClient(byte[] frame) {
			this.frame = frame;
		}

		@Override
		public void fromBytes(ByteBuf buf) {
			frame = readFrame(buf);
		}

		@Override
		public void toBytes(ByteBuf buf) {
			writeFrame(buf, frame);
		}
	}

	public static class FrameToServer implements IMessage {
		public byte[] frame;

		public FrameToServer() {}

		public FrameToServer(byte[] frame) {
			this.frame = frame;
		}

		@Override
		public void fromBytes(ByteBuf buf) {
			frame = readFrame(buf);
		}

		@Override
		public void toBytes(ByteBuf buf) {
			writeFrame(buf, frame);
		}
	}

	public static class ToClientHandler implements IMessageHandler<FrameToClient, IMessage> {
		@Override
		public IMessage onMessage(FrameToClient message, MessageContext ctx) {
			if (message.frame != null) {
				V2Inbox.enqueueToClient(message.frame);
			}
			return null;
		}
	}

	public static class ToServerHandler implements IMessageHandler<FrameToServer, IMessage> {
		@Override
		public IMessage onMessage(FrameToServer message, MessageContext ctx) {
			if (message.frame != null && ctx.getServerHandler() != null
					&& ctx.getServerHandler().playerEntity != null) {
				V2Inbox.enqueueToServer(
						ctx.getServerHandler().playerEntity.getUniqueID().toString(), message.frame);
			}
			return null;
		}
	}
}
