# OCLights3 — Verified Issues Register

Every entry below was found by a code survey and then **adversarially re-verified against the code**
by an independent audit pass (2026-07-31, tree `24c4ba1`). Verification is by reading (plus a few
empirical JVM checks, e.g. `File.getCanonicalPath` on Windows, `skipBytes(-1)`); nothing has been
runtime-tested in-game yet. One surveyed claim was refuted and is listed at the end.

Severity: **H** = crash/corruption/protocol break reachable in normal play, **M** = wrong behavior,
resource waste, or crash needing mild bad luck, **L** = latent, dead-code, cosmetic, or hazard.

IDs are stable — reference them in commits (e.g. `Fix G-01`).

## T — Threading & concurrency

- **T-01 (H)** — All 39 GPU callbacks are `@Callback(direct=true)` and mutate shared GPU state
  (color/transform/textures) and push onto a plain `ArrayDeque` from OC worker threads, while the
  server tick thread concurrently drains it (`PacketSenders.java:48-50`, `GPU.java:509-519`) and
  clears it. `updateEntity`'s `synchronized(this)` is a lock no callback ever takes
  (TileEntityGPU.java:876-886). A push between `writeInt(size)` and the drain desyncs the packet;
  concurrent deque mutation can corrupt/NPE the tick thread. Multiple computers on one GPU add
  more concurrent writers.
- **T-02 (H)** — Client side: the netty/packet thread appends to `ClientDrawThread.draws` with no
  lock (`PacketHandlerIMPL.java:272-275`) while the draw thread iterates the `WeakHashMap` and polls
  deques under its own monitors — one-sided locking. A `ConcurrentModificationException` escaping
  `run()` kills the thread; the watchdog (266-271) swaps in a new thread **with an empty map**,
  dropping all queued commands.
- **T-03 (H)** — `writeToNBT` PNG-encodes live textures (TileEntityGPU.java:777-790) while executor
  threads draw into the same `BufferedImage` — torn or failed PNG encodes during world save. (OC's
  own solution: `Context.pause()` blocks until the executor task completes — see OC-INTEGRATION.)
- **T-04 (M)** — Full-state snapshots (NET_GPUINIT / NET_GPUDOWNLOAD) are applied on the receiving
  thread, bypassing the draw-thread queue (`PacketHandlerIMPL.java:290-308`): `transformStack.clear()`
  racing a queued `Pop` throws `EmptyStackException`; stale queued draws execute after the snapshot.
- **T-05 (M)** — `renderLock` is a plain non-volatile boolean (Texture.java:40); renderers wait ≤1 ms
  then read `rgbCache` anyway, and `GuiMonitor`'s GL upload sits outside the `synchronized(tex)`
  block (GuiMonitor.java:96-104) — tearing by design.
- **T-06 (M)** — `ClientDrawThread` lifecycle: started from a `PacketHandlerIMPL` *instance
  initializer* assigning a static field (PacketHandlerIMPL.java:56-62); the handler class is
  registered twice (OCLights2.java:67-68) so **two threads start and one is orphaned**; it also runs
  on dedicated servers; 1 ms sleep loop (≈1 kHz) holding the `draws` lock each pass; non-daemon, no
  shutdown path; when `gpu.currentMonitor == null` the deque is left to grow unboundedly while
  packets keep arriving (ClientDrawThread.java:33).

## P — Protocol & sync

- **P-01 (H)** — Client/server texture-id divergence: replayed `CreateTexture`/`Import` carry no
  assigned id; the client's `newTexture()` picks its own first free slot (GPU.java:203-211). Any
  skew (drawlist racing the one-shot GPUDOWNLOAD, NBT-restored server textures) makes every later
  `BindTexture`/`DrawTexture`/`FreeTexture` target the wrong client texture, permanently.
- **P-02 (M)** — The wire format is `CommandEnum.ordinal()` (PacketSenders.java:51) plus
  `Serialize`'s registry array index (Serialize.java:11-23); there is no protocol version field.
  Reordering either is a silent cross-version protocol break (mitigated only by FML's mod-version
  handshake). The `Transelate` typo is wire-frozen until a protocol redesign.
