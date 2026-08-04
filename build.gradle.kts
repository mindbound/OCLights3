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

// ---------------------------------------------------------------------------
// Keep build/libs to the CURRENT build's artifacts only.
//
// Jar names embed the git description, so every commit leaves its jars behind and the directory
// accumulates indefinitely — it had reached ~80 files. That is not merely untidy: the version
// comes from a configuration-cached `GitTagVersionSource`, so a build can emit a jar named after
// an OLDER commit than HEAD while the previous newest jar keeps a NEWER-looking name. Picking
// "the latest jar" out of that directory by eye is how a stale jar ends up in a test instance,
// which cost an evening of chasing a callback that was present in the source all along.
//
// Deletes only *.jar, and only before the jar tasks write, so the current build's outputs
// survive and nothing else in build/ is touched.
val pruneStaleJars by tasks.registering(Delete::class) {
    delete(fileTree(layout.buildDirectory.dir("libs")) { include("*.jar") })
}

tasks.withType<Jar>().configureEach {
    dependsOn(pruneStaleJars)
}
