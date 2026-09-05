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

// Requires CMake and a C/C++ toolchain (MSVC/Visual Studio Build Tools on Windows) on PATH.
// No special generator/toolchain flags needed (unlike engine/audio's clang+MinGW setup) --
// CMake's default Windows generator (MSVC) is fine, same as engine/fmod.
val configureNativeWindow = tasks.register<Exec>("configureNativeWindow") {
    inputs.file("CMakeLists.txt")
    inputs.dir("src/main/cpp")
    outputs.dir(nativeBuildDir)
    workingDir = projectDir
    commandLine("cmake", "-S", ".", "-B", nativeBuildDir.get().asFile.absolutePath, "-DCMAKE_BUILD_TYPE=Release")
}

val buildNativeWindow = tasks.register<Exec>("buildNativeWindow") {
    dependsOn(configureNativeWindow)
    inputs.dir("src/main/cpp")
    val destDir = nativeResourceDir.map { it.dir("natives/$nativeOs-$nativeArch") }
    outputs.dir(destDir)
    workingDir = projectDir
    commandLine("cmake", "--build", nativeBuildDir.get().asFile.absolutePath, "--config", "Release")
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
    dependsOn(buildNativeWindow)
}
