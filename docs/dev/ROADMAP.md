# OpenGPU — Roadmap

Phases are ordered by the project priorities (fix → performance → completeness → API extension),
but Phase 0 comes first because identity/licensing decisions gate what the fixes may break, and a
few Phase-1 items (the protocol redesign) naturally merge into Phase 2. Issue IDs reference
[ISSUES.md](ISSUES.md).

## Phase 0 — Project identity, hygiene, provenance

**Decisions needed (open questions):**

1. ~~**Mod identity**~~ — DONE 2026-07-31: renamed to `OCLights3` with full save compat
   (missing-mappings remap + TE alternatives; details in ISSUES B-02).
   **Superseded 2026-08-01: final identity is `OpenGPU`** (modid `OpenGPU`, display name
   "OpenGPU", Java package root `opengpu.*` — legacy code at `opengpu.`, new core at
   `opengpu.v2.` until the old code is deleted). The remap now accepts both `OCLights2:*`
   and interim `OCLights3:*` ids; TE registrations carry `OCLights3:*` alternatives.
   Decided while no world had ever seen OCLights3 ids, so the rename costs nothing in
   save compat. Asset domain stays `oclights` (legacy assets die at Stage A). Repo
   directory/GitHub name still say OCLights3 — renaming those is the user's call.
2. **Licensing / asset provenance** — DEFERRED 2026-07-31: OCLights3 is private/internal-use for
   now, and these are *redistribution* obligations, so nothing is required yet. **This is a hard
   publish gate**: before publishing jars or the repo anywhere (including a multiplayer server
   where others download the jar, or a modpack), do the following:
   (a) jhlabs filters carry "Copyright 2005 Huxtable.com. All rights reserved." headers with no
   license grant (upstream is Apache-2.0) — prune the dead classes, keep `BoxBlurFilter` + its
   actual dependencies with restored Apache-2.0 headers, add `THIRD-PARTY.md` with the Apache-2.0
   text; (b) `ascii.png` may be Mojang-derived — pixel-compare against vanilla
   `font/ascii.png` (local install at `D:\Minecraft`) and replace with a free 6×8 font atlas
   (e.g. unscii, public domain) if it matches; (c) repo stays MMPL-1.0 (relicensing would need
   consent from ~6 historical copyright holders — not worth pursuing).
3. ~~**OC dependency pin**~~ — DONE 2026-07-31: bumped to `1.12.55-GTNH`.

**Mechanical tasks:**

- Set a version scheme + first tag so the release workflow can actually run (B-01); pin the CI
  reusable workflows to a commit SHA instead of `@master` (X-03); drop or configure the
  Modrinth/CurseForge token forwarding (B-03).
- ~~Fix stale URLs (mcmod.info, README → this repo), mcmod.info `dependencies`, README rewrite for
  OCLights3~~ — DONE 2026-07-31.
- Delete the dead-code inventory (ARCHITECTURE § Dead code) — including the jhlabs pruning above.
- Document the dev-env JVM requirement (Gradle JVM 21+; `D:\Minecraft\java` has Temurin 21) — done
  in ISSUES B-04; consider committing `gradle/gradle-daemon-jvm.properties` so Gradle provisions
  the daemon JVM itself.
- Verify CI passes on the revival commits (build run locally: see B-04 / session notes).

## Phase 1 — Correctness & stability

Work through ISSUES in roughly this order; each fix should come with the smallest test or
in-game repro note we can manage (nothing here is runtime-verified yet):

1. **Threading model** (T-01..T-06) — this is the big one and should be designed once, not patched:
   likely shape (stolen from OC, see OC-INTEGRATION prior art): keep callbacks `direct=true` but
   make every mutation append to a synchronized per-GPU command batch; rasterize server-side under
   one lock (or move rasterization to the tick thread); flush once per tick under the same lock;
   `Context.pause()` during NBT save to stop the world for texture encodes (T-03). Client side:
   replace the 1 kHz thread with tick/render-driven replay of a properly synchronized queue.
2. **Crash fixes, cheap and high-value**: G-01 (setPixels off-by-one), G-02 (id validation
   everywhere — introduce one checked `getTexture(id)` helper), G-03, G-12, U-01, U-02, U-03,
   U-04, U-05, U-07; A-01 (accept `Number`, not `Double` — fixes Lua 5.3/5.4 users); G-04, G-05.
