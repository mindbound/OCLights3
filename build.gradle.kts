plugins {
    id("com.falsepattern.fpgradle-mc") version "3.3.0"
}

group = "ds.mods"

minecraft_fp {
    mod {
        modid   = "OCLights3"
        name    = "OCLights 3"
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
    mavenCentral()
}

dependencies {
    compileOnly("com.github.GTNewHorizons:OpenComputers:1.12.55-GTNH:api") {
        excludeDeps()
    }
    runtimeOnly("com.github.GTNewHorizons:OpenComputers:1.12.55-GTNH:dev") {
        excludeDeps()
    }
    testImplementation("junit:junit:4.13.2")
}