- **P-03 (M)** — `PacketChunker` reassembly is keyed **only by a sender-assigned 1-byte packetId**
  in a static singleton shared by all clients and both sides (PacketChunker.java:14-18, 91-99):
  two clients' multi-chunk uploads interleave into one slot (AIOOBE or corruption); incomplete
  stacks leak on disconnect and later poison that id on wraparound; the HashMap is touched from
  per-connection netty threads unsynchronized.
- **P-04 (L)** — `numChunks` is a byte: payloads over ~4.16 MB compressed overflow negative and
  throw `NegativeArraySizeException` (PacketChunker.java:39-40); `sendTextures`' catch just
  printStackTraces, so the client silently never gets the texture.
- **P-05 (M)** — `sendTextures` allocates `int[w*h*4]` but `getRGB` fills only `w*h` — **4× the
  pixel data is transmitted** (75% zeros) and read back (PacketSenders.java:146-152,
  PacketHandlerIMPL.java:352-356).
- **P-06 (M)** — `recTexture` applies snapshots to `tex.img` but never calls `texUpdate()`, so the
  rendered `rgbCache` stays stale until some later incremental batch — an idle monitor renders
  black/old indefinitely after a full sync; on size mismatch the monitor's texture object is
  orphaned (PacketHandlerIMPL.java:339-357).
- **P-07 (M)** — Full-state request (`GPUDOWNLOAD`) is sent once per client TE instance with no
  retry (`sentOnce`, TileEntityGPU.java:888-891) and the server silently no-ops if the TE lookup
  fails — permanent desync until the TE is recreated. (Compare OC's retry-every-100-ticks
  handshake.)
- **P-08 (M)** — Drawlist and external-monitor-geometry broadcasts use `TargetPoint` range 4096
  (PacketSenders.java:77, 291) — effectively every player in the dimension receives every GPU's
  full command stream every tick.
- **P-09 (L)** — `Serialize` dispatches by exact `Class.equals`: the `Map` registration is
  unreachable (a `HashMap` never matches `Map.class`) and `serialize(null)` writes nothing while
  the reader unconditionally reads a type byte — either desyncs the stream. Latent (no current
  caller sends maps/nulls).
- **P-10 (L)** — Array-marker decoding "rewinds" with `skipBytes(-1)`, which is a no-op on
  `DataInput`; parsing is correct only because the writer emits a separate marker byte — dead,
  misleading code (PacketHandlerIMPL.java:247-256).
- **P-11 (L)** — Chunk payloads are gunzipped **one byte at a time** via `available()`/`read()`
  (PacketHandlerIMPL.java:76-78), appending a spurious `0xFF` at EOF (harmless only because all
  parsers stop early) and costing hundreds of thousands of calls for texture-sized packets.
- **P-12 (L)** — `NET_LIGHT`'s only sender omits the leading type byte, so the payload would be
  dispatched as NET_GPUDRAWLIST; dead in practice (lights unregistered).
  `NET_SYNC` is dead on both ends (empty client case; senders never invoked). `NET_GPUKEY` is
  declared and never used.
- **P-13 (L)** — The same `PacketMessage` class is registered under discriminators 0 (CLIENT) and
  1 (SERVER) on one `SimpleNetworkWrapper` (OCLights2.java:67-68) — works via codec overwrite,
  fragile; side dispatch uses `getEffectiveSide()` instead of `MessageContext.side`.

## G — GPU core correctness

- **G-01 (H)** — `SetPixels` executor off-by-one: callback packs channel data starting at arg 5,
  executor reads from index 4 — the y-coordinate becomes the first red channel and every channel
  shifts by one (TileEntityGPU.java:406-414 vs GPU.java:390-397). Every `setPixels` renders wrong,
  identically on both sides.
