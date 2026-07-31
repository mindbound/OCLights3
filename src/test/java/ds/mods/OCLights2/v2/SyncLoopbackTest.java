package ds.mods.OCLights2.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ds.mods.OCLights2.v2.protocol.MessageCodec;
import ds.mods.OCLights2.v2.protocol.V2Wire;
import ds.mods.OCLights2.v2.scene.CanvasCommand;
import ds.mods.OCLights2.v2.scene.SceneMirror;
import ds.mods.OCLights2.v2.scene.ServerScene;
import ds.mods.OCLights2.v2.sync.ClientTransport;
import ds.mods.OCLights2.v2.sync.MirrorClient;
import ds.mods.OCLights2.v2.sync.SceneHost;
import ds.mods.OCLights2.v2.sync.SceneTransport;

/**
 * End-to-end loopback: SceneHost and MirrorClient wired through in-memory queues with
 * deterministic packet loss. Automates the recovery half of the design's two-client matrix:
 * late join, lost batch -> gap -> resync, lost snapshot -> retry, body fetch and caching,
 * flood bounding, scene destruction, zombie decay.
 */
public class SyncLoopbackTest {

	private static final String SCENE = "scene-loop";
	private static final int HEARTBEAT_INTERVAL = 4;
	private static final int SNAPSHOT_MIN_INTERVAL = 3;
	private static final int RESYNC_RETRY = 3;
	private static final int BODIES_PER_TICK = 4;

	private static final class Harness {
		final Map<String, ArrayDeque<byte[]>> toClient = new HashMap<String, ArrayDeque<byte[]>>();
		final Map<String, ArrayDeque<byte[]>> toServer = new HashMap<String, ArrayDeque<byte[]>>();
		final ServerScene scene = new ServerScene(SCENE);
		final SceneHost host;
		final Map<String, MirrorClient> clients = new HashMap<String, MirrorClient>();
		int serverDropCountdown = -1; // when 0: drop the next matching server->client message
		String dropOnlyFor = null;    // null = any watcher
		final int[] requestCounter = { 0 }; // wire MSG_RESOURCE_REQUEST count

		Harness() {
			host = new SceneHost(scene, new SceneTransport() {
				@Override
				public void sendToWatcher(String watcherKey, byte[] envelope) {
					if (serverDropCountdown == 0 && (dropOnlyFor == null || dropOnlyFor.equals(watcherKey))) {
						serverDropCountdown = -1;
						return; // dropped
					}
					if (serverDropCountdown > 0 && (dropOnlyFor == null || dropOnlyFor.equals(watcherKey))) {
						serverDropCountdown--;
					}
					queueFor(toClient, watcherKey).addLast(envelope);
				}
			}, HEARTBEAT_INTERVAL, SNAPSHOT_MIN_INTERVAL, BODIES_PER_TICK);
		}

		static ArrayDeque<byte[]> queueFor(Map<String, ArrayDeque<byte[]>> map, String key) {
			ArrayDeque<byte[]> q = map.get(key);
			if (q == null) {
				q = new ArrayDeque<byte[]>();
				map.put(key, q);
			}
			return q;
		}

		MirrorClient client(final String watcher) {
			MirrorClient client = clients.get(watcher);
			if (client == null) {
				client = new MirrorClient(new ClientTransport() {
					@Override
					public void sendToServer(byte[] envelope) {
						queueFor(toServer, watcher).addLast(envelope);
					}
				}, RESYNC_RETRY);
				clients.put(watcher, client);
			}
			return client;
		}

		/** One full tick: server tick, deliver to clients, client ticks, deliver to server. */
		void pump(long tick) throws Exception {
			host.tick(tick);
			for (Map.Entry<String, ArrayDeque<byte[]>> e : toClient.entrySet()) {
				MirrorClient client = client(e.getKey());
				byte[] envelope;
				while ((envelope = e.getValue().pollFirst()) != null) {
					client.onMessage(envelope);
				}
			}
			for (Map.Entry<String, MirrorClient> e : clients.entrySet()) {
				e.getValue().tick(tick);
			}
			for (Map.Entry<String, ArrayDeque<byte[]>> e : toServer.entrySet()) {
				byte[] envelope;
				while ((envelope = e.getValue().pollFirst()) != null) {
					dispatchToServer(e.getKey(), envelope);
				}
			}
		}

