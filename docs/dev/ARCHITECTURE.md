# OpenGPU — Architecture / Codebase Map

State of the codebase as inherited (basdxz OCLights2 fork tip, commit `24c4ba1`). Written from a
full-code survey (2026-07-31); all file:line references are against that tree. Defects discovered
during the survey live in [ISSUES.md](ISSUES.md); this doc describes *how it works today*, including
warts, because the wire format and enum ordering are load-bearing.

## Big picture

```
Lua program (OC computer)
   │  component.ocl_gpu.*          (39 @Callback(direct=true) methods)
   ▼
TileEntityGPU ──────────────► GPU.processCommand()      [server, OC worker thread]
   │  builds DrawCMD              rasterizes immediately into Texture (java.awt)
   │  pushes to gpu.drawlist
   ▼
updateEntity() per tick          [server tick thread]
   │  GPU.processSendList → PacketSenders.sendPacketsNow
   │  serialize DrawCMDs → gzip → chunk (≤127×~32KB) → broadcast r=4096
   ▼
PacketHandlerIMPL.ClientSide     [client netty/tick thread]
   │  decode DrawCMDs → append to ClientDrawThread.draws (per-GPU deque)
   ▼
ClientDrawThread (~1 kHz loop)   [dedicated client thread]
   │  replay via client GPU.processCommand → Texture.texUpdate() → rgbCache
   ▼
GuiMonitor / GuiTablet / TESR    [render thread]
      full 512×288 glTexSubImage upload of rgbCache, every frame
```

The same `GPU`/`Texture`/`Monitor` classes run on both sides; the server is authoritative and the
client replays the command stream. Query callbacks (`getPixelColor`, `getSize`, `export`, …) never
enter the drawlist — they read server state directly in the callback.

## Source layout

| Package / path | Role |
|---|---|
| `opengpu` | Mod entry (`OpenGPU.java`), `CommonProxy` (registration), `Config`, `GuiHandler`, `CommandEnum` (wire opcodes), `ClientDrawThread` |
| `opengpu.gpu` | Core: `GPU` (command interpreter + state), `Texture` (java.awt rasterizer), `Monitor`, `DrawCMD` |
| `opengpu.block` (+`.tileentity`) | Blocks: GPU, Monitor, ExternalMonitor (multiblock), TabletTransceiver, 2 disabled lights; TEs incl. `TileEntityGPU` (the OC component) |
| `opengpu.network` | `PacketHandler(IMPL)`, `PacketSenders`, `PacketChunker` (gzip+split) |
| `opengpu.serialize` | Type-tagged value codec for DrawCMD args on the wire |
| `opengpu.converter`, `.utils` | Lua-value coercion helpers, color-depth math, `TabMesg` tablet bus |
| `opengpu.client` | Proxies, GUIs (`GuiMonitor`, `GuiTablet`), renderers (TESR, `TabletRenderer`), `ClientTickHandler` (screenshot hook) |
| `opengpu.jhlabs.image` | 12 bundled Huxtable filters; **only `BoxBlurFilter` is used** (Blur opcode, radius 2) |
| `src/main/resources/assets/oclights` | Textures, lang (en_US, nb_NO), shipped Lua (`lib/gpu.lua`, `bin/gpututorial.lua`, `bin/tabletcam.lua`, `.autorun.lua`) |

## GPU core

- **Command model** — every mutation is a `DrawCMD` (`CommandEnum` + boxed `Object[]` args).
  `GPU.processCommand()` (GPU.java:287-493) is the single dispatch used by both server execution and
  client replay. Draw order is preserved end-to-end (callbacks `addFirst`, sender `removeLast`,
  client `addLast`, drainer `poll` → FIFO).
- **Rasterization** — `Texture` wraps a `BufferedImage` (TYPE_INT_ARGB) + `Graphics2D`; ops apply the
  GPU's shared `AffineTransform` (translate/rotate/scale + push/pop stack), set color, draw, reset.
  Texture blits are color-tinted per channel via `RescaleOp` (Texture.java:356-359).
- **Text** — 6×8 glyphs sampled from `assets/oclights/textures/gui/ascii.png` with vanilla-style
  `charWidth` metrics (Texture.java:87-147, 506-531). Strings are exploded into per-`Character`
  DrawCMD args.
