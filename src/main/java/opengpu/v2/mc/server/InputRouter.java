package opengpu.v2.mc.server;

import java.util.HashMap;
import java.util.Map;

import li.cil.oc.api.network.Node;
import net.minecraft.entity.player.EntityPlayer;
import opengpu.v2.protocol.MessageCodec;

/**
 * Validates player input against a scene and turns it into OC signals.
 *
 * Everything a client sends is treated as hostile until proven otherwise (legacy X-01): the
 * surface is resolved from the server's OWN binding rather than named by the client, the
 * coordinates are range-checked against the scene's logical size, and only a fixed set of
 * signal names can ever be emitted. Identity is an opaque per-press id, never a player name.
 *
 * Signals are sent from the SCREEN's node, because OC's Machine prepends the sending node's
 * address to every signal — which is exactly the {@code (surfaceAddress, ...)} shape the
 * design specifies — and via {@code computer.checked_signal}, which makes OC apply its own
 * canInteract permission rules for free.
 *
 * Server thread only, under the owning TE's scene lock.
 */
public final class InputRouter {
	/** Max input events accepted from one watcher per tick; excess is dropped silently. */
	public static final int MAX_EVENTS_PER_WATCHER_PER_TICK = 20;

	private final Map<String, Integer> activePointer = new HashMap<String, Integer>();
	private final Map<String, Integer> eventsThisTick = new HashMap<String, Integer>();
	private long currentTick = Long.MIN_VALUE;
	private int nextPointerId = 1;

	/** Called at the start of each pump so the per-tick allowance resets exactly once. */
	public void beginTick(long tick) {
		if (tick != currentTick) {
			currentTick = tick;
			eventsThisTick.clear();
		}
	}

	private static String pointerSlot(String watcherKey, int button) {
		return watcherKey + '#' + button;
	}

	/** A watcher disconnected: forget every press it was holding. */
	public void evictWatcher(String watcherKey) {
		for (int button = 0; button <= 2; button++) {
			activePointer.remove(pointerSlot(watcherKey, button));
		}
		eventsThisTick.remove(watcherKey);
	}

	/**
	 * @param screen  the surface the input landed on, whose node sends the signal
	 * @param player  the originating player, for OC's permission check
	 * @return true when a signal was emitted
	 */
	public boolean route(MessageCodec.Input input, String watcherKey, EntityPlayer player,
			TileEntityScreen2 screen, int sceneWidth, int sceneHeight) {
		if (screen == null || player == null) {
			return false;
		}
		Node node = screen.node();
		if (node == null || node.address() == null) {
			return false;
		}
		// Rate limit before any work: input is the one inbound path a client can drive at
		// will, and a signal flood would stall every machine on the network.
		//
		// A RELEASE is exempt. Dropping one leaves activePointer set forever: Lua sees a
		// press that never ends, and the next press reuses the stale slot. Releases are
		// self-limiting anyway — there can only be as many as there were presses, and those
		// ARE capped.
		boolean isRelease = input.kind == MessageCodec.INPUT_POINTER_UP;
		if (!isRelease) {
			Integer used = eventsThisTick.get(watcherKey);
			int count = used == null ? 0 : used;
			if (count >= MAX_EVENTS_PER_WATCHER_PER_TICK) {
				return false;
			}
			eventsThisTick.put(watcherKey, count + 1);
		}

		switch (input.kind) {
			case MessageCodec.INPUT_POINTER_DOWN:
			case MessageCodec.INPUT_POINTER_MOVE:
			case MessageCodec.INPUT_POINTER_UP: {
				if (!inBounds(input.a, input.b, sceneWidth, sceneHeight))
					return false;
				if (input.c < 0 || input.c > 2)
					return false; // left/right/middle only
				// Keyed per BUTTON, not just per watcher: a second button pressed during a
				// drag would otherwise overwrite the first one's id, so the first press
				// could never be released and its move/up events would carry the wrong
				// gesture. Two-button drags are ordinary in a paint program.
				String slot = pointerSlot(watcherKey, input.c);
				int pointerId;
				if (input.kind == MessageCodec.INPUT_POINTER_DOWN) {
					pointerId = nextPointerId++;
					if (nextPointerId <= 0) {
						nextPointerId = 1; // wrap: ids are opaque and short-lived
					}
					activePointer.put(slot, pointerId);
				} else {
					Integer active = activePointer.get(slot);
					// A move or release with no press behind it is either a lost packet or a
					// forged one; either way there is no gesture to attribute it to.
					if (active == null)
						return false;
					pointerId = active;
					if (input.kind == MessageCodec.INPUT_POINTER_UP) {
						activePointer.remove(slot);
					}
				}
				String name = input.kind == MessageCodec.INPUT_POINTER_DOWN ? "monitor_down"
						: input.kind == MessageCodec.INPUT_POINTER_MOVE ? "monitor_move" : "monitor_up";
				node.sendToReachable("computer.checked_signal", player, name,
						Integer.valueOf(input.a), Integer.valueOf(input.b),
						Integer.valueOf(input.c), Integer.valueOf(pointerId));
				return true;
			}
			case MessageCodec.INPUT_SCROLL: {
				if (!inBounds(input.a, input.b, sceneWidth, sceneHeight))
					return false;
				if (input.c != 1 && input.c != -1)
					return false; // one notch at a time; a client cannot amplify a scroll
				Integer active = activePointer.get(pointerSlot(watcherKey, 0));
				node.sendToReachable("computer.checked_signal", player, "monitor_scroll",
						Integer.valueOf(input.a), Integer.valueOf(input.b),
						Integer.valueOf(input.c), Integer.valueOf(active == null ? 0 : active));
				return true;
			}
			case MessageCodec.INPUT_KEY_DOWN:
			case MessageCodec.INPUT_KEY_UP: {
				// Deliberately NOT named key_down/key_up: OpenOS binds those for real
				// keyboards, and the legacy mod's collision with them (with a different
				// argument shape) was a documented interop hazard.
				if (input.a < 0 || input.a > 0xFFFF || input.b < 0 || input.b > 0xFF)
					return false;
				String name = input.kind == MessageCodec.INPUT_KEY_DOWN
						? "monitor_key_down" : "monitor_key_up";
				node.sendToReachable("computer.checked_signal", player, name,
						Integer.valueOf(input.a), Integer.valueOf(input.b));
				return true;
			}
			default:
				return false; // decodeInput already rejects unknown kinds
		}
	}

	private static boolean inBounds(int x, int y, int width, int height) {
		return x >= 0 && y >= 0 && x < width && y < height;
	}
}
