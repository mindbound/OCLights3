# OCLights3 engineering docs

Created 2026-07-31 from a full survey of the inherited codebase (tree `24c4ba1`), the GTNH
OpenComputers API, and the CCLights2 ancestry. Keep these current as the code changes.

| Doc | What's in it |
|---|---|
| [PROJECT.md](PROJECT.md) | Charter: what OCLights3 is, lineage, goals, open questions |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Codebase map, draw pipeline, wire protocol & opcode tables, threading model, content + dead-code inventory |
| [OC-INTEGRATION.md](OC-INTEGRATION.md) | Current OC integration, full Lua API reference, correct GTNH OC component patterns, OC screen-sync prior art, upstream quirks |
| [ISSUES.md](ISSUES.md) | ~70 verified defects with file:line evidence, adversarially audited; stable IDs (T/P/G/A/S/U/F/X/B) |
| [ROADMAP.md](ROADMAP.md) | Phased plan: identity & provenance → correctness → performance rework → completeness → API extension |

Reference clones (shallow): GTNH OpenComputers at `C:\Users\astro\Downloads\OpenComputers-GTNH`
(with the pinned `1.12.8-GTNH` tag fetched), CCLights2 at `C:\Users\astro\Downloads\CCLights2`,
HBM's Nuclear Tech at `C:\Users\astro\Downloads\Hbm-s-Nuclear-Tech-GIT` (1.7.10 rendering/code
tricks reference).

Build note: the Gradle JVM must be 21+ (FPGradle 3.3.0). Verified working:
`JAVA_HOME=D:\Minecraft\java\eclipse_temurin_jre21.0.8+9 ./gradlew.bat build` → BUILD SUCCESSFUL,
producing `build/libs/OCLights2-mc1.7.10-<git-describe>[-dev|-sources].jar`. FPGradle 4.0.2 is
available (we're on 3.3.0) — upgrade is a Phase-0 candidate.
