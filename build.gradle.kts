plugins {
    id("com.falsepattern.fpgradle-mc") version "3.3.0"
}

group = "opengpu"

minecraft_fp {
    mod {
        modid   = "OpenGPU"
        name    = "OpenGPU"
        rootPkg = "opengpu"
    }

    tokens {
        tokenClass = "Tags"
    }

    publish {
        maven {
            repoUrl = "https://mvn.ventooth.com/releases"
            repoName = "venmaven"
        }
    }
}

repositories {
    exclusive(horizon(), "com.github.GTNewHorizons")
    mavenCentral()
}

dependencies {
    compileOnly("com.github.GTNewHorizons:OpenComputers:1.12.55-GTNH:api") {
        excludeDeps()
    }
    runtimeOnly("com.github.GTNewHorizons:OpenComputers:1.12.55-GTNH:dev") {
        excludeDeps()
    }
    // OpenComputers' own coremod transformer (li.cil.oc.common.asm.ClassTransformer) resolves
    // GTNHLib's ClassConstantPoolParser in its constructor, which LaunchWrapper instantiates
    // before any mod class loads. excludeDeps() above strips OC's transitive tree, so without
    // this the server dies at launch with NoClassDefFoundError — invisible to `gradlew build`
    // (compileOnly against the API needs none of it) and only reachable by actually starting
    // the game, which is why CI caught it and local builds never did.
    // Version tracks what OpenComputers 1.12.55-GTNH declares; bump it with the OC pin.
    //
    // NOTE: no excludeDeps() here, deliberately. GTNHLib is a coremod that cascades a Mixin
    // tweaker, so it needs its own declared tree at launch — unimixins above all. Excluding it
    // reproduced the original failure one layer down (ClassNotFoundException: MixinTweaker).
    // Its deps are plain libraries (unimixins, fastutil, joml, brigadier, GTNHExtLib) with no
    // Minecraft or Forge among them, so resolving them is safe; that is NOT true of the OC
    // artifacts above, where the exclusion is load-bearing.
    runtimeOnly("com.github.GTNewHorizons:GTNHLib:0.11.24:dev")
    testImplementation("junit:junit:4.13.2")
}
