# OCLights 3

OCLights 3 is an open-source Minecraft 1.7.10 mod that adds a **GPU peripheral for
[OpenComputers](https://github.com/GTNewHorizons/OpenComputers)**: pixel-graphics rendering to
dedicated monitors, multi-block external monitors, and remote tablets — in contrast to OC's
built-in character-cell screens.

It is a revival fork of the abandoned **OCLights2** (itself an OpenComputers port of
[CCLights2](https://github.com/ds84182/CCLights2) by ds84182), built against the GT New Horizons
fork of OpenComputers. Worlds from OCLights2 are remapped automatically.

**Status:** early revival. See [docs/dev](docs/dev/README.md) for the project charter,
architecture notes, the verified issues register, and the roadmap.

## Building

Requires a JVM 21+ to run Gradle (the mod itself targets Java 8 / MC 1.7.10; compile toolchains
are provisioned automatically):

```
./gradlew build
```

## License

[MMPL-1.0](LICENSE.md), continued from CCLights2/OCLights2.
