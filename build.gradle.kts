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

// ---------------------------------------------------------------------------
// Make the jar carry its own licence and identity.
//
// MMPL-1.0 is a copyleft licence whose terms have to travel with the binary, and until now the
// jar shipped neither the licence text nor anything identifying itself: the manifest was 25
// bytes, `Manifest-Version: 1.0` and nothing else. That is a publish blocker rather than a
// tidiness issue — a redistributed jar with no licence inside is a jar whose recipient has no
// stated terms.
//
// `metaInf` rather than adding LICENSE.md to src/main/resources: the file belongs at the repo
// root (that is where GitHub and every tool looks for it), and copying it into the source tree
// would create a second copy to drift.
//
// Deliberately `tasks.named<Jar>("jar")`, NOT `withType<Jar>().configureEach`. See the pruning
// block below for why that distinction is load-bearing here: mutating lazily-realized Jar tasks
// broke CI once with a Gradle-internal ConcurrentModificationException. Adding a CopySpec and
// manifest attributes to one named task adds no graph edges, but the narrower form is still the
// right habit in this build.
tasks.named<Jar>("jar") {
    metaInf {
        from(rootProject.file("LICENSE.md"))
    }
    manifest {
        attributes(
            "Implementation-Title" to "OpenGPU",
            "Implementation-Version" to project.version.toString(),
            "Implementation-Vendor" to "OpenGPU contributors",
            // MMPL requires source availability alongside binaries. The sources jar is already
            // built and published to the same Maven repo; this says where to look for it, so the
            // obligation is discoverable from the artifact rather than only from the repo.
            //
            // Must match mcmod.info's "url". The remote is still named OCLights3 while the mod
            // is OpenGPU — a rename is the owner's call, and when it happens BOTH places move.
            "Source-Repository" to "https://github.com/mindbound/OCLights3",
            "License" to "MMPL-1.0 (see META-INF/LICENSE.md)",
        )
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
// Implemented as a doLast on `build` that touches only *.jar, ADDING NO GRAPH EDGES.
//
// The first version registered a Delete task and wired it with
//   tasks.withType<Jar>().configureEach { dependsOn(pruneStaleJars) }
// which passed every local build and broke CI with a Gradle-internal
// ConcurrentModificationException in NodeSets during execution-plan finalization. Adding a
// dependency from configureEach mutates the task graph as Jar tasks are REALIZED, and FPGradle
// realizes several of them lazily, so Gradle ended up modifying a node set it was iterating.
// Local runs missed it because they used --no-daemon with a cold configuration cache; CI reuses
// the configuration and build caches with parallel workers, so tasks realize in a different
// order. Task-graph shape is precisely the thing local builds cannot verify.
//
// Keeping by modification time rather than by version string avoids coupling to how FPGradle
// derives the version -- which is itself configuration-cached and was the reason a build could
// emit a jar named after an older commit than HEAD.
tasks.named("build") {
    val libsDir = layout.buildDirectory.dir("libs")
    doLast {
        val dir = libsDir.get().asFile
        val jars = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") } ?: emptyList()
        if (jars.isEmpty()) return@doLast
        val newest = jars.maxOf { it.lastModified() }
        val staleAfterMillis = 10L * 60L * 1000L
        jars.filter { newest - it.lastModified() > staleAfterMillis }.forEach { it.delete() }
    }
}
