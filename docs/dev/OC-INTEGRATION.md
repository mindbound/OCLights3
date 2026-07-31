# OCLights3 — OpenComputers Integration & API Notes

How the mod integrates with OpenComputers today, the full Lua-facing API as shipped, and what the
GTNH OC API expects from a well-behaved component mod (with OC's own screen sync documented as
prior art for the performance rework).

Reference: GTNH OpenComputers clone at `C:\Users\astro\Downloads\OpenComputers-GTNH`.
**Version caveat:** the build pins `1.12.8-GTNH` but the reference checkout is `1.12.55-GTNH`-era
(API `6.0.0-alpha`); the `1.12.8-GTNH` tag has been fetched into the clone for diffing when a
behavioral question matters. API-surface claims below were checked against the checkout; nothing
the mod uses is deprecated or removed there.

## Current integration (as inherited)

- `TileEntityGPU` implements `li.cil.oc.api.network.Environment` directly (TileEntityGPU.java:52).
- Constructor creates: the `GPU(8192)` state object; a **loot filesystem** from
  `assets/oclights/lua` labeled `ocl_gpu` (FileSystem.fromClass + asManagedEnvironment); and the
  node: `Network.newNode(this, Visibility.Network).withComponent("ocl_gpu").create()` —
  **no `.withConnector()`, so there is zero energy integration in the mod** (TileEntityGPU.java:67-72).
- Joins the network lazily on first tick (`addedToNetwork` flag) — the canonical pattern;
  `node.remove()` in both `onChunkUnload()` and `invalidate()`. Node NBT persists under `oc:node`,
  the FS node under `oc:fsnode`.
- `onConnect` records each connecting machine `Context` in a list (used to fan out signals) and
  wires the loot-FS node to the computer; `onDisconnect` mirrors it (TileEntityGPU.java:731-747).
- All 39 callbacks are `@Callback(direct=true)` with **no `limit=`, no `doc=`, and no
  `consumeCallBudget`** — unthrottled and undocumented in-game. (Extension levers; also a
  correctness problem, see ISSUES T-01.)
- Monitors are **not** components: Lua reaches them via `getMonitor()` returning a `MonitorObject`
  (`li.cil.oc.api.prefab.AbstractValue`) — but it's a non-static inner class, which OC's value
  persistence cannot reinstantiate (ISSUES A-03).
- Secondary components use `SimpleComponent`: `tablet_transceiver` (registered), `light` /
  `light_adv` (dead, unregistered).
- Argument conversion assumes JNLua boxes all Lua numbers as `Double` (`converter/ConvertDouble`).
  GTNH OC boxes integral numbers as **`Long`** under Lua 5.3/5.4 architectures
  (ExtendedLuaState.scala:108), so table-consuming callbacks break there (ISSUES A-01).

## Lua API as shipped (component `ocl_gpu`)

Draw state: one current color (RGBA 0-255), one bound texture slot, one transform + stack.
Coordinates are ints (doubles for transforms). Draws target the bound texture; slot 0 = the screen.

| Callback | Signature → returns | Notes |
|---|---|---|
| `fill` | `()` | fill bound texture with current color |
| `plot` | `(x, y)` | transform-aware bounds pre-check, silently no-ops offscreen |
| `line`, `rectangle`, `filledRectangle`, `clearRectangle` | `(x,y,…)` | clearRectangle = hard set, no alpha blend |
| `triangle`, `filledTriangle` | `(x1,y1,x2,y2,x3,y3)` | |
| `oval`, `filledOval` | `(cx, cy, w, h)` | center-anchored |
| `drawText` | `(text, x, y)` | 6×8 bitmap font |
| `getTextWidth` | `(text) → int` | |
| `setColor` | `(r, g, b[, a=255])` | buggy dedupe early-out (ISSUES G-04) |
| `getColor` | `() → r, g, b, a` | |
| `createTexture` | `(w, h) → {id}` | errors "Not enough memory"/"Not enough texture slots" |
| `bindTexture`, `freeTexture`, `getBindedTexture` | | id validation broken (ISSUES G-02) |
| `drawTexture` | `(id, x, y)` or `(id, x, y, tx, ty, w, h)` | 4–6 args rejected; tinted by current color |
| `getSize` | `([id]) → w, h` | defaults to bound |
| `getPixelColor` | `(x, y) → r, g, b, a` | reads server raster; % coordinate wrap (ISSUES G-08) |
| `setPixels` | `(w, h, x, y, pixels)` | table of w\*h\*4 channel values; broken off-by-one + Long/Double issue (ISSUES G-01, A-01) |
| `flipVertically` | `(id)` | broken (ISSUES G-05) |
| `import` | `(table \| string)` or `(fsAddress, path)` `→ {id, w, h}` | 2-string form reads the OC save dir directly; broken on Windows (ISSUES A-02) |
| `export` | `(id, format) → table` | signed-byte values; garbage on unknown format (ISSUES A-04) |
| `translate`, `rotate`, `rotateAround`, `scale`, `push`, `pop`, `origin` | doubles | transform stack |
| `getFreeMemory`, `getTotalMemory`, `getUsedMemory` | `() → int` | |
| `startFrame`, `endFrame` | `()` | suspend/resume the per-tick flush (no timeout — ISSUES G-07) |
| `getMonitor` | `() → MonitorObject` | NPE if no monitor attached |
| `blur` | `(id)` | radius-2 box blur |

