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

// SuperbWarfare (mods/SuperbWarfare) -- tracks upstream (Mercurows/SuperbWarfare) directly on its
// "1.21" branch rather than the SunsetDN fork used for the other submodules above: the SunsetDN
// fork only has an old Forge/Minecraft 1.20.1 branch, while upstream's own "1.21" branch is
// already a pure NeoForge/Minecraft 1.21.1 build (single project, no Forge/Fabric split), so no
// porting was needed.
includeBuild("mods/SuperbWarfare") {
    dependencySubstitution {
        substitute(module("com.atsuishio.superbwarfare:superbwarfare")).using(project(":"))
    }
}

// ModernUI-MC (mods/ModernUI-MC) -- unlike the submodules above, this one is included WITHOUT a
// dependencySubstitution: its neoforge subproject builds with Architectury Loom (a completely
// separate toolchain from this repo's net.neoforged.moddev-based ones, with its own independent
// Minecraft artifact resolution), and publishes several jar variants from one project (plain,
// shadow, sources, and the self-contained "universal" one -- see
// mods/ModernUI-MC/neoforge/build.gradle's remapJar/shadowJar tasks). A substitution would
// silently resolve to whichever jar happens to be the project's default artifact, not
// necessarily the "universal" one this repo actually wants to distribute -- so it's just
// included as a composite build (for its own build/run tasks and IDE integration) and its
// "universal" jar copied by collectDistributionJars in the root build.gradle.kts, the same way
// Sodium/Iris are, rather than substituted into a dependency.
includeBuild("mods/ModernUI-MC")

// Sodium (mods/sodium, SunsetDN fork, 1.21.1/stable branch) -- this fork drops "fabric" from
// upstream (see mods/sodium/settings.gradle.kts), keeping "common" (loader-agnostic shared code,
// still built via Fabric Loom purely for Minecraft/mappings resolution) and "neoforge". Its jar is
// collected by collectDistributionJars in the root build.gradle.kts instead of the previous
// prebuilt Modrinth artifact, AND substituted for TACZ's own "maven.modrinth:sodium" compat
// dependency (see mods/TACZ-1.21.1/build.gradle.kts) so it builds against this local fork too --
// safe to point at :neoforge's default (library) jar here since TACZ's compat code doesn't
// reference any Sodium classes directly (only Iris's, see below), so the exact artifact shape
// doesn't matter for compilation.
includeBuild("mods/sodium") {
    dependencySubstitution {
        substitute(module("maven.modrinth:sodium")).using(project(":neoforge"))
    }
}

// Iris (mods/Iris, SunsetDN fork, 1.21.1 branch) -- same "fabric" drop as Sodium above (see
// mods/Iris/settings.gradle.kts). Unlike Sodium, Iris's "neoforge" subproject compiles "common"'s
// sources directly into its own main source set (see its build.gradle.kts's notNeoTask-filtered
// JavaCompile/ProcessResources wiring) rather than splitting service/mod jars, so its default jar
// already contains the real net.irisshaders.iris.* classes TACZ's compat code
// (mods/TACZ-1.21.1/src/main/java/com/tacz/guns/compat/iris) needs at compile time -- substituting
// straight to :neoforge's default configuration is enough, unlike a hypothetical Sodium class-level
// substitution which would need its "mod" configuration specifically.
includeBuild("mods/Iris") {
    dependencySubstitution {
        substitute(module("maven.modrinth:iris")).using(project(":neoforge"))
    }
}