- **G-02 (H)** — Texture-id validation is broken across the board: `GPU.bindTexture`'s check
  `texid < 0 && texid >= textures.length` is unsatisfiable (GPU.java:179); several callbacks and
  command cases index `textures[]` unchecked (TileEntityGPU.java:174, 275; GPU.java:321-355, 402,
  476); `export`'s guard uses `> length` so 8192 passes (TileEntityGPU.java:492); the wire-level
  `FreeTexture` accepts slot 0 and nulls the monitor texture (GPU.java:350-356).
- **G-03 (M)** — With no bound texture, `processCommand` silently returns null for **all** commands
  (GPU.java:291-294) — needlessly gating `CreateTexture`/`Import`, whereupon the `createTexture`
  callback NPEs dereferencing the null result (TileEntityGPU.java:137-138). `removeMonitor` never
  clears `bindedTexture`, so post-detach draws land on an orphaned texture instead.
- **G-04 (M)** — `setColor`'s duplicate-color early-out compares blue against the green argument
  and green against blue (TileEntityGPU.java:564): a real change whose G/B mirror the current B/G
  is silently dropped, server-side and on the wire.
- **G-05 (M)** — `flipVertically` is broken: `scale(1,-1)` with no compensating translate maps the
  image off-canvas, drawing the image onto itself mid-flip (Texture.java:310-317; the comment
  admits it).
- **G-06 (M)** — VRAM accounting only gates on `free > 0` *before* allocation, ignoring the request
  size — with 1 unit free, a 4096×4096 request succeeds and allocates a ~64 MB BufferedImage on
  both sides (GPU.java:195-213). `(w*h)/32` integer division prices sub-32-pixel textures at 0.
  `getUsedMemory` skips slot 0.
- **G-07 (M)** — `startFrame` with no `endFrame` suppresses the per-tick flush forever while
  callbacks keep pushing — unbounded `drawlist` growth. The `frame` flag is non-volatile
  cross-thread and not persisted (TileEntityGPU.java:690-694, 882-886).
- **G-08 (L)** — `Texture.getRGB` wraps coordinates with `%` (silently aliasing out-of-range
  reads) and Java's signed `%` sends negative inputs to a raster `ArrayIndexOutOfBoundsException`
  (Texture.java:410-414).
- **G-09 (L)** — Callbacks NPE when no monitor is attached: `plot`, `drawText`, `getPixelColor`
  dereference `bindedTexture`, `getMonitor` dereferences `currentMonitor` (TileEntityGPU.java:197,
  519, 287, 670) — raw NPEs surface into Lua instead of clean errors.
- **G-10 (L)** — `removeMonitor` unconditionally fills the monitor texture black — wiping the
  framebuffer other GPUs still drive (GPU.java:126). `connectToMonitor()` runs every tick on both
  sides, re-querying neighbor TEs even when connected, and its `break` on the first
  already-connected monitor prevents ever connecting a second adjacent monitor
  (TileEntityGPU.java:846-873, 887).
- **G-11 (L)** — `startClick`: `new Random()` per loop iteration, O(n) `containsValue` on the wrong
  map, `clickToDataMap` entries leak (only `endClick` removes), and click state is keyed by player
  display name (TileEntityGPU.java:75-80).
- **G-12 (M)** — `moveClick`/`endClick` unbox a possibly-null map value (TileEntityGPU.java:90,
  104); the server invokes them straight from client packets (move/up without a prior down → NPE
  on the network thread, kicking the player).

## A — Lua API & OC integration

- **A-01 (H)** — Table-argument conversion hard-assumes numbers arrive as `java.lang.Double`
  (`ConvertDouble.java:6-9`, import cast at TileEntityGPU.java:451). The GTNH OC this builds
  against boxes integral Lua numbers as **`Long`** (ExtendedLuaState.scala:108, Lua 5.3/5.4
  architectures), so `setPixels` and `import(table)` throw for every user on those architectures.
  Also NPEs on sparse tables (`m.get(...)` returning null).
