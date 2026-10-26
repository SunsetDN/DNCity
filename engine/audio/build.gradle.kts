// AGENT-DONE(codex): msys2-native-toolchain
import org.gradle.internal.os.OperatingSystem

// Native audio capture/playback: a small JNI bridge (src/main/cpp) over the vendored miniaudio
// (vendor/miniaudio.h, single-header, public domain/MIT-0 -- see vendor/README.md) instead of
// jextract-generated FFI bindings like engine/fmod. Chosen so the JVM side only ever pushes/pulls
// fixed-format PCM through lock-free ring buffers on the native side -- no callback from the
// audio device thread ever has to attach to the JVM.
//
// Local device capture/playback only for now: no network relay lives here, that's a separate
// concern for whatever consumes this module's Java API (see src/main/java/.../NativeAudio.java
// for the fixed format and ring-buffer semantics).
//
// Plain Java, not Kotlin: when this module is consumed by the root project via
// `additionalRuntimeClasspath` (needed for ModDevGradle dev runs -- see the root build.gradle.kts
// dependencies block), it can end up in a module layer that doesn't have kotlin-stdlib visible to
// it, which surfaced as a NoClassDefFoundError on kotlin.jvm.internal.Intrinsics in practice.
// Since this module's only class is a thin, mechanical JNI declarations wrapper, dropping Kotlin
// entirely removes that whole failure class instead of chasing module-layer visibility.
//
// Not published to Maven Central and not yet wired into the root build via includeBuild, same as
// engine/fmod -- build/use this module standalone until it's ready to be consumed.
plugins {
    `java-library`
}

group = "engine"
version = "1.0"

repositories {
    mavenCentral()
}

// Matches the rest of the repo -- see the root build.gradle.kts's comment on why Java 21
// (not the previous Java 25) is the actual floor now.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

// Identifies the native build both by output directory and by the resource path
// NativeAudio's System.loadLibrary lookup expects, so multiple platforms' binaries can
// eventually sit side by side in the jar without colliding.
val nativeOs = when {
    OperatingSystem.current().isWindows -> "windows"
    OperatingSystem.current().isMacOsX -> "macos"
    else -> "linux"
}
val nativeArch = System.getProperty("os.arch").let {
    if (it == "amd64" || it == "x86_64") "x86-64" else it
}
val nativeClassifier = "$nativeOs-$nativeArch"
val nativeLibName = when (nativeOs) {
    "windows" -> "dncity_audio.dll"
    "macos" -> "libdncity_audio.dylib"
    else -> "libdncity_audio.so"
}

val nativeBuildDir = layout.buildDirectory.dir("native/$nativeClassifier")
val nativeResourceDir = layout.buildDirectory.dir("generated/nativeResources")

// codec2 needs a real MinGW libc for its C99 `_Complex` arithmetic. Prefer the complete MSYS2
// UCRT64 toolchain on Windows; every path remains overridable for non-default installations.
val msys2UcrtBin = file((project.findProperty("msys2UcrtBin") as String?) ?: "C:/msys64/ucrt64/bin")
val cmakePath = (project.findProperty("cmakePath") as String?)
    ?: msys2UcrtBin.resolve("cmake.exe").takeIf { it.isFile }?.absolutePath
    ?: "cmake"
val ninjaPath = (project.findProperty("ninjaPath") as String?)
    ?: msys2UcrtBin.resolve("ninja.exe").absolutePath
val clangPath = (project.findProperty("clangPath") as String?)
    ?: msys2UcrtBin.resolve("clang.exe").absolutePath
val clangCppPath = (project.findProperty("clangCppPath") as String?)
    ?: msys2UcrtBin.resolve("clang++.exe").absolutePath

val configureNativeAudio = tasks.register<Exec>("configureNativeAudio") {
    inputs.file("CMakeLists.txt")
    inputs.dir("src/main/cpp")
    inputs.dir("vendor")
    outputs.dir(nativeBuildDir)
    workingDir = projectDir
    val args = mutableListOf(
        cmakePath, "-S", ".", "-B", nativeBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
    )
    if (OperatingSystem.current().isWindows) {
        args += listOf(
            "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=$ninjaPath",
            "-DCMAKE_C_COMPILER=$clangPath",
            "-DCMAKE_CXX_COMPILER=$clangCppPath",
            "-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc -static-libstdc++ -Wl,-Bstatic -lwinpthread -Wl,-Bdynamic",
        )
    }
    commandLine(args)
}

val buildNativeAudio = tasks.register<Exec>("buildNativeAudio") {
    dependsOn(configureNativeAudio)
    inputs.dir("src/main/cpp")
    inputs.dir("vendor")
    val destDir = nativeResourceDir.map { it.dir("natives/$nativeClassifier") }
    outputs.dir(destDir)
    workingDir = projectDir
    commandLine(cmakePath, "--build", nativeBuildDir.get().asFile.absolutePath, "--config", "Release")
    // Resolved to plain local values (not script-level property references) before doLast, so
    // the stored task action doesn't capture the enclosing build script object -- accessing a
    // top-level script val from inside doLast implicitly captures the whole script instance via
    // Kotlin's receiver-capture, which the configuration cache can't serialize.
    val libFileName = nativeLibName
    val nativeBuildDirPath = nativeBuildDir.get().asFile.absolutePath
    val builtLibraryTree = fileTree(nativeBuildDir) { include("**/$libFileName") }
    doLast {
        val out = destDir.get().asFile
        out.mkdirs()
        val built = builtLibraryTree.files.firstOrNull()
            ?: error("Native build did not produce $libFileName under $nativeBuildDirPath")
        built.copyTo(out.resolve(libFileName), overwrite = true)
    }
}

sourceSets["main"].resources.srcDir(nativeResourceDir)
tasks.named("processResources") {
    dependsOn(buildNativeAudio)
}