		void dispatchToServer(String watcher, byte[] envelope) throws Exception {
			byte kind = MessageCodec.kindOf(envelope);
			byte[] payload = MessageCodec.payloadOf(envelope);
			if (kind == MessageCodec.MSG_RESYNC_REQUEST) {
				MessageCodec.ResyncRequest req = MessageCodec.decodeResyncRequest(payload);
				if (SCENE.equals(req.sceneId)) {
					host.onResyncRequest(watcher);
				}
			} else if (kind == MessageCodec.MSG_RESOURCE_REQUEST) {
				MessageCodec.ResourceRequest req = MessageCodec.decodeResourceRequest(payload);
				requestCounter[0]++;
				if (SCENE.equals(req.sceneId)) {
					host.onResourceRequest(watcher, req.resId);
				}
			}
		}

		void draw(int canvas, int value) {
			List<CanvasCommand> cmds = new ArrayList<CanvasCommand>();
			cmds.add(CanvasCommand.of(V2Wire.OP_PLOT, value, value));
			scene.canvasAppend(canvas, cmds);
		}

		int countQueued(String watcher, byte kind) throws Exception {
			int count = 0;
			for (byte[] envelope : queueFor(toClient, watcher)) {
				if (MessageCodec.kindOf(envelope) == kind) {
					count++;
				}
			}
			return count;
		}
	}