Value objects: `MonitorObject.getResolution() → w, h`; external monitors add `getDPM() → 32` and
`getBlockResolution() → w, h` (blocks). Component `tablet_transceiver`: `getResolution`,
`getNumberOfTablets`, `getTabletUUID(index)` (0-based, unchecked), `disconnect()` (calls
`invalidate()` — ISSUES U-08).

Signals pushed into attached computers: `monitor_down` / `monitor_move` / `monitor_up`
`(address, x, y, button, id)`; `monitor_scroll (x, y, dir)`; `key_down` / `key_up` `(addr, char, code)`
— **name-collides with OC's standard keyboard signals but with a different argument shape**
(OpenOS interop hazard, worth renaming or aligning in the API rework); `tablet_image (table)`.

Shipped Lua lib (`lib/gpu.lua`): metatable proxy to `component.ocl_gpu` plus `gpu.importFile(path)`
which resolves a mount path to `(fs address, rel path)` for `import`.

## The correct GTNH OC component pattern (from the API sources)

What a rework should follow (today's code mostly complies on lifecycle, and diverges on threading):

1. **Node creation** — once, in the constructor:
   `Network.newNode(this, Visibility.Network).withComponent(name[, visibility]).withConnector(buffer).create()`.
   Component names are `lowercase_with_underscores`. Reachability (node graph) and visibility
   (computer-facing) are distinct; OC's own GPU uses `Neighbors`, its screen `Network`.
2. **Join on first tick**, not `validate()` (neighbors are inaccessible there):
   set a flag in `updateEntity`, call `Network.joinOrCreateNetwork(this)` once. Guard by
   `!worldObj.isRemote` (the prefab omits the guard and relies on an internal check — don't copy that;
   OCLights2 currently creates its node client-side too, ISSUES A-05).
3. **Dispose** with `node.remove()` in *both* `invalidate()` and `onChunkUnload()`.
4. **Persist** node NBT under a namespaced child tag (`oc:node`), guarded by `node.host() == this`.
   The address in that tag is what computers store — losing it breaks user programs.
5. **Callbacks**: `@Callback` on `Object[] f(Context, Arguments)`. `direct=false` (default) runs on
   the **server thread** (safe world access). `direct=true` runs on the **computer's executor
   thread**: the node network is *not* thread-safe there; `Connector.changeBuffer` *is*. Use
   `limit=` / `Context.consumeCallBudget` for throttling — exhausting the budget converts the call
   into a next-tick synchronized retry via `LimitReachedException`, transparently to Lua.
6. **Signals** support nil/boolean/number/string/byte[]/simple maps only.
   `Arguments.checkString` does UTF-8 conversion from the byte[] Lua strings arrive as.
7. **Registration timing**: all `api.Driver.add` / `api.Network` calls in init or later — `API.*`
   fields are null before pre-init completes.
8. **AbstractValue** subclasses handed to Lua must be public static/top-level with a no-arg
   constructor (OC unpersists via `clazz.newInstance`), overriding save/load to rebind context.

`Context.pause()` from the server thread **blocks until the current executor task completes** — OC
exploits this in `TextBuffer.save` to keep direct-callback state consistent with world saves; the
same trick is available to us for texture persistence.

## Prior art: OC's own screen sync

OC solved the exact problem OCLights has (server-authoritative pixel-ish buffer, many clients,
direct callbacks) — the pattern to steal for the Phase-2 rework:

1. One authoritative server-side buffer in a `ManagedEnvironment`; the **same class** is
   instantiated client-side with a Server/Client proxy split selected at construction.
2. GPU mutation callbacks are `direct=true` with per-tier call budgets + energy cost, mutating the
   server buffer under `synchronized`.
3. Every mutation appends a compact typed command to **one lazily-created compressed packet builder
   per buffer, keyed by node address**; `update()` flushes at most once per tick to players near the
   host (view-distance + config-capped range, `isPlayerWatchingChunk`).
4. Client replays the command stream onto its mirror and sets a dirty flag consumed by a render
   cache (no per-frame re-upload of unchanged content).
5. **Full-state resync is client-initiated and retried**: client learns the node address from
   description-packet NBT, asks every 100 ticks until answered; server validates the requester is
   watching the chunk and replies with a compressed full dump to that one player. Handles late
   joiners, chunk reloads, and lost packets with zero per-player server bookkeeping.
6. Address→component routing via per-world weak-value `ComponentTracker` caches on both sides.
7. VRAM pages are nodeless buffers; full NBT sent once on first use, then only tiny bitblt commands;
   a dirty flag scales the bitblt budget so full-screen blit spam self-throttles.
8. Large payloads are saved **out-of-band** via `SaveHandler.scheduleSave` keyed by node address —
   not inline TE NBT (OCLights2's PNG-in-NBT approach is the anti-pattern, ISSUES S-01..S-03).

Key files: `common/component/TextBuffer.scala`, `server/component/GraphicsCard.scala`,
`common/component/GpuTextBuffer.scala`, `server/PacketSender.scala`, `client/PacketHandler.scala`,
`common/PacketBuilder.scala`, `common/ComponentTracker.scala`.

## Upstream (GTNH OC) quirks found while surveying

Noted for awareness — these are upstream bugs, not ours; worth reporting to GTNH:

- `TextBuffer.setMaximumResolution` propagates width twice — client receives `(w, w)`
  (TextBuffer.scala:249).
- `GpuTextBuffer.getViewportWidth/Height` are transposed (GpuTextBuffer.scala:24-25).
- Resolution validation checks `h > mw` (height against max *width*) in three places, admitting
  transposed portrait resolutions (TextBuffer.scala:262, GraphicsCard.scala:410, 435).