- **A-02 (M)** — `import(address, path)`: `new File("/", path).getCanonicalPath()` on Windows
  yields a drive-qualified path (`C:\…`), producing a malformed lookup that **always fails on
  Windows** (empirically verified); traversal containment is a side effect of canonicalization,
  not a check; authorization is node-reachability only; and it reads the save directory directly,
  bypassing OC's filesystem abstraction (works only for disk-backed managed filesystems)
  (TileEntityGPU.java:454-470).
- **A-03 (M)** — `MonitorObject`/`ExternalMonitorObject` are **non-static inner** `AbstractValue`
  subclasses (TileEntityMonitor.java:87-92, TileEntityExternalMonitor.java:484-494). OC persists
  values held by computers via `clazz.newInstance` (UserdataAPI.scala:40) — reinstantiation throws
  for member inner classes, so a computer that saves while holding the value cannot restore it.
- **A-04 (L)** — `export` returns **signed** byte values (-128..-1 for 128-255) while the
  `tablet_image` path uses unsigned 0-255 — the two conventions disagree; `ImageIO.write`'s boolean
  return is ignored, so an unknown format silently yields an empty table
  (TileEntityGPU.java:497-503, PacketHandlerIMPL.java:213-215).
- **A-05 (L)** — The OC node and loot FS are created client-side too (constructor,
  TileEntityGPU.java:67-72), and the description packet loads server node NBT into the client's
  node — harmless today only because OC internally ignores client joins.
- **A-06 (M)** — No callback (nor the RAM-upgrade path in `BlockGPU.onBlockActivated`) ever calls
  `markDirty()` — zero matches in src — so GPU state changes don't flag the chunk for saving;
  persistence depends on something else dirtying the chunk.
- **A-07 (M)** — The mod emits signals named `key_down`/`key_up` with a different argument shape
  than OC's standard keyboard signals — a collision that confuses OpenOS-side event handling.
  No `@Callback` uses `doc=` or `limit=` anywhere (no in-game docs, no call budget).

## S — Persistence & description packets

- **S-01 (H)** — `TileEntityTTrans` has a self-perpetuating description-packet loop: `writeToNBT`
  sets `update=true`, `getDescriptionPacket` calls `writeToNBT`, `updateEntity` marks the block
  for update whenever `update` is true — an endless once-per-tick S35 broadcast **each embedding
  the full 512×288 framebuffer as PNG** while anyone watches the chunk (TileEntityTTrans.java:94,
  98-102, 183-187).
- **S-02 (M)** — `TileEntityGPU.getDescriptionPacket` reuses the full `writeToNBT` — every texture
  PNG-encoded on the server thread for every chunk-watch/block-update — duplicating the
  GPUDOWNLOAD path and risking 1.7.10 packet-size limits (TileEntityGPU.java:749-793; worst case
  8191 textures). Oversized TE NBT is also a chunk-save corruption risk.
- **S-03 (M)** — Every tile of a merged external monitor serializes the **entire shared**
  framebuffer PNG into its own NBT (propogateTerminal shares one Monitor; TileEntityMonitor
  .writeToNBT encodes it) — a max 16×9 wall stores the same 512×288 PNG up to 144 times per
  chunk save.
- **S-04 (M)** — `TileEntityExternalMonitor.updateEntity` runs unguarded on the client and calls
  server-side packet sends (`ExternalMonitorUpdate` → `sendToAllAround`) when the client-side
  `dirty` flag is set by `onDataPacket` (TileEntityExternalMonitor.java:466-473).

## U — GUI, content, items

- **U-01 (H)** — `GuiTablet`: the out-of-range branch draws "Out of range." onto the **shared
  static** `TabletRenderer.defaultTexture` (defacing every unconfigured tablet until restart)
  instead of `errorTexture`; in that state `mon` is null so `mouseClicked` NPEs; and `keyRelease`
  lacks `keyTyped`'s `canDisplay` guard, so releasing a key with an unpaired tablet GUI open
  crashes the client (GuiTablet.java:27, 53-57, 157, 199-205).
- **U-02 (H)** — `ItemTablet` NPEs on tablets without NBT (creative-picked / commands): line 41
  dereferences `getTagCompound()` unchecked; only crafting (`onCreated`) initializes NBT
  (ItemTablet.java:41, 100-103). Compounded by U-03's fallthrough.
