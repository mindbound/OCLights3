# OpenGPU

OpenGPU is a Minecraft 1.7.10 mod that adds a **GPU peripheral for
[OpenComputers](https://github.com/GTNewHorizons/OpenComputers)**: pixel-graphics rendering to
dedicated screens and multi-block screen walls, driven from Lua — in contrast to OC's built-in
character-cell screens.

It began as a revival fork of the abandoned **OCLights2** (itself an OpenComputers port of
[CCLights2](https://github.com/ds84182/CCLights2) by ds84182), built against the GT New Horizons
fork of OpenComputers. It is no longer that: the inherited pipeline was replaced wholesale by a
new protocol, scene model and renderer, and the original code was removed in full.

**OpenGPU does not migrate OCLights2 worlds, and is not a drop-in replacement for it.** The
legacy blocks and items no longer exist; their ids are abandoned on load. A world that contained
them still loads, without a prompt, but those blocks are gone. Back up any save before opening it
with OpenGPU. The same applies to saves from **OCLights3**, the working name used by early
revival commits.

**Status:** in development, and it makes no compatibility promises yet — the Lua API is still
moving.

## Requirements

- Minecraft 1.7.10 with Forge, and OpenComputers (GTNH fork).
- **Framebuffer objects must be available and enabled.** This is a hard requirement with no
  software fallback. FBOs are vanilla functionality, not a dependency on any other mod, but note
  they can be switched off in Video Settings — if screens stay blank, check there first; the mod
  says which of the two it is in chat and in the log.

## Building

Requires a JVM 21+ to run Gradle (the mod itself targets Java 8 / MC 1.7.10; compile toolchains
are provisioned automatically):

```
./gradlew build
```

## License

[MMPL-1.0](LICENSE.md), continued from CCLights2/OCLights2.
