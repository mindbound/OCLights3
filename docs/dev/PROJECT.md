# OpenGPU — Project Charter

## What this is

OpenGPU (formerly OCLights3) is a revival fork of **OCLights2**, an abandoned Minecraft 1.7.10 mod that adds a
**GPU peripheral for OpenComputers**: a component that renders pixel-graphics draw commands to
monitors, in contrast to OpenComputers' built-in character-cell screens.

## Lineage

| Project | Author | Platform | Status |
|---|---|---|---|
| [CCLights2](https://github.com/ds84182/CCLights2) | ds84182 | ComputerCraft | abandoned |
| OCLights2 | ds84182 et al. | OpenComputers port | abandoned |
| [OCLights2 (basdxz fork)](https://github.com/basdxz/OCLights2) | basdxz | GTNH OpenComputers + FPGradle build | starting point — this repo's history |
| **OpenGPU** | this project (interim name OCLights3) | GTNH OpenComputers | active |

The repo currently *is* the basdxz OCLights2 fork; OpenGPU work happens on top of it.

## Upstream / reference repos

- **GTNH OpenComputers** — the OC fork we build against; the most stable and actively
  maintained OC lineage. <https://github.com/GTNewHorizons/OpenComputers>
  - Local reference clone: `C:\Users\astro\Downloads\OpenComputers-GTNH` (shallow)
- **CCLights2** — the original ComputerCraft ancestor, useful for lineage archaeology.
  <https://github.com/ds84182/CCLights2>
  - Local reference clone: `C:\Users\astro\Downloads\CCLights2` (shallow)

## Build target

- Minecraft 1.7.10 (Forge), via [FPGradle](https://github.com/FalsePattern/fpgradle) (`fpgradle-mc` plugin)
- `compileOnly com.github.GTNewHorizons:OpenComputers:1.12.8-GTNH:api`
- CI: GitHub Actions (`build-and-test`, `release-tags`)

## Goals, in priority order

1. **Fix issues in the original code** — bugs, crashes, thread-safety, protocol problems.
2. **Rework for performance** — especially network sync (the original README's standing TODO)
   and the draw/render pipeline.
3. **Feature-completeness** — finish half-done features (monitor GUI borders, external monitor
   textures, etc.) and make existing ones robust.
4. **Extend the Lua-facing API** — richer draw primitives and quality-of-life functions.

Explicitly *not* a priority right now: brand-new OC components/blocks. Possible later.

## Open questions (decide before/during Phase 0 — see ROADMAP.md)

1. ~~**Identity**~~ — DECIDED 2026-07-31: renamed to modid `OCLights3`, with save compat preserved
   via `FMLMissingMappingsEvent` remapping (blocks/items) and
   `registerTileEntityWithAlternatives` (TE ids, also fixing the bare `GPU` id hazard).
   **Superseded 2026-08-01**: final identity is `OpenGPU` (modid `OpenGPU`, package root
   `opengpu.*`); the remap accepts both `OCLights2:*` and interim `OCLights3:*` ids, and legacy
   configs are migrated on first launch.
2. **Licensing/provenance** — DEFERRED while the project is private/internal-use (redistribution
   obligations don't attach). **Publish gate**: before any public release, run the licensing
   checklist in ROADMAP Phase 0 (jhlabs prune + Apache-2.0 attribution; `ascii.png` provenance
   check/replacement). Repo license stays MMPL-1.0.
3. ~~**OC pin**~~ — DECIDED 2026-07-31: bumped to `1.12.55-GTNH` (current).

## Status

- 2026-07-31 — full codebase survey + adversarial audit complete; work docs created; build of the
  inherited tree verified (`BUILD SUCCESSFUL` with Gradle JVM = Temurin JRE 21 from
  `D:\Minecraft\java`; see ISSUES B-04).
- 2026-07-31 — modid renamed `OCLights2` → `OCLights3` (with world remapping), OC dependency
  bumped `1.12.8-GTNH` → `1.12.55-GTNH`, stale URLs/README/mcmod.info refreshed.
- 2026-08-01 — final identity settled: `OCLights3` → `OpenGPU` (modid `OpenGPU`, package root
  `opengpu.*` — legacy at `opengpu.`, new core at `opengpu.v2.`; rootProject `OpenGPU`); remap
  accepts both legacy id domains; legacy config migrated on first launch.

## Work docs

Engineering docs live in `docs/dev/`:

- `PROJECT.md` — this charter
- `ARCHITECTURE.md` — codebase map and data flow
- `OC-INTEGRATION.md` — how the mod talks to the OpenComputers API; GTNH OC API notes
- `ISSUES.md` — verified defects and weaknesses, with code evidence
- `ROADMAP.md` — phased work plan
