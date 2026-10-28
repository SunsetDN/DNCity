// AGENT-DONE(codex): msys2-native-toolchain
// Native (C++/JNI) port of this mod's KCP-over-UDP transport, replacing the previous pure-Kotlin
// implementation (see network/kcp/Kcp.kt's git history) with a hand-written JNI bridge over a
// direct C++ translation of the same algorithm -- same wire format, same simplified feature set
// (no congestion-control window, no window-probe -- see NativeKcp.java's doc comment), so
// VoiceKcpServer/VoiceKcpClient's on-the-wire behavior is unchanged, only where the per-segment
// ARQ bookkeeping runs. Motivation: get this off the JVM's GC/JIT entirely for the mod's
// lowest-latency traffic path, and give it one shared channel other latency-sensitive binary
// traffic (Distant Horizons LOD streaming) can also ride, demuxed by NativeKcp's own leading
// type-tag byte the same way voice/radio/phone-call audio already are (see
// network/kcp/VoiceKcpProtocol.kt).
//
// Same composite-build shape as engine/audio/engine/fmod/engine/window (own settings.gradle.kts,
// own build.gradle.kts wired into the root via includeBuild + dependencySubstitution +
// implementation/jarJar/additionalRuntimeClasspathConfiguration). Windows and Linux x86-64: the
// KCP algorithm itself is plain, portable C++17 with no OS-specific calls at all (unlike
// engine/window's Win32 windowing or engine/audio's codec2 `_Complex` need); macOS would be one
// more natives/<os>-<arch>/ build.
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
}

group = "engine"
version = "1.0"

repositories {
    mavenCentral()
}

// Matches the rest of the repo (root/TACZ/First Aid/engine/audio/engine/fmod/engine/window) --
// Java 21 is what Mojang ships to end users for Minecraft 1.21.1.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}


// No dependencies block: NativeKcpLibrary uses plain java.nio.file/InputStream.transferTo, same
// as engine/audio's NativeLibrary.java / engine/window's NativeWindowLibrary.java -- no commons-io
// needed (unlike engine/fmod).

// Built for the host: Windows with the MSYS2 UCRT64 toolchain (paths below), Linux with the system's
// cmake and C++ compiler. A jar built anywhere also carries the Linux x86-64 library: a Linux x86-64
// host builds it fresh, any other host takes the committed copy in prebuilt/ (refresh it on Linux
// with `./gradlew -p engine/kcp updateLinuxPrebuilt` after changing the C++ sources).
// Linux links libstdc++ dynamically (it is already needed by FMOD's own Linux libraries), which keeps
// the floor at what the code itself uses instead of the build machine's glibc.
val hostOs = OperatingSystem.current()
val nativeOs = when {
    hostOs.isWindows -> "windows"
    hostOs.isLinux -> "linux"
    else -> "unsupported"
}
val nativeArch = System.getProperty("os.arch").let { if (it == "amd64" || it == "x86_64") "x86-64" else it }
val nativeLibName = if (nativeOs == "windows") "dncity_kcp.dll" else "libdncity_kcp.so"
val nativeResourcePath = "natives/$nativeOs-$nativeArch"

val nativeBuildDir = layout.buildDirectory.dir("native/$nativeOs-$nativeArch")
val nativeResourceDir = layout.buildDirectory.dir("generated/nativeResources")

// MSYS2 paths only mean something on Windows (file("C:/...") fails to resolve elsewhere).
val msys2UcrtBin = if (hostOs.isWindows) file((project.findProperty("msys2UcrtBin") as String?) ?: "C:/msys64/ucrt64/bin") else null
val cmakePath = (project.findProperty("cmakePath") as String?)
    ?: msys2UcrtBin?.resolve("cmake.exe")?.takeIf { it.isFile }?.absolutePath
    ?: "cmake"

val configureNativeKcp = tasks.register<Exec>("configureNativeKcp") {
    onlyIf { nativeOs != "unsupported" }
    inputs.file("CMakeLists.txt")
    inputs.dir("src/main/cpp")
    outputs.dir(nativeBuildDir)
    workingDir = projectDir
    val args = mutableListOf(cmakePath, "-S", ".", "-B", nativeBuildDir.get().asFile.absolutePath, "-DCMAKE_BUILD_TYPE=Release")
    if (msys2UcrtBin != null) {
        val ninjaPath = (project.findProperty("ninjaPath") as String?) ?: msys2UcrtBin.resolve("ninja.exe").absolutePath
        val clangPath = (project.findProperty("clangPath") as String?) ?: msys2UcrtBin.resolve("clang.exe").absolutePath
        val clangCppPath = (project.findProperty("clangCppPath") as String?) ?: msys2UcrtBin.resolve("clang++.exe").absolutePath
        val winpthreadStaticPath = msys2UcrtBin.resolve("../lib/libwinpthread.a").canonicalPath
        args += listOf("-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=$ninjaPath",
            "-DCMAKE_C_COMPILER=$clangPath", "-DCMAKE_CXX_COMPILER=$clangCppPath",
            "-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc -static-libstdc++ -Wl,--whole-archive,$winpthreadStaticPath,--no-whole-archive")
    }
    commandLine(args)
}

val buildNativeKcp = tasks.register<Exec>("buildNativeKcp") {
    onlyIf { nativeOs != "unsupported" }
    dependsOn(configureNativeKcp)
    inputs.dir("src/main/cpp")
    val destDir = nativeResourceDir.map { it.dir(nativeResourcePath) }
    outputs.dir(destDir)
    workingDir = projectDir
    commandLine(cmakePath, "--build", nativeBuildDir.get().asFile.absolutePath, "--config", "Release")
    val libFileName = nativeLibName
    val nativeBuildDirPath = nativeBuildDir.get().asFile.absolutePath
    val builtLibraryTree = fileTree(nativeBuildDir) {
        include("**/$libFileName", "**/lib$libFileName")
    }
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
    dependsOn(buildNativeKcp)
}

val buildsLinuxX64 = nativeOs == "linux" && nativeArch == "x86-64"
if (buildsLinuxX64) {
    tasks.register<Copy>("updateLinuxPrebuilt") {
        group = "build"
        description = "Copies the freshly built Linux x86-64 library into prebuilt/, which is committed."
        dependsOn(buildNativeKcp)
        from(nativeResourceDir)
        include("$nativeResourcePath/$nativeLibName")
        into("prebuilt")
    }
} else {
    sourceSets["main"].resources.srcDir("prebuilt")
}
