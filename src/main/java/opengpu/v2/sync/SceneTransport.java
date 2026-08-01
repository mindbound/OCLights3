package opengpu.v2.sync;

/**
 * Server-side outbound transport abstraction: delivers a v2 message envelope to one watcher.
 * The Minecraft layer implements this over the mod channel (chunking oversized envelopes via
 * FrameChunker); tests implement it as an in-memory loopback. Watcher keys are opaque,
 * stable per-player identifiers (player UUID strings, never display names).
 *
 * ORDERING CONTRACT (load-bearing): delivery per watcher MUST be strictly FIFO across all
 * message kinds — a chunked payload (e.g. a large snapshot) blocks subsequent envelopes for
 * that watcher until its last chunk is sent. The mirror's ordering rules assume a snapshot
 * stamped seq N arrives before any batch sealed after it; an implementation that lets small
 * batches overtake an in-flight snapshot livelocks busy scenes in a permanent resync loop.
 */
public interface SceneTransport {
	void sendToWatcher(String watcherKey, byte[] envelope);
}
