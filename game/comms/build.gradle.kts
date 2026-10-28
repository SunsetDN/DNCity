// AGENT-DONE(claude): feature-mods
// dncity_comms: radios, voice, phones. Shared setup (plugins, NeoForge, core dependency, metadata) is in the root
// build.gradle.kts (featureMods); only this mod's own dependencies are here.

dependencies {
    // engine/audio (miniaudio capture/playback + codec2, see the root build.gradle.kts's history for why a plain
    // library needs both implementation and jarJar, and also the dev runs' additionalRuntimeClasspath there).
    "implementation"("engine:audio:1.0")
    "jarJar"("engine:audio:1.0")

    // Opus (close-range voice and AM/FM radio), a prebuilt multi-platform JNI binding.
    "implementation"("com.plasmoverse:opus-jni-rust:1.0.4")
    "jarJar"("com.plasmoverse:opus-jni-rust:1.0.4")

    // NanoVG (phone chrome/keypad), pinned to Minecraft 1.21.1's strict LWJGL 3.3.3; natives-windows only, like
    // engine/window.
    "implementation"("org.lwjgl:lwjgl-nanovg:3.3.3")
    "runtimeOnly"("org.lwjgl:lwjgl-nanovg:3.3.3:natives-windows")
    "jarJar"("org.lwjgl:lwjgl-nanovg:3.3.3")
    "jarJar"("org.lwjgl:lwjgl-nanovg:3.3.3:natives-windows")

    // ModernUI-MC (phone Fragment/View host) and VoxelMap (phone Map app): their built jars, as in the root build
    // (see its comments for why not a project substitution). The dev runs load them from the root project.
    "compileOnly"(fileTree(rootDir.resolve("mods/ModernUI-MC/neoforge/build/libs")) { include("*-universal.jar") })
    "compileOnly"(fileTree(rootDir.resolve("mods/VoxelMap/build/libs")) { include("*.jar") })
}

listOf("compileJava", "compileKotlin").forEach { taskName ->
    tasks.named(taskName) {
        dependsOn(gradle.includedBuild("ModernUI-MC").task(":ModernUI-NeoForge:remapJar"))
        dependsOn(gradle.includedBuild("VoxelMap").task(":neoforge:jar"))
    }
}