- **Monitors** — `Monitor` = dimensions + backing `Texture` + list of attached GPUs.
  `GPU.setMonitor` aliases **texture slot 0** to the monitor texture (GPU.java:133-144): drawing to
  slot 0 is drawing to the screen. The GPU auto-attaches to any adjacent `TileEntityMonitor` every
  tick (`connectToMonitor()`, TileEntityGPU.java:846-873).
- **Memory model** — 8192 texture slots (0 reserved); `maxmem = 8192` "units" where a texture costs
  `(w*h)/32` units → a 262,144-pixel budget. Accounting rescans slots 1+ on demand and only gates on
  `free > 0` before allocating (see ISSUES G-06). RAM items add `1024*(damage+1)` units via
  right-click on the GPU block, refunded on break (BlockGPU.java:39-96).

### Wire opcodes (`CommandEnum.ordinal()` — **enum order is the network protocol**)

| # | Opcode | Args |
|---|---|---|
| 0 | Fill | — (fills bound texture with current color) |
| 1 | Plot | x, y |
| 2 | CreateTexture | w, h → texid (-1 no memory, -2 no slots) |
| 3 | DrawTexture | `[0, texid, x, y]` full blit / `[1, texid, x, y, tx, ty, w, h]` sub-rect |
| 4 | DrawText | x, y, char… |
| 5 | BindTexture | texid |
| 6 | FreeTexture | texid |
| 7 | Line | x1, y1, x2, y2 |
| 8 / 9 | Rectangle / FilledRectangle | x, y, w, h |
| 10 / 11 | Triangle / FilledTriangle | x1..y3 |
| 12 / 13 | Oval / FilledOval | cx, cy, w, h (center-anchored) |
| 14 | SetPixels | 0, w, h, x, y, then w*h*4 channel ints (executor off-by-one — ISSUES G-01) |
| 15 | FlipVertically | texid (broken — ISSUES G-05) |
| 16 | Import | Byte[] image bytes → ImageIO decode → new texture |
| 17 | Transelate *(sic — typo is wire-frozen)* | dx, dy |
| 18 / 19 | Rotate / RotateAround | r / r, x, y |
| 20 | Scale | sx, sy |
| 21 / 22 | Push / Pop | transform stack |
| 23 | Blur | texid (radius-2 box blur in place) |
| 24 | ClearRectangle | x, y, w, h (hard set, no alpha blend) |
| 25 | Origin | reset transform |
| 26–34, 36–38 | GetFreeMemory … EndFrame | **no processCommand case** — query/control callbacks that never enter the drawlist; entries exist only to keep later ordinals stable |
| 35 | SetColor | r, g, b [, a] |

## Network protocol

One FML `SimpleNetworkWrapper` channel; a single opaque `PacketMessage` (`[int len][bytes]`)
registered under discriminators 0 (CLIENT) and 1 (SERVER). First body byte = packet type;
dispatch via `FMLCommonHandler.getEffectiveSide()` into static `ServerSide`/`ClientSide` switches
(PacketHandlerIMPL.java:43-53). There is **no protocol version field anywhere**.

| # | Name | Dir | Payload / notes |
|---|---|---|---|
| 0 | NET_GPUDRAWLIST | S→C | `[x][y][z][count]` then per cmd `[ordinal][argc]` per arg `[marker 0|-1][Serialize value]`; gzip+chunked; broadcast r=4096, per tick |
| 1 | NET_GPUEVENT | C→S | `[x][y][z][UTF name][argc][typed args]` → `Context.signal` on all attached computers (server prepends node address). Carries scroll + key_down/key_up |
| 2 | NET_GPUDOWNLOAD | C→S req / S→C resp | request `[x][y][z]`; response per texture `[x][y][z][texid][w][h][len][len ints ARGB]` (len erroneously w\*h\*4 — ISSUES P-07) |
| 3 | NET_GPUMOUSE | C→S | sub 0=down(button,mx,my) 1=move(mx,my) 2=up |
| 4 | NET_GPUKEY | — | declared, never used |
| 5 | NET_GPUTILE | S→C | external-monitor multiblock geometry (w,h,xIdx,yIdx,dir); on dirty or every 12000 ticks |
| 6 | NET_GPUINIT | S→C | color, 6-double transform, transform stack (reply to GPUDOWNLOAD, sent before textures) |
| 7 | NET_LIGHT | S→C | rgb floats for advanced light — dead: only sender omits the type byte, block unregistered |
| 8 | NET_SPLITPACKET | both | `[numChunks][chunkIdx][packetId][chunk]`; payload = gzip of a full inner message. Reassembly keyed by 1-byte packetId in a **shared static** chunker (ISSUES P-04) |
| 9 | NET_SYNC | S→C | monitor size — dead on both ends |
| 10 | NET_SCREENSHOT | C→S | JPEG (q=0.5) from tablet sneak-click → `tablet_image` signal |