3. **Protocol integrity**: P-01 (server assigns texture ids and puts them **on the wire** in
   CreateTexture/Import), P-03 (key reassembly by sender+id, evict on disconnect), P-06 (texUpdate
   after snapshot), P-07 (retrying resync handshake — fold into Phase-2 redesign if it lands
   early), P-05 (send w*h ints).
4. **Server-side validation** (X-01): instanceof + range checks on every C→S handler; whitelist
   the signal names NET_GPUEVENT may inject; decide the fate of `import(address, path)` (X-02) —
   probably remove the raw-disk form and route through OC's filesystem API.
5. **Persistence**: S-01 (break the TTrans loop), S-02 (description packet = geometry only; pixel
   state flows through the sync protocol), S-03 (only the origin tile persists the framebuffer),
   A-06 (markDirty where it matters), A-03 (static MonitorObject with save/load), G-06 (real
   VRAM accounting).

## Phase 2 — Performance rework

Adopt the OC TextBuffer architecture wholesale (OC-INTEGRATION § Prior art):

- **Delta protocol v2**: versioned header (kills P-02's fragility), server-assigned ids (P-01),
  compact binary args (byte[] blobs for pixels/text — F-03), one compressed batch per GPU per tick,
  range- and watch-limited delivery (F-02/P-08).
- **Client-initiated, retried full-state resync** keyed by node address (P-07), replacing
  `sentOnce` + description-packet piggybacking.
- **Dirty tracking**: dirty-rect (or at least dirty-flag) on Texture; `texUpdate` copies only dirty
  rows; renderers upload only when dirty and only the dirty region; per-monitor GL textures instead
  of the shared `dyntex` (F-01, U-06 stride fix — size rgbCache to the actual monitor).
- **Out-of-band texture persistence** (SaveHandler-style, address-keyed) replacing PNG-in-NBT
  (S-02); PNG encode off the server thread with `Context.pause` consistency.
- **Call budgets**: `limit=`/`consumeCallBudget` on draw callbacks so Lua-side spam self-throttles
  like OC's bitblt (A-07).
- **Baseline measurements first**: bytes/tick for a full-screen redraw, draw-thread CPU, upload
  cost — so the rework has numbers to beat (nothing is measured today).

## Phase 3 — Feature completeness

- Monitor GUI border (`corners.png` is already shipped) and external-monitor world texture —
  the two 2013 README TODOs (U-16).
- Honor monitor size properly end-to-end (config or per-block), unblocked by the U-06 fix.
- In-world dragging on external monitors (U-13) and the key_up char situation (U-15).
- Tablet robustness: NBT lazy-init on server (U-02), UUID authority server-side (U-10), TabMesg
  lifecycle (U-11), rewrite `tabletcam.lua` for OpenOS (U-12).
- Decide the lights' fate (U-08): revive (they need icons, the visual effect implemented, and the
  NET_LIGHT sender fixed) or delete. They're the only colored-lighting feature — probably revive
  `light_adv`, drop `light`.
- External-monitor resize content preservation (U-14) — nice-to-have.

## Phase 4 — Lua API extension

Only after the protocol redesign (new opcodes are cheap then):

- `doc=` strings + `limit=` on every callback (A-07); clean Lua errors instead of NPEs (G-09).
- Signals: rename/realign `key_down`/`key_up` with OC conventions (A-07); consider `monitor_drag`
  naming symmetry with OC touch/drag/drop.
- New primitives, in rough order of user value: polygon/filledPolygon (rasterizer code already
  exists, unused), blit modes / alpha compositing control, palette or indexed-color helpers
  (Convert.java has the depth math), text metrics beyond width, clipping rectangle, additional
  filters (jhlabs classes exist — licensing permitting, Phase 0), buffer-to-buffer copy
  (OC bitblt-style), maybe setPixels with packed byte[] instead of tables.
- `getDeviceInfo()` (OC `DeviceInfo`), `Analyzable` for wrench/analyzer output.
- Energy integration decision: `.withConnector()` + per-op cost, configurable off (GTNH packs will
  want it on).

## Standing verification gaps

- Nothing has been runtime-tested in-game yet; ISSUES entries are read-verified only.
- CI status on GitHub for the revival commits unchecked (no Actions runs inspected).
- `gpututorial.lua` "works" per reading, not per execution.
