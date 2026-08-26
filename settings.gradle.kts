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

// engine/fmod (hand-written JNI FMOD bindings -- see CLAUDE.md) was previously only reachable
// via TACZ's own includeBuild (mods/TACZ-1.21.1/settings.gradle.kts) -- included here too so the
// root project can depend on it directly, for FMODCoreSystem-based direct audio-file playback
// (io.github.jwyoon1220.dncity.music.AudioPlayer) independent of TACZ's own Studio system.
// Gradle deduplicates composite builds included from multiple places by build identity (the
// build's own root directory), so this doesn't create a second, conflicting instance of the
// module -- both this and TACZ's includeBuild resolve to the same one.
includeBuild("engine/fmod") {
    dependencySubstitution {
        substitute(module("engine:fmod")).using(project(":"))
    }
}

// Automobility (mods/Automobility) -- this fork strips it down to a single neoforge subproject
// (see mods/Automobility/settings.gradle.kts) since upstream's common/fabric setup pulled in
// fabric-loom, which needs a newer Gradle than this repo runs.
includeBuild("mods/Automobility") {
    dependencySubstitution {
        substitute(module("io.github.foundationgames:automobility")).using(project(":neoforge"))
    }
}
