// Standalone child-process JCEF/AWT host, launched by the main mod (see
// client/window/BrowserHostProcess.kt) as a plain `java -jar` subprocess -- NOT wired into the
// main mod's own classloading/module system the way engine/audio, engine/fmod, and engine/window
// are (no includeBuild+dependencySubstitution into the root project). See BrowserHostMain's own
// doc comment for why a whole separate process exists: creating an AWT JFrame/JCEF browser
// in-process, inside Minecraft's own JVM (which forces java.awt.headless=true at boot), needed
// Unsafe-based reflection to un-cache GraphicsEnvironment's headless state -- which reliably
// crashed the whole JVM natively (EXCEPTION_ACCESS_VIOLATION in unrelated, already-JIT-compiled
// AWT native-peer code, confirmed on both GraalVM CE and plain OpenJDK 21, so not a JIT-vendor
// bug -- see memory/project_phone_overlay_jvm_crash.md from the debugging session that led here).
// This process never has headless forced at all, so it needs none of that hackery.
//
// Root project's build.gradle.kts stages this module's shadow jar into
// src/main/resources/browserhost/browserhost-all.jar (a plain classpath resource, extracted to a
// real temp file at runtime by BrowserHostProcess -- same pattern as NativeLibrary/FModLoad
// extracting a bundled native library) via includeBuild("engine/browserhost") +
// gradle.includedBuild("browserhost").task(":shadowJar").
//
// Kotlin (not plain Java like engine/audio/window/fmod): those modules avoid Kotlin because they
// get pulled into the main mod's own module layer via additionalRuntimeClasspath, where
// kotlin-stdlib visibility isn't guaranteed (see engine/audio's build.gradle.kts comment). This
// module is never loaded into that module layer at all -- it only ever runs as an independent
// `java -jar` process with its own self-contained shadow jar (Kotlin stdlib included) -- so that
// concern doesn't apply.
plugins {
    kotlin("jvm") version "2.3.20"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "engine"
version = "1.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Same version the root project pins for its own (now-removed) in-process JCEF usage -- see
    // BrowserOverlay.kt.
    implementation("me.friwi:jcefmaven:135.0.20")
    implementation("engine:window:1.0")
}

application {
    mainClass.set("io.github.jwyoon1220.dncity.browserhost.BrowserHostMainKt")
}

tasks.shadowJar {
    archiveBaseName.set("browserhost")
    archiveClassifier.set("all")
    archiveVersion.set("")
}