- **U-03 (M)** — `GuiHandler` case 0 has no `break`: if the TE at the coords isn't a
  `TileEntityMonitor`, it falls through to case 1 and constructs `GuiTablet` from
  `player.getHeldItem().getTagCompound()` — NPE with an empty hand (GuiHandler.java:22-31).
- **U-04 (M)** — `GuiMonitor` drag sends the **previous frame's Y** (`mly` updated after the send,
  GuiMonitor.java:80-87); `GuiTablet` has the corrected line, confirming the copy bug.
- **U-05 (M)** — `TileEntityTTrans.invalidate()/validate()` never call super (TE never marked
  invalid); the Lua-facing `disconnect()` calls `invalidate()` as app logic and pushes "unload"
  instead of "disconnect"; the correct `onRemove()` has no callers; `getTabletUUID(index)` is
  0-based and unchecked (TileEntityTTrans.java:38-63, 190-213).
- **U-06 (M)** — Monitor geometry is hardcoded 512×288 end-to-end: fixed 576 KB `rgbCache` per
  Monitor **regardless of actual size** (even 32×32 tiles), `texUpdate` stride hardcoded to 512,
  all upload paths hardcode 512×288; the `MonitorSize` config is dead end-to-end (parsed, never
  applied; NET_SYNC dead; TileEntityMonitor hardcodes 256×144) (Monitor.java:26,41;
  Texture.java:600; Config.java:26).
- **U-07 (M)** — `Config.loadConfig` runs before the logger is assigned (OCLights2.java:55-56);
  a malformed `MonitorSize` string NPEs (or NumberFormatExceptions) during preInit.
- **U-08 (L)** — Light blocks are unregistered *and* internally broken: icon never assigned +
  wrong texture path; `color > 16` off-by-one admits 16 for a 16-entry palette; the visual-effect
  call is commented out (ColorLight) / NPE-prone during chunk load (Advancedlight `worldObj.provider`);
  unguarded 4096-radius broadcast every 20 ticks (CommonProxy.java:53-66 and the two TEs/blocks).
- **U-09 (L)** — RAM combine recipes exclude any pair containing 1K RAM and can mint 9K/10K items
  beyond the 0-7 subtype range; the base recipe declares an `'L'` key absent from its pattern
  (CommonProxy.java:122-133).
- **U-10 (L)** — Tablet UUID can be minted independently on client and server (lazy `createNBT`
  from both sides), so pairing may reference different identities until container sync
  (ItemTablet.java:84, TabletRenderer.java:114).
- **U-11 (L)** — `TabMesg` static maps are never cleaned: tablet-keyed message stacks only grow
  (nothing pops them), and state survives world unload for the JVM lifetime (TabMesg.java:8-9).
- **U-12 (L)** — `bin/tabletcam.lua` is ComputerCraft code (`peripheral.wrap`, `os.pullEvent`) —
  cannot run on OpenOS.
- **U-13 (L)** — In-world external-monitor clicks fire `startClick`+`endClick` in the same tick —
  no in-world drag (BlockExternalMonitor.java:103-107). Design gap, GUI-only drag.
- **U-14 (L)** — External-monitor "terminal preservation" is vestigial: `rebuildTerminal` ignores
  its parameter, `m_connections` is never incremented, resize always wipes content.
- **U-15 (L)** — Misc: TESR allocates a `ModelExternalMonitor` every frame
  (TileEntityExternalMonitorRenderer.java:96-99); unchecked TE casts in BlockExternalMonitor
  break/activate paths; screenshot hook ignores `RenderTickEvent.phase` (fires twice) and
  re-renders the whole world; `key_up` events carry char 0 (LWJGL limitation, old TODO).
- **U-16 (L)** — README-era feature gaps confirmed real: no monitor GUI border (`corners.png`
  shipped, unused) and no external-monitor world texture (`renderWorldBlock` returns false).

## F — Performance (structural)

