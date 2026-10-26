// AGENT-DONE(codex): msys2-native-toolchain
// FMOD Studio bindings: hand-written JNI (src/main/cpp/jni_fmod.cpp, over the minimal API
// surface declared in fmod_min.h) plus a small Java wrapper (FMODSystem.java) on top, in place
// of this module's previous jextract-generated java.lang.foreign (Panama FFI) bindings.
//
// Why the switch: the FFM approach needed SymbolLookup.findOrThrow() (added in JDK 24), and
// java.lang.foreign itself only stabilized (non-preview) in JDK 22 -- meaning the whole module,
// and by extension TACZ (which calls FMODSystem's classes directly, at TACZ's own build.gradle.kts
// Java toolchain version), required JDK 24+ just to launch. Players run whatever JDK their
// launcher bundles -- commonly Java 21 for Minecraft 1.21.1 -- so that floor was a real
// compatibility problem, not just a build-time inconvenience. Hand-written JNI over a small,
// explicitly-declared subset of the FMOD Studio C API (see fmod_min.h's comment on how that
// subset's correctness was verified) has no such floor.
//
// fmod_min.h's declared API is intentionally minimal: only what FMODSystem.java's public API
// actually needs, which is itself only what TACZ's com.tacz.guns.client.sound.fmod package
// actually calls -- not a general-purpose FMOD binding. FMODSystem's public API was kept
// identical to the previous FFM-backed version on purpose, so TACZ needed no changes at all.
plugins {
    `java-library`
}

group = "engine"
version = "1.0"

repositories {
    mavenCentral()
}

// Matches the rest of the repo (root/TACZ/First Aid/engine/audio) -- Java 21 is what Mojang
// ships to end users for Minecraft 1.21.1, and (as of this module's JNI rewrite) nothing in this
// repo requires newer than that anymore.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    // Used by FModLoad to extract the bundled native libraries from the jar to a temp dir.
    implementation("commons-io:commons-io:2.18.0")
}

// Identifies the native build both by output directory and by the resource path FModLoad's
// System.load lookup expects. Unlike engine/audio's "natives/<os>-<arch>/" (hyphenated)
// convention, this matches FModLoad.resourcePath()'s existing "<os>/<arch>/" (underscored, no
// "natives/" prefix) layout, since the vendored fmod.dll/fmodstudio.dll/etc. already sit there
// and dncity_fmod.dll needs to be findable the same way.
val nativeOs = "windows" // Only Windows is built today -- see CMakeLists.txt's comment.
val nativeArch = "x86_64"
val nativeLibName = "dncity_fmod.dll"

val nativeBuildDir = layout.buildDirectory.dir("native/$nativeOs-$nativeArch")
val nativeResourceDir = layout.buildDirectory.dir("generated/nativeResources")

val msys2UcrtBin = file((project.findProperty("msys2UcrtBin") as String?) ?: "C:/msys64/ucrt64/bin")
val cmakePath = (project.findProperty("cmakePath") as String?)
    ?: msys2UcrtBin.resolve("cmake.exe").takeIf { it.isFile }?.absolutePath
    ?: "cmake"
val ninjaPath = (project.findProperty("ninjaPath") as String?) ?: msys2UcrtBin.resolve("ninja.exe").absolutePath
val clangPath = (project.findProperty("clangPath") as String?) ?: msys2UcrtBin.resolve("clang.exe").absolutePath
val clangCppPath = (project.findProperty("clangCppPath") as String?) ?: msys2UcrtBin.resolve("clang++.exe").absolutePath
val winpthreadStaticPath = msys2UcrtBin.resolve("../lib/libwinpthread.a").canonicalPath

val configureNativeFmod = tasks.register<Exec>("configureNativeFmod") {
    inputs.file("CMakeLists.txt")
    inputs.dir("src/main/cpp")
    outputs.dir(nativeBuildDir)
    workingDir = projectDir
    commandLine(cmakePath, "-S", ".", "-B", nativeBuildDir.get().asFile.absolutePath,
        "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=$ninjaPath", "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_C_COMPILER=$clangPath", "-DCMAKE_CXX_COMPILER=$clangCppPath",
        "-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc -static-libstdc++ -Wl,--whole-archive,$winpthreadStaticPath,--no-whole-archive")
}

val buildNativeFmod = tasks.register<Exec>("buildNativeFmod") {
    dependsOn(configureNativeFmod)
    inputs.dir("src/main/cpp")
    val destDir = nativeResourceDir.map { it.dir("$nativeOs/$nativeArch") }
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
    dependsOn(buildNativeFmod)
}