	@Test
	public void liveSubscriberTracksEveryTick() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		int canvas = h.scene.createCanvas(64, 64, 4096);
		for (long t = 1; t <= 10; t++) {
			h.draw(canvas, (int) t);
			h.pump(t);
			SceneMirror mirror = h.client("p1").mirror(SCENE);
			assertTrue("tick " + t, h.scene.state().contentEquals(mirror.state()));
			assertFalse(mirror.needsResync());
		}
	}

	@Test
	public void lateJoinerRecoversViaHeartbeatResyncAndBodyFetch() throws Exception {
		Harness h = new Harness();
		int canvas = h.scene.createCanvas(64, 64, 4096);
		int texture = h.scene.createTexture(4, 4, new byte[64]);
		long t = 1;
		for (; t <= 5; t++) {
			h.draw(canvas, (int) t);
			h.pump(t);
		}
		h.host.subscribe("p1");
		for (long end = t + 14; t <= end; t++) {
			h.pump(t);
		}
		SceneMirror mirror = h.client("p1").mirror(SCENE);
		assertFalse(mirror.needsResync());
		assertTrue(h.scene.state().contentEquals(mirror.state()));
		assertFalse(mirror.state().resources.get(texture).isPending());
	}

	@Test
	public void lostBatchHealsViaGapDetection() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		int canvas = h.scene.createCanvas(64, 64, 4096);
		h.draw(canvas, 1);
		h.pump(1);
		h.serverDropCountdown = 0;
		h.draw(canvas, 2);
		h.pump(2);
		SceneMirror mirror = h.client("p1").mirror(SCENE);
		assertFalse(h.scene.state().contentEquals(mirror.state()));
		long t = 3;
		for (; t <= 12; t++) {
			h.draw(canvas, (int) t);
			h.pump(t);
			if (!mirror.needsResync() && h.scene.state().contentEquals(mirror.state())) {
				break;
			}
		}
		assertTrue("healed by tick " + t, h.scene.state().contentEquals(mirror.state()));
	}

	@Test
	public void lostSnapshotIsRetried() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		int canvas = h.scene.createCanvas(64, 64, 4096);
		h.draw(canvas, 1);
		h.pump(1);
		h.serverDropCountdown = 0;
		h.draw(canvas, 2);
		h.pump(2);
		h.draw(canvas, 3);
		h.pump(3); // gap detected; resync requested
		h.serverDropCountdown = 0; // the snapshot served next tick is dropped
		long t = 4;
		SceneMirror mirror = h.client("p1").mirror(SCENE);
		boolean healed = false;
		for (; t <= 25; t++) {
			h.pump(t);
			if (!mirror.needsResync() && h.scene.state().contentEquals(mirror.state())) {
				healed = true;
				break;
			}
		}
		assertTrue("healed after snapshot loss by tick " + t, healed);
	}

	@Test
	public void twoWatchersWithIndependentLossBothConverge() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		h.host.subscribe("p2");
		int canvas = h.scene.createCanvas(64, 64, 4096);
		h.draw(canvas, 1);
		h.pump(1);
		// Drop the next batch for p1 only; p2 must stay live throughout.
		h.dropOnlyFor = "p1";
		h.serverDropCountdown = 0;
		h.draw(canvas, 2);
		h.pump(2);
		assertTrue(h.scene.state().contentEquals(h.client("p2").mirror(SCENE).state()));
		long t = 3;
		for (; t <= 14; t++) {
			h.draw(canvas, (int) t);
			h.pump(t);
		}
		assertTrue(h.scene.state().contentEquals(h.client("p1").mirror(SCENE).state()));
		assertTrue(h.scene.state().contentEquals(h.client("p2").mirror(SCENE).state()));
	}

	@Test
	public void idleSceneHeartbeatsAndStaysQuietOtherwise() throws Exception {
		final int[] sent = { 0 };
		ServerScene scene = new ServerScene(SCENE);
		SceneHost host = new SceneHost(scene, new SceneTransport() {
			@Override
			public void sendToWatcher(String watcherKey, byte[] envelope) {
				sent[0]++;
			}
		}, HEARTBEAT_INTERVAL, SNAPSHOT_MIN_INTERVAL, BODIES_PER_TICK);
		host.subscribe("p1");
		int afterSubscribe = sent[0];
		for (long t = 1; t <= HEARTBEAT_INTERVAL * 3; t++) {
			host.tick(t);
		}
		assertEquals(afterSubscribe + 3, sent[0]);
	}

	@Test
	public void resyncRequestsAreRateLimited() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		h.scene.createCanvas(16, 16, 64);
		h.pump(1);
		int snapshotsServed = 0;
		for (long t = 2; t <= 14; t++) {
			h.host.onResyncRequest("p1");
			h.host.tick(t);
			snapshotsServed += h.countQueued("p1", MessageCodec.MSG_SNAPSHOT);
			Harness.queueFor(h.toClient, "p1").clear();
		}
		assertTrue("served " + snapshotsServed, snapshotsServed <= 5);
	}

	@Test
	public void resubscribeDoesNotResetTheRateLimiter() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		h.scene.createCanvas(16, 16, 64);
		h.host.tick(1);
		h.host.onResyncRequest("p1");
		h.host.tick(2); // served + stamped at tick 2
		Harness.queueFor(h.toClient, "p1").clear();
		// Unsubscribe/resubscribe churn must not grant a fresh immediate snapshot.
		h.host.unsubscribe("p1");
		h.host.subscribe("p1");
		h.host.onResyncRequest("p1");
		h.host.tick(3);
		assertEquals(0, h.countQueued("p1", MessageCodec.MSG_SNAPSHOT));
		// After the floor elapses (host clock advances first), the request is honored again.
		h.host.tick(2 + SNAPSHOT_MIN_INTERVAL);
		h.host.onResyncRequest("p1");
		h.host.tick(3 + SNAPSHOT_MIN_INTERVAL);
		assertEquals(1, h.countQueued("p1", MessageCodec.MSG_SNAPSHOT));
	}

	@Test
	public void unsubscribeBeforePendingSnapshotServeSendsNothing() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		h.scene.createCanvas(16, 16, 64);
		h.host.tick(1);
		Harness.queueFor(h.toClient, "p1").clear();
		h.host.onResyncRequest("p1"); // queued for next tick boundary...
		h.host.unsubscribe("p1");     // ...but the watcher leaves first
		h.host.tick(2);
		assertEquals(0, Harness.queueFor(h.toClient, "p1").size());
	}

	@Test
	public void resourceRequestFloodIsBounded() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		int texture = h.scene.createTexture(8, 8, new byte[256]);
		h.host.tick(1);
		Harness.queueFor(h.toClient, "p1").clear();
		int bodiesServed = 0;
		// 30-byte requests every tick for the same body must be served at most once per
		// floor window, not once per request.
		for (long t = 2; t <= 14; t++) {
			h.host.onResourceRequest("p1", texture);
			h.host.tick(t);
			bodiesServed += h.countQueued("p1", MessageCodec.MSG_RESOURCE_BODY);
			Harness.queueFor(h.toClient, "p1").clear();
		}
		assertTrue("served " + bodiesServed, bodiesServed <= 5);
	}

	@Test
	public void resyncDoesNotRedownloadCachedBodies() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		int canvas = h.scene.createCanvas(64, 64, 4096);
		int texture = h.scene.createTexture(4, 4, new byte[64]);
		long t = 1;
		// Converge fully, including the body fetch.
		for (; t <= 10; t++) {
			h.pump(t);
		}
		SceneMirror mirror = h.client("p1").mirror(SCENE);
		assertFalse(mirror.state().resources.get(texture).isPending());
		// Reset the wire-request counter, then force a resync (dropped batch).
		h.requestCounter[0] = 0;
		h.serverDropCountdown = 0;
		h.draw(canvas, 99);
		h.pump(++t);
		for (long end = t + 12; t <= end; t++) {
			h.draw(canvas, (int) t);
			h.pump(t);
		}
		assertTrue(h.scene.state().contentEquals(mirror.state()));
		assertFalse(mirror.state().resources.get(texture).isPending());
		// The snapshot flipped the texture back to pending, but the hash-keyed client cache
		// satisfied it locally: zero new wire requests.
		assertEquals(0, h.requestCounter[0]);
	}

	@Test
	public void sceneGoneEvictsTheMirror() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		int canvas = h.scene.createCanvas(64, 64, 4096);
		h.draw(canvas, 1);
		h.pump(1);
		assertTrue(h.client("p1").hasMirror(SCENE));
		h.host.destroy();
		h.pump(2);
		assertFalse(h.client("p1").hasMirror(SCENE));
		// No retry traffic for the dead scene.
		for (long t = 3; t <= 10; t++) {
			h.pump(t);
		}
		assertEquals(0, Harness.queueFor(h.toServer, "p1").size());
		assertFalse(h.client("p1").hasMirror(SCENE));
	}

	@Test
	public void unansweredResyncLoopDecays() throws Exception {
		final ArrayDeque<byte[]> requests = new ArrayDeque<byte[]>();
		MirrorClient client = new MirrorClient(new ClientTransport() {
			@Override
			public void sendToServer(byte[] envelope) {
				requests.addLast(envelope);
			}
		}, RESYNC_RETRY, 3, 8);
		// A straggler heartbeat resurrects a mirror nobody will ever answer.
		byte[] heartbeat = MessageCodec.envelope(MessageCodec.MSG_HEARTBEAT,
				MessageCodec.encodeHeartbeat(new MessageCodec.Heartbeat("ghost", 42)));
		client.onMessage(heartbeat);
		assertTrue(client.hasMirror("ghost"));
		for (long t = 1; t <= 40; t++) {
			client.tick(t);
		}
		// Bounded requests (attempt ceiling), then the zombie is dropped.
		assertTrue("requests " + requests.size(), requests.size() <= 3);
		assertFalse(client.hasMirror("ghost"));
	}

	@Test
	public void unsubscribedWatcherReceivesNothing() throws Exception {
		Harness h = new Harness();
		h.host.subscribe("p1");
		h.host.unsubscribe("p1");
		int canvas = h.scene.createCanvas(64, 64, 4096);
		h.draw(canvas, 1);
		h.host.tick(1);
		assertEquals(1, Harness.queueFor(h.toClient, "p1").size()); // only the subscribe heartbeat
		h.host.onResyncRequest("p1");
		h.host.onResourceRequest("p1", 1);
		h.host.tick(2);
		assertEquals(1, Harness.queueFor(h.toClient, "p1").size());
	}
}