- **F-01 (M)** — No dirty tracking or double buffering anywhere: full `rgbCache` recopy per client
  batch, full 512×288 GL upload per renderer per frame into one shared `DynamicTexture` GL id.
- **F-02 (M)** — Whole-dimension broadcast of every GPU's command stream every tick (= P-08).
- **F-03 (M)** — Wasteful encodings: `Byte[]` args serialized element-wise with a type byte per
  element (2× pre-gzip); `export`/`tablet_image` marshal images as `HashMap<Double,Double>` per
  byte (~80-100 bytes overhead per payload byte); text as one `Character` arg per glyph.
- **F-04 (L)** — 1 kHz client draw-thread busy loop; per-tick neighbor scans; per-frame model
  allocation (= T-06, G-10, U-15).

## X — Security-relevant

- **X-01 (H)** — Server packet handlers trust client-supplied coordinates with unchecked casts
  (`ClassCastException` remote crash vector at PacketHandlerIMPL.java:107, 150, 189, 207) and
  NET_GPUEVENT lets any client inject **arbitrary named signals** into every attached computer
  (event name read straight from the packet, 146-183). No range/permission checks anywhere on the
  input path.
- **X-02 (M)** — `import(address, path)` exposes any reachable filesystem's on-disk directory to
  read, with no access-rights check (= A-02).
- **X-03 (M)** — CI delegates to `FalsePattern/fpgradle-workflows@master` (mutable ref) and
  forwards Maven/Modrinth/CurseForge secrets to whatever that branch contains — non-reproducible
  CI + secret exposure (build-and-test.yml:13, release-tags.yml:13-20).

## B — Build, identity, metadata

- **B-01 (M)** — No version is defined anywhere and the repo has zero git tags — the tag-driven
  release workflow has never run and FPGradle's version derivation has nothing to describe.
- **B-02 (M)** — ~~Identity is still `OCLights2` throughout~~ **FIXED 2026-07-31**: modid renamed
  to `OCLights3` (buildscript + rootProject.name + lang keys + mcmod.info + README); old-world
  block/item ids remapped via `FMLMissingMappingsEvent` (OCLights2.java) and TE ids re-registered
  as `OCLights3:*` with legacy alternatives (`GPU`, `OCLMonitorTE`, `OCLBigMonitorTE`,
  `OCLTTransTE`) — which also cures the bare-`GPU` collision hazard. Residual: Java package stays
  `ds.mods.OCLights2` for now (rename = separate refactor decision); old `OCLights2.cfg` config
  values are not migrated (config is nearly dead, U-06); disabled light blocks from pre-2015
  worlds are intentionally not remapped.
- **B-03 (L)** — Modrinth/CurseForge tokens are forwarded by CI but no corresponding publish
  config exists in the buildscript; ~~`mcmod.info` declares empty `dependencies`~~ (fixed
  2026-07-31).
- **B-04 (info)** — Dev-env note: FPGradle 3.3.0 requires the **Gradle JVM to be 21+** (system
  default Java 8 and even JDK 17 fail with clear errors). Local JVMs live in `D:\Minecraft\java`
  (Temurin JRE 8 + JRE 21); compile toolchains are auto-provisioned via foojay. **Build verified**
  2026-07-31 with the Temurin JRE 21: `BUILD SUCCESSFUL`, jar/dev/sources artifacts produced,
  only unchecked-operations compiler warnings. FPGradle 4.0.2 is available (upgrade candidate).

## Refuted during audit

- `Texture.dispose()/finalize()` double-dispose NPE — **refuted**: `dispose()`'s only caller is
  `resize()`, which immediately recreates the graphics/img, so the claimed finalizer NPE has no
  reachable trigger in this codebase.

## Upstream (GTNH OC) bugs found in passing

See [OC-INTEGRATION.md](OC-INTEGRATION.md#upstream-gtnh-oc-quirks-found-while-surveying) — three
confirmed reference-repo bugs (setMaximumResolution width/width, transposed GpuTextBuffer viewport
accessors, `h > mw` resolution validation) worth reporting upstream.
