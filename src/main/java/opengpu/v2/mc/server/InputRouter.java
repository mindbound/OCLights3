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

	/**
	 * A gesture in flight: everything needed to END it without the client's help.
	 *
	 * The id alone is not enough, and that is the whole point. A release has to be emitted
	 * FROM THE NODE THE PRESS WAS EMITTED FROM — after a rebind, sending through the GPU's
	 * current screen would deliver {@code monitor_up(newAddress)} for a
	 * {@code monitor_down(oldAddress)}: a phantom release on a surface the player never
	 * touched, while the real gesture stays stuck. The coordinates ride along so a
	 * server-originated release lands where the gesture actually was, rather than at a
	 * position a disqualified client got to choose.
	 */
	private static final class Pointer {
		final int id;
		final int button;
		/** The surface the DOWN was emitted from, by ADDRESS — never by Node reference. */
		final String address;
		int x;
		int y;

		Pointer(int id, int button, String address, int x, int y) {
			this.id = id;
			this.button = button;
			this.address = address;
			this.x = x;
			this.y = y;
		}
	}

	/** Resolves a watcher key to the player it belongs to, or null when they are gone. */
	public interface PlayerLookup {
		EntityPlayer find(String watcherKey);
	}

	private final Map<String, Pointer> activePointer = new HashMap<String, Pointer>();
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

	private static String watcherOf(String slot) {
		int hash = slot.lastIndexOf('#');
		return hash < 0 ? slot : slot.substring(0, hash);
	}

	/**
	 * End every gesture held on one surface, by EMITTING its release.
	 *
	 * This exists because the defect was never that {@code activePointer} leaked — a map entry
	 * costs nothing and dies with the chunk. The defect is that a {@code monitor_down} reached
	 * an OC machine and no {@code monitor_up} followed, and that belief lives in persisted Lua
	 * state. Only a signal ends it. The old {@code evictWatcher} cleared the map and emitted
	 * nothing, so it left every machine holding the button exactly as before; forgetting the
	 * slot is not a lesser fix, it is not a fix at all.
	 *
	 * Called at the transitions where the server knows a gesture cannot be completed against
	 * this surface: unbind, rebind, screen removal, the wall-follow when a bound screen is
	 * absorbed and demoted, and the driver-takeover drop after a network partition heals. Those
	 * all run on the server thread while the OLD screen is still live and its node still
	 * attached, which is what makes the release deliverable — {@code BlockScreen2.breakBlock}
	 * deliberately notifies before {@code super.breakBlock} for this reason.
	 *
	 * That list has been wrong twice by omission, so treat it as a summary and not an index:
	 * the authoritative set is whatever calls this method and {@code flushBoundScreenLocked}.
	 *
	 * Not reachable at all when the screen's own chunk unloads: {@code invalidate()} and
	 * {@code onChunkUnload()} both call {@code node.remove()} first, so there is no network
	 * left to send on. That case is accepted and documented rather than papered over.
	 *
	 * @return how many releases were emitted
	 */
	public int flushScreen(String screenAddress, Node fromNetwork, PlayerLookup lookup,
			int sceneWidth, int sceneHeight) {
		if (screenAddress == null) {
			return 0;
		}
		int sent = 0;
		java.util.Iterator<Map.Entry<String, Pointer>> it = activePointer.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Pointer> entry = it.next();
			Pointer p = entry.getValue();
			if (!screenAddress.equals(p.address)) {
				continue;
			}
			// REMOVED ONLY ON A SUCCESSFUL SEND — the same rule as flushPointer, and now the
			// rule everywhere. An earlier version removed regardless, justified by "the surface
			// is going away". That premise is false at half the call sites: bind() and unbind()
			// leave the screen in the world, and the flush is by address precisely so it works
			// while the screen's chunk is unloaded — which is exactly when the send cannot
			// resolve a target. So the case the comment cited as the reason to flush was the
			// case where flushing destroyed the record and emitted nothing.
			//
			// The other half of that justification — that a retained slot could let a forged
			// release reuse its id on a different screen — is impossible: route()'s address
			// check refuses to emit a slot's release on any surface but its own.
			EntityPlayer player = lookup == null ? null : lookup.find(watcherOf(entry.getKey()));
			if (emitRelease(p, player, fromNetwork, sceneWidth, sceneHeight)) {
				it.remove();
				sent++;
			}
		}
		return sent;
	}

	/**
	 * Every gesture held by anyone, whatever surface it is on. For teardown of this GPU.
	 *
	 * The only flush where removal is unconditional, and it costs nothing to be: this router
	 * dies with the tile entity that owns it, so the map is about to be unreachable either way.
	 * Retention exists to preserve a retry, and there will be no later event to retry on.
	 */
	public int flushAll(Node fromNetwork, PlayerLookup lookup, int sceneWidth, int sceneHeight) {
		int sent = 0;
		java.util.Iterator<Map.Entry<String, Pointer>> it = activePointer.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Pointer> entry = it.next();
			Pointer p = entry.getValue();
			it.remove();
			EntityPlayer player = lookup == null ? null : lookup.find(watcherOf(entry.getKey()));
			if (emitRelease(p, player, fromNetwork, sceneWidth, sceneHeight)) {
				sent++;
			}
		}
		return sent;
	}

	/**
	 * End every gesture this watcher holds. Called on disconnect, where the player object is
	 * still available — {@code PlayerLoggedOutEvent} carries it — which is the only moment a
	 * checked signal can still be attributed to them.
	 *
	 * @return how many releases were emitted
	 */
	public int flushWatcher(String watcherKey, EntityPlayer player, Node fromNetwork,
			int sceneWidth, int sceneHeight) {
		int sent = 0;
		for (int button = 0; button <= 2; button++) {
			sent += flushPointer(watcherKey, button, player, fromNetwork, sceneWidth, sceneHeight);
		}
		// DELIBERATELY does not touch eventsThisTick. It used to, back when the only caller was
		// disconnect eviction and clearing a departing player's counter looked tidy. It is now
		// called from onInput's rejecting gates, and one of those gates turns on `input.epoch`
		// — a field read straight off the wire. Resetting the allowance there would let a
		// client restore its own rate limit on demand by sending a deliberately stale epoch
		// between bursts, which defeats the one bound on how many signals a player can force
		// into every machine on the network.
		//
		// It was never load-bearing anyway: beginTick() clears the whole map once per tick.
		return sent;
	}

	/**
	 * End ONE button's gesture, and only if the release can actually be delivered.
	 *
	 * Two things distinguish this from {@link #flushScreen}, and both were got wrong by copying
	 * that method's rule across.
	 *
	 * PER BUTTON. Slots are keyed per button precisely because two-button drags are ordinary
	 * (see route()); a gate rejecting a release of button 1 says nothing about button 0, and
	 * ending all three would kill a live drag the player is still holding.
	 *
	 * THE SLOT SURVIVES A FAILED SEND. flushScreen removes regardless because its surface is
	 * genuinely going away, so the client can never complete the gesture. That premise is false
	 * on the gate paths: a screen whose chunk merely unloaded comes back with the same node
	 * address — resolveScreenLocked deliberately keeps the claim for exactly that reason — and
	 * the client's real release then works. Removing the slot here destroyed that recovery and
	 * left nothing emitted, which is the very behaviour this file condemns two methods up.
	 * Keeping it also means the next gated event retries the send, which self-heals.
	 *
	 * @return 1 if a release was emitted, 0 otherwise
	 */
	public int flushPointer(String watcherKey, int button, EntityPlayer player, Node fromNetwork,
			int sceneWidth, int sceneHeight) {
		String slot = pointerSlot(watcherKey, button);
		Pointer p = activePointer.get(slot);
		if (p == null) {
			return 0;
		}
		if (!emitRelease(p, player, fromNetwork, sceneWidth, sceneHeight)) {
			return 0; // kept deliberately: see above
		}
		activePointer.remove(slot);
		return 1;
	}

	/**
	 * Send one server-originated {@code monitor_up}.
	 *
	 * Deliberately NOT rate-limited and not reach-checked. Neither guard applies: this is not
	 * client traffic, and the bounded quantity is the number of presses the server itself
	 * admitted through both guards.
	 *
	 * Exactly-once holds by a different argument than it used to. The slot is no longer removed
	 * before this runs — callers keep it when the send fails, so a later event can retry — so
	 * the guarantee is that a SUCCESSFUL send is immediately followed by removal at every
	 * caller, and a failed one emits nothing to be duplicated. A retry after a failure is a
	 * first delivery, not a second.
	 */
	private boolean emitRelease(Pointer p, EntityPlayer player, Node fromNetwork,
			int sceneWidth, int sceneHeight) {
		if (player == null || fromNetwork == null || fromNetwork.network() == null) {
			return false;
		}
		// Resolved BY ADDRESS at send time, never from a Node captured at the press. Holding a
		// Node would pin the screen's TileEntity — and through it a chunk — for as long as a
		// gesture sat unreleased, and would keep sending through an object that may since have
		// been removed and replaced. Every other cross-object reference in this codebase is an
		// address for the same reason. A null here is the surface having genuinely gone, which
		// is the one case that stays undeliverable.
		Node target = fromNetwork.network().node(p.address);
		if (target == null || target.address() == null) {
			return false;
		}
		// Clamped to the LIVE scene size, not the size at press time. setResolution can shrink
		// the scene while a gesture is held, and a release outside the canvas is exactly what
		// route()'s own bounds check refuses to let a client send.
		int x = sceneWidth > 0 ? Math.max(0, Math.min(sceneWidth - 1, p.x)) : p.x;
		int y = sceneHeight > 0 ? Math.max(0, Math.min(sceneHeight - 1, p.y)) : p.y;
		target.sendToReachable("computer.checked_signal", player, "monitor_up",
				Integer.valueOf(x), Integer.valueOf(y),
				Integer.valueOf(p.button), Integer.valueOf(p.id));
		return true;
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
				int px = input.a, py = input.b;
				if (!inBounds(px, py, sceneWidth, sceneHeight)) {
					// A RELEASE is CLAMPED, never dropped — the same reasoning as the
					// rate-limit exemption above, which this check silently undid: returning
					// here skips the activePointer.remove() below, so Lua sees a press that
					// never ends and the next press reuses the stale slot.
					//
					// Out-of-bounds became reachable when the scene size stopped being a
					// constant. The server applies setResolution the instant Lua calls it (a
					// direct callback), while the client keeps mapping clicks through the old
					// size until the batch is sealed, shipped, and rendered — a window of a
					// tick plus latency plus a frame, easily long enough to release in.
					if (!isRelease) {
						return false;
					}
					px = Math.max(0, Math.min(sceneWidth - 1, px));
					py = Math.max(0, Math.min(sceneHeight - 1, py));
				}
				if (input.c < 0 || input.c > 2)
					return false; // left/right/middle only
				// Keyed per BUTTON, not just per watcher: a second button pressed during a
				// drag would otherwise overwrite the first one's id, so the first press
				// could never be released and its move/up events would carry the wrong
				// gesture. Two-button drags are ordinary in a paint program.
				String slot = pointerSlot(watcherKey, input.c);
				int pointerId;
				if (input.kind == MessageCodec.INPUT_POINTER_DOWN) {
					// An occupied slot means a press whose release never arrived — a lost
					// packet, or one of the gated paths. Overwriting it silently orphaned the
					// incumbent: Lua kept the old id held forever while a new id started on
					// the same button. END it before reusing the slot.
					Pointer incumbent = activePointer.remove(slot);
					if (incumbent != null) {
						emitRelease(incumbent, player, node, sceneWidth, sceneHeight);
					}
					pointerId = nextPointerId++;
					if (nextPointerId <= 0) {
						nextPointerId = 1; // wrap: ids are opaque and short-lived
					}
					// The ADDRESS is captured here, at the press, because that is the surface
					// the gesture belongs to. Everything that later ends this gesture must
					// release against it and not against whatever the GPU is bound to by then.
					activePointer.put(slot, new Pointer(pointerId, input.c, node.address(), px, py));
				} else {
					Pointer active = activePointer.get(slot);
					// A move or release with no press behind it is either a lost packet or a
					// forged one; either way there is no gesture to attribute it to.
					if (active == null)
						return false;
					// THE SAME RULE THIS FILE STATES FOR THE FLUSH PATH, ENFORCED HERE TOO — and
					// this is the path that carries almost every real release. The binding can
					// move under a live gesture without any surface being destroyed: when a
					// bound screen is absorbed into a larger wall it is demoted to a satellite
					// and TileEntityGpu2.resolveScreenLocked deliberately FOLLOWS the new
					// origin. Emitting through whatever screen is bound now would hand Lua a
					// monitor_up for a surface it never received a monitor_down from, while the
					// real gesture stayed held forever. Release the surface it actually belongs
					// to and refuse to fabricate one on the new surface.
					if (!active.address.equals(node.address())) {
						// Remove only on a successful send, as everywhere else. The old surface
						// may be temporarily unresolvable — a cut cable, an unloaded chunk —
						// and dropping the slot then would strand the gesture with nothing
						// emitted and nothing left to retry with. Retention is safe because
						// this very check refuses to emit it on the new surface, and the next
						// move for this button re-enters here and tries again.
						if (emitRelease(active, player, node, sceneWidth, sceneHeight)) {
							activePointer.remove(slot);
							return true;
						}
						return false;
					}
					pointerId = active.id;
					// Track where the gesture is, so a server-originated release lands at the
					// last position the player actually reached rather than at the origin.
					active.x = px;
					active.y = py;
					if (input.kind == MessageCodec.INPUT_POINTER_UP) {
						activePointer.remove(slot);
					}
				}
				String name = input.kind == MessageCodec.INPUT_POINTER_DOWN ? "monitor_down"
						: input.kind == MessageCodec.INPUT_POINTER_MOVE ? "monitor_move" : "monitor_up";
				node.sendToReachable("computer.checked_signal", player, name,
						Integer.valueOf(px), Integer.valueOf(py),
						Integer.valueOf(input.c), Integer.valueOf(pointerId));
				return true;
			}
			case MessageCodec.INPUT_SCROLL: {
				if (!inBounds(input.a, input.b, sceneWidth, sceneHeight))
					return false;
				if (input.c != 1 && input.c != -1)
					return false; // one notch at a time; a client cannot amplify a scroll
				// Address-checked like the move/release path. A slot can now legitimately be
				// RETAINED after a failed release — its surface temporarily unresolvable — and
				// reporting that gesture's id on a scroll over a different surface would tell a
				// program a scroll belonged to a drag on a screen it is not looking at. Zero,
				// meaning "no gesture", is the honest answer.
				Pointer active = activePointer.get(pointerSlot(watcherKey, 0));
				int scrollGesture = active != null && active.address.equals(node.address())
						? active.id : 0;
				node.sendToReachable("computer.checked_signal", player, "monitor_scroll",
						Integer.valueOf(input.a), Integer.valueOf(input.b),
						Integer.valueOf(input.c), Integer.valueOf(scrollGesture));
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
