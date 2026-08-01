package opengpu.v2.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Length-prefixed chunked framing for payloads larger than one network packet — the designed
 * replacement for the legacy PacketChunker (which keyed reassembly on a global 1-byte id
 * shared across all senders; see ISSUES P-03). Chunk frame layout:
 *
 *   [int transferId][int chunkIndex][int chunkCount][int payloadLen][payload]
 *
 * Reassembly state is per sender (the {@link Reassembler} caller supplies the sender key),
 * with hard caps on concurrent transfers, chunk counts, and total size; any inconsistency
 * (mismatched counts, duplicate or out-of-range index, over-cap size) drops the whole
 * transfer rather than guessing. Callers must evict a sender's state on disconnect.
 */
public final class FrameChunker {
	private FrameChunker() {}

	public static final int DEFAULT_CHUNK_SIZE = 30000;
	public static final long MAX_TRANSFER_BYTES = MessageCodec.MAX_RESOURCE_BODY + (1 << 16);
	/**
	 * Must satisfy MAX_CHUNK_COUNT * DEFAULT_CHUNK_SIZE >= MAX_TRANSFER_BYTES so every
	 * wire-legal payload is deliverable ("legal to create" implies "deliverable" — pinned by
	 * test). 9000 * 30000 = 270 MB >= ~256 MB.
	 */
	public static final int MAX_CHUNK_COUNT = 9000;
	public static final int MAX_TRANSFERS_PER_SENDER = 4;

	public static List<byte[]> split(int transferId, byte[] data, int chunkSize) {
		if (chunkSize <= 0)
			throw new IllegalArgumentException("Chunk size must be positive");
		int count = Math.max(1, (data.length + chunkSize - 1) / chunkSize);
		if (count > MAX_CHUNK_COUNT)
			throw new IllegalArgumentException("Payload needs " + count + " chunks (cap " + MAX_CHUNK_COUNT + ")");
		ArrayList<byte[]> frames = new ArrayList<byte[]>(count);
		for (int i = 0; i < count; i++) {
			int offset = i * chunkSize;
			int len = Math.min(chunkSize, data.length - offset);
			try {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				DataOutputStream out = new DataOutputStream(bytes);
				out.writeInt(transferId);
				out.writeInt(i);
				out.writeInt(count);
				out.writeInt(len);
				out.write(data, offset, len);
				frames.add(bytes.toByteArray());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return frames;
	}

	/**
	 * Per-sender reassembly. Not thread-safe; callers hold their own lock.
	 *
	 * Caps are constructor-configurable and DIRECTIONAL by design: the client-side instance
	 * (reassembling server payloads) uses the large defaults; the server-side instance
	 * (reassembling client requests, all of which fit one packet today) must be constructed
	 * tiny so hostile clients cannot park large buffers. The aggregate byte budget applies
	 * across ALL of a sender's incomplete transfers; hitting the slot cap evicts the OLDEST
	 * incomplete transfer (newest-wins — matches the retry loops, which always mint a fresh
	 * transferId) instead of wedging the sender.
	 */
	public static final class Reassembler {
		private static final class Transfer {
			byte[][] chunks;
			int received;
			long totalBytes;
		}

		private static final class SenderState {
			final LinkedHashMap<Integer, Transfer> transfers = new LinkedHashMap<Integer, Transfer>();
			long totalBytes;
		}

		private final int maxTransfersPerSender;
		private final long maxBytesPerSender;
		private final Map<String, SenderState> senders = new HashMap<String, SenderState>();

		public Reassembler() {
			this(MAX_TRANSFERS_PER_SENDER, MAX_TRANSFER_BYTES);
		}

		public Reassembler(int maxTransfersPerSender, long maxBytesPerSender) {
			this.maxTransfersPerSender = maxTransfersPerSender;
			this.maxBytesPerSender = maxBytesPerSender;
		}

		/**
		 * Accepts one chunk frame; returns the reassembled payload when complete, else null.
		 * @throws CodecException on malformed frames or cap breaches (the transfer is dropped).
		 */
		public byte[] accept(String senderKey, byte[] frame) throws CodecException {
			int transferId;
			int index;
			int count;
			byte[] payload;
			try {
				DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
				transferId = in.readInt();
				index = in.readInt();
				count = in.readInt();
				int len = in.readInt();
				if (count <= 0 || count > MAX_CHUNK_COUNT)
					throw new CodecException("Chunk count out of range: " + count);
				if (index < 0 || index >= count)
					throw new CodecException("Chunk index out of range: " + index);
				if (len < 0 || len > frame.length)
					throw new CodecException("Chunk length out of range: " + len);
				payload = new byte[len];
				in.readFully(payload);
				if (in.read() != -1)
					throw new CodecException("Trailing data after chunk");
			} catch (IOException e) {
				throw new CodecException("Malformed chunk frame", e);
			}

			SenderState sender = senders.get(senderKey);
			if (sender == null) {
				sender = new SenderState();
				senders.put(senderKey, sender);
			}
			Transfer transfer = sender.transfers.get(transferId);
			if (transfer == null) {
				while (sender.transfers.size() >= maxTransfersPerSender) {
					dropTransfer(sender, sender.transfers.keySet().iterator().next());
				}
				transfer = new Transfer();
				transfer.chunks = new byte[count][];
				sender.transfers.put(transferId, transfer);
			}
			if (transfer.chunks.length != count) {
				dropTransfer(sender, transferId);
				throw new CodecException("Chunk count mismatch mid-transfer");
			}
			if (transfer.chunks[index] != null) {
				dropTransfer(sender, transferId);
				throw new CodecException("Duplicate chunk " + index);
			}
			transfer.chunks[index] = payload;
			transfer.received++;
			transfer.totalBytes += payload.length;
			sender.totalBytes += payload.length;
			if (sender.totalBytes > maxBytesPerSender) {
				dropTransfer(sender, transferId);
				throw new CodecException("Sender exceeds aggregate reassembly budget");
			}
			if (transfer.received < transfer.chunks.length)
				return null;
			dropTransfer(sender, transferId);
			if (sender.transfers.isEmpty())
				senders.remove(senderKey);
			ByteArrayOutputStream assembled = new ByteArrayOutputStream((int) transfer.totalBytes);
			for (byte[] chunk : transfer.chunks) {
				assembled.write(chunk, 0, chunk.length);
			}
			return assembled.toByteArray();
		}

		private static void dropTransfer(SenderState sender, int transferId) {
			Transfer dropped = sender.transfers.remove(transferId);
			if (dropped != null) {
				sender.totalBytes -= dropped.totalBytes;
			}
		}

		/** Drops all reassembly state for a sender (call on disconnect). */
		public void evict(String senderKey) {
			senders.remove(senderKey);
		}

		/** Drops every sender's state (server stop / world unload on a long-lived instance). */
		public void clear() {
			senders.clear();
		}
	}
}
