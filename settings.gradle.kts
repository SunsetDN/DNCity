pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven { url = uri("https://maven.neoforged.net/releases") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

includeBuild("mods/TACZ-1.21.1") {
    dependencySubstitution {
        substitute(module("com.tacz:tacz-neoforge-1.21.1")).using(project(":"))
    }
}

includeBuild("mods/First-Aid-New") {
    dependencySubstitution {
        substitute(module("ichttt.mods.firstaid:firstaid")).using(project(":"))
    }
}

// engine/audio (miniaudio JNI bridge -- see CLAUDE.md) is a standalone Gradle project, wired in
// here the same way as the TACZ/First Aid submodules above so its NativeAudio class and native
// library resource land on the main mod's classpath.
includeBuild("engine/audio") {
    dependencySubstitution {
        substitute(module("engine:audio")).using(project(":"))
    }
}