`Serialize` (serialize/Serialize.java) tags values by **registry array index**: 0=Byte 1=Short
2=Integer 3=Float 4=Double 5=Long 6=Map 7=GPU(as tile coords) 8=String 9=Character. The index
order is protocol-frozen exactly like `CommandEnum`. Dispatch is exact-class `equals`, so the Map
entry is unreachable in practice (ISSUES P-10). `Byte[]` array args (Import) are serialized
element-wise with a type byte per element — 2× inflation pre-gzip (ISSUES F-03).

**Full-state sync** is client-pulled: on its first tick a client `TileEntityGPU` sends
NET_GPUDOWNLOAD exactly once (`sentOnce`, no retry); the server replies NET_GPUINIT + one
NET_GPUDOWNLOAD per non-null texture. Separately, `getDescriptionPacket` reuses the **full**
`writeToNBT` — including every texture PNG-encoded — on the vanilla S35 path (ISSUES S-01/S-02).

## Client pipeline

- `ClientDrawThread` ("OCLights2 Draw Thread"): singleton started from a `PacketHandlerIMPL`
  *instance initializer*; `WeakHashMap<GPU, ArrayDeque<DrawCMD>>`; loops with 1 ms sleeps, replays
  commands under a 5-deep synchronized pyramid, then `Texture.texUpdate()` recopies the whole image
  into `rgbCache` — a fixed `int[512*288]` with a hard-coded 512 scanline stride (Monitor.java:26,41;
  Texture.java:596-602).
- Renderers (`GuiMonitor`, `GuiTablet`, `TileEntityExternalMonitorRenderer`, `TabletRenderer`) wait
  ≤1 ms on a non-volatile `renderLock` boolean, then upload the **entire** 512×288 rgbCache into a
  shared `DynamicTexture` GL id (`TabletRenderer.dyntex`) **every frame**. There is no double
  buffering and no dirty tracking anywhere in the pipeline.
- Snapshot packets (NET_GPUINIT / NET_GPUDOWNLOAD responses) are applied immediately on the receiving
  thread, bypassing the draw-thread queue — an ordering hole (ISSUES T-04).

## Input pipeline

- **Monitor GUI** (right-click plain monitor, GUI id 0) and **Tablet GUI** (GUI id 1): mouse
  down/move/up → NET_GPUMOUSE; scroll → `monitor_scroll` and keyboard → `key_down`/`key_up` via
  NET_GPUEVENT. Server looks up the TE at client-supplied coords (unchecked — ISSUES X-01) and fans
  out to every attached GPU: `startClick`/`moveClick`/`endClick` allocate a per-player click id and
  `Context.signal()` `monitor_down`/`monitor_move`/`monitor_up` (address, x, y, button, id) into
  every attached computer.
- **In-world external monitor click**: face hit → global pixel → instant `startClick`+`endClick`
  pair (no in-world drag; drag exists only in GUIs).
- **Tablet screenshot**: sneak-right-click → next render tick re-renders the world, `glReadPixels`,
  JPEG q=0.5 → NET_SCREENSHOT → server converts bytes to `HashMap<Double,Double>` → `tablet_image`
  signal.

## Threading model (as-is)

| Thread | Touches |
|---|---|
| OC executor (worker) threads | all 39 `direct=true` callbacks: mutate GPU color/transform/textures, rasterize, push to `drawlist` |
| Server tick thread | `updateEntity`: drains/clears `drawlist`, `connectToMonitor()`, NBT saves (PNG-encodes textures) |
| Netty handler threads | server: click handlers, chunk reassembly; client: enqueue DrawCMDs |
| ClientDrawThread | client-side replay + `texUpdate` |
| Render thread | GL uploads, GUI input capture |

