// FMOD Studio bindings: jextract-generated Panama FFI (java.lang.foreign) bindings over
// fmod.h/fmodstudio.h, based on https://github.com/iwei20/java-fmod, plus a hand-written
// FMODSystem wrapper (bank loading, event playback) on top of them.
//
// The generated sources under src/generated/java were regenerated on Windows with jextract 25
// (see ../libs/README.md) because the upstream repo's checked-in bindings hardcode C's `long`
// as 8 bytes (LP64, Linux/macOS), which crashes on Windows where `long` is 4 bytes (LLP64).
//
// Not published to Maven Central, so it lives here as a source module instead of a binary
// dependency.
//
// The rest of the repo is unified on Java 25 (matching this module, which needs at least
// Java 24 for the generated bindings' use of APIs added after java.lang.foreign stabilized
// in JDK 22 -- SymbolLookup.findOrThrow, Linker.canonicalLayouts, Arena.allocateFrom, etc.).
plugins {
    `java-library`
}

group = "engine"
version = "1.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain.languageVersion = JavaLanguageVersion.of(25)

    sourceSets {
        named("main") {
            java {
                srcDir("src/generated/java")
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

dependencies {
    // Used by FModLoad to extract the bundled native libraries from the jar to a temp dir.
    implementation("commons-io:commons-io:2.18.0")
}
