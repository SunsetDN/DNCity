// AGENT-DONE(codex): msys2-native-toolchain
// Win32 native child-window bridge: creates a real HWND parented directly to the Minecraft/GLFW
// window, either handed back raw (for external native rendering) or reparented with an AWT
// Frame/JFrame embedded inside it (found via a unique-title FindWindowW lookup, not JDK internal
// --add-opens hacks) so Swing content -- including a JCEF browser's UI component -- can be added.
// See AGENTS.md's "Architecture: window overlay" section.
//
// Windows-only for now, same as engine/fmod: plain Win32 windowing APIs need nothing beyond the
// default MSVC toolchain (no C99 `_Complex`-style constraint like engine/audio's codec2 has), so
// this module copies engine/fmod's simpler CMake/Gradle shape rather than engine/audio's
// clang+MinGW one. The Java loader class, however, mirrors engine/audio's NativeLibrary.java
// (natives/<os>-<arch>/ resource layout, temp-root-level DLL extraction, no extra dependency) --
// see NativeWindowLibrary.java's own comment for why extraction happens straight into the OS temp
// root rather than a subdirectory.
plugins {
    `java-library`
}

group = "engine"
version = "1.0"

repositories {
    mavenCentral()
}

// Matches the rest of the repo (root/TACZ/First Aid/engine/audio/engine/fmod) -- Java 21 is what
// Mojang ships to end users for Minecraft 1.21.1.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

// No dependencies block: NativeWindowLibrary uses plain java.nio.file/InputStream.transferTo,
// same as engine/audio's NativeLibrary.java -- no commons-io needed (unlike engine/fmod).

// "natives/<os>-<arch>/" (hyphenated, with prefix) -- matches engine/audio's convention, and
// NativeWindowLibrary's resourcePath() lookup below.
val nativeOs = "windows" // Only Windows is built today -- see CMakeLists.txt's comment.
val nativeArch = "x86-64" // Hyphenated to match NativeWindowLibrary's resourcePath() lookup.
val nativeLibName = "dncity_window.dll"

val nativeBuildDir = layout.buildDirectory.dir("native/$nativeOs-$nativeArch")
val nativeResourceDir = layout.buildDirectory.dir("generated/nativeResources")

// The Win32 code has no Linux/macOS counterpart (every export is under _WIN32), so the native tasks
// only run on Windows; the MSYS2 path is only resolved there (file("C:/...") fails elsewhere).
val isWindowsHost = org.gradle.internal.os.OperatingSystem.current().isWindows
val msys2UcrtBin = file((project.findProperty("msys2UcrtBin") as String?) ?: if (isWindowsHost) "C:/msys64/ucrt64/bin" else "msys2-not-used")
val cmakePath = (project.findProperty("cmakePath") as String?)
    ?: msys2UcrtBin.resolve("cmake.exe").takeIf { it.isFile }?.absolutePath
    ?: "cmake"
val ninjaPath = (project.findProperty("ninjaPath") as String?) ?: msys2UcrtBin.resolve("ninja.exe").absolutePath
val clangPath = (project.findProperty("clangPath") as String?) ?: msys2UcrtBin.resolve("clang.exe").absolutePath
val clangCppPath = (project.findProperty("clangCppPath") as String?) ?: msys2UcrtBin.resolve("clang++.exe").absolutePath
val winpthreadStaticPath = msys2UcrtBin.resolve("../lib/libwinpthread.a").canonicalPath

val configureNativeWindow = tasks.register<Exec>("configureNativeWindow") {
    onlyIf { isWindowsHost }
    inputs.file("CMakeLists.txt")
    inputs.dir("src/main/cpp")
    outputs.dir(nativeBuildDir)
    workingDir = projectDir
    commandLine(cmakePath, "-S", ".", "-B", nativeBuildDir.get().asFile.absolutePath,
        "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=$ninjaPath", "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_C_COMPILER=$clangPath", "-DCMAKE_CXX_COMPILER=$clangCppPath",
        "-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc -static-libstdc++ -Wl,--whole-archive,$winpthreadStaticPath,--no-whole-archive")
}

val buildNativeWindow = tasks.register<Exec>("buildNativeWindow") {
    onlyIf { isWindowsHost }
    dependsOn(configureNativeWindow)
    inputs.dir("src/main/cpp")
    val destDir = nativeResourceDir.map { it.dir("natives/$nativeOs-$nativeArch") }
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
    dependsOn(buildNativeWindow)
}