Almost none of these handoffs are correctly synchronized — the full inventory is ISSUES section T.

## Persistence

`TileEntityGPU.writeToNBT` saves: OC node (`oc:node`), loot-FS node (`oc:fsnode`), RAM upgrade
counts, `vram`, **every texture PNG-encoded** into a `textures` compound, bound slot, color
(TileEntityGPU.java:761-793). `readFromNBT` mirrors it; vram only ratchets upward from the 8192
default. Monitors likewise PNG-round-trip their framebuffer; every tile of a merged external monitor
serializes the whole shared framebuffer (ISSUES S-03). OC prior art for doing this properly
(out-of-band SaveHandler, address-keyed) is documented in
[OC-INTEGRATION.md](OC-INTEGRATION.md#prior-art-ocs-own-screen-sync).

## Content inventory

Registered: `BlockGPU` (OCLGPU), `BlockMonitor` (OCLMonitor, 256×144), `BlockExternalMonitor`
(OCLBigMonitor, self-assembling multiblock ≤16×9 blocks @32×32 px = 512×288 max),
`BlockTabletTransceiver` (OCLTTrans, component `tablet_transceiver`, 512×288), `ItemRAM` (subtypes
1K–8K), `ItemTablet` (pairs to a transceiver by UUID; live remote screen within 10 blocks).
TE registration ids: `GPU`, `OCLMonitorTE`, `OCLBigMonitorTE`, `OCLTTransTE` (+`OCLLight`,
`OCLAdvLight` for the disabled lights). The bare id `GPU` is a cross-mod collision hazard.

Disabled/dead content: `BlockColorLight` ("light") and `BlockAdvancedLight` ("light_adv") —
registration commented out (CommonProxy.java:53-66) and internally broken (ISSUES U-10).

Shipped Lua (mounted as read-only OC loot filesystem "ocl_gpu" per GPU): `.autorun.lua` (symlinks
into the OS), `lib/gpu.lua` (component proxy + `importFile`), `bin/gpututorial.lua` (working
tutorial), `bin/tabletcam.lua` (**broken** — ComputerCraft-era `peripheral.wrap`/`os.pullEvent`).

## Dead code inventory (candidates for deletion)

- 11 of 12 jhlabs filter classes (all but `BoxBlurFilter`) — also a licensing question (ROADMAP).
- `Texture.temp` static 512×512 texture; `Texture.polygon`/`filledPolygon` (never called;
  `filledPolygon` draws an outline anyway); NET_GPUKEY; NET_SYNC + `Config.monitorSize` +
  `playerLoggedIn`/`clientLoggedIn`; NET_LIGHT path; `ConvertString`; `PacketChunker.createPackets`'s
  unused `channel` param; commented-out `equals(SimpleComponent)` stubs in four TEs;
  `TileEntityExternalMonitor.rebuildTerminal`/`m_connections` (vestigial no-ops);
  `GuiMonitor.initGui`'s unreachable "OpenGL texture setup failed!" check; Config's 1.6-era block IDs.

## Build & lineage

FPGradle (`com.falsepattern.fpgradle-mc` 3.3.0), Gradle 9.2.1 wrapper, JDK auto-provisioned via
foojay (Gradle itself needs JVM 17+; compiled output targets MC 1.7.10/Java 8). `Tags` class is
generated at build time into `opengpu` (tokenClass config) — no `Tags.java` in tree, by
design. Dependency: `com.github.GTNewHorizons:OpenComputers:1.12.8-GTNH` (`:api` compileOnly,
`:dev` runtimeOnly). CI: two thin workflows delegating to `FalsePattern/fpgradle-workflows@master`;
release fires on tag push (no tags exist yet; no version is defined anywhere — ISSUES B-02).

History (183 commits): CCLights2 by ds84182 2013 (MC 1.5/1.6.4, ComputerCraft) → OC port by gamax92
2014-08 → 1.7.10 port 2014-11 → last feature work 2015-01 → dormant → basdxz revival 2025-12-19
(exactly two commits: CRLF→LF + FPGradle buildscript). The OC port dropped CC-specific machinery
(`PeripheralProvider`, CC mounts → OC loot FS) but carried the GPU core, network stack, and the
README TODO list verbatim from 2013. License: MMPL-1.0 since 2013 (provenance concerns → ROADMAP
open questions).
