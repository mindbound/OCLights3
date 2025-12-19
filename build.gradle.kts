plugins {
    id("com.falsepattern.fpgradle-mc") version "3.3.0"
}

group = "ds.mods"

minecraft_fp {
    mod {
        modid   = "OCLights2"
        name    = "OCLights 2"
        rootPkg = "$group.OCLights2"
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
}

dependencies {
    compileOnly("com.github.GTNewHorizons:OpenComputers:1.12.8-GTNH:api") {
        excludeDeps()
    }
    runtimeOnly("com.github.GTNewHorizons:OpenComputers:1.12.8-GTNH:dev") {
        excludeDeps()
    }
}
