import org.gradle.language.jvm.tasks.ProcessResources
import org.slf4j.event.Level

plugins {
    `java-library`
    `maven-publish`
    idea
    id("net.neoforged.moddev") version "2.0.144"
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
}

val minecraft_version: String by project
val minecraft_version_range: String by project
val neo_version: String by project
val neo_version_range: String by project
val loader_version_range: String by project
val parchment_mappings_version: String by project
val parchment_minecraft_version: String by project
val mod_id: String by project
val mod_name: String by project
val mod_license: String by project
val mod_version: String by project
val mod_group_id: String by project
val mod_authors: String by project
val mod_description: String by project
val mods_tacz_version: String by project
val mods_firstaid_version: String by project
val mods_automobility_version: String by project

// Pinned to the exact same versions TACZ (mods/TACZ-1.21.1/gradle/libs.versions.toml) already
// depends on for its own compat code, so these dev-run additions resolve from the local Gradle
// cache TACZ's own build already populated -- not a second, independent version choice.
val mods_sodium_version = "mc1.21.1-0.6.13-neoforge"
val mods_iris_version = "1.8.12+1.21.1-neoforge"

version = mod_version
group = mod_group_id

// Minecraft's own dependency set (via net.neoforged:minecraft-dependencies) pins
// commons-io to 2.15.1 with a strict constraint. engine/fmod requests 2.18.0, which
// conflicts with that constraint; force everything onto the version Minecraft ships,
// since commons-io is source/binary compatible across these minor releases.
configurations.all {
    resolutionStrategy.force("commons-io:commons-io:2.15.1")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
        content { includeGroup("thedarkcolour") }
    }

    // Required to resolve TACZ's (mods/TACZ-1.21.1) own dependencies, since
    // Gradle resolves the whole project graph using the consuming project's
    // (this one's) repositories rather than the included build's.
    maven {
        url = uri("https://jitpack.io")
        content {
            includeGroup("com.github.rtyley")
            includeGroup("com.github.FiguraMC.luaj")
            includeGroup("com.github.mcmodderanchor")
        }
    }
    maven { url = uri("https://maven.shedaniel.me") }
    maven { url = uri("https://maven.kosmx.dev") }
    maven { url = uri("https://maven.blamejared.com") }
    // opus-jni-rust (com.plasmoverse:opus-jni-rust) -- existing Rust/jni-rs Opus JNI binding
    // (github.com/plasmoapp/opus-jni-rust, from the Plasmo Voice team) used for the close-range
    // voice chat tier's Opus codec, in place of hand-writing new JNI bindings.
    maven {
        url = uri("https://repo.plasmoverse.com/releases")
        content { includeGroup("com.plasmoverse") }
    }
    maven {
        url = uri("https://maven.architectury.dev")
        content { includeGroup("dev.architectury") }
    }
    maven {
        url = uri("https://maven.latvian.dev/releases")
        content {
            includeGroup("dev.latvian.mods")
            includeGroup("dev.latvian.apps")
        }
    }
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter { includeGroup("maven.modrinth") }
    }
    exclusiveContent {
        forRepository {
            maven {
                name = "CurseForge"
                url = uri("https://cursemaven.com")
            }
        }
        filter { includeGroup("curse.maven") }
    }
    flatDir { dirs("mods/TACZ-1.21.1/libs") }
}

base {
    archivesName.set(mod_id)
}

// Unified on Java 21 across the whole repo (root, TACZ, First Aid, engine/audio, engine/fmod) --
// what Mojang ships to end users for Minecraft 1.21.1, so it's what players actually have.
// This was Java 25 until 2026-08-26: engine/fmod's old jextract/java.lang.foreign bindings
// needed JDK 24+ (SymbolLookup.findOrThrow, added in JDK 24; java.lang.foreign itself only
// stabilized out of preview in JDK 22), and TACZ loads engine/fmod's classes directly at
// runtime, so the requirement cascaded to the whole repo (UnsupportedClassVersionError
// otherwise). engine/fmod now uses hand-written JNI instead (see its build.gradle.kts/
// CMakeLists.txt), which has no such floor -- don't reintroduce a jextract/FFM-based binding
// without solving this the same way, or this comment (and the Java 21 pin) will need reverting.
java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
tasks.withType<JavaCompile> {
    options.release.set(21)
}
kotlin.jvmToolchain(21)

neoForge {
    // Specify the version of NeoForge to use.
    version = neo_version

    parchment {
        mappingsVersion = parchment_mappings_version
        minecraftVersion = parchment_minecraft_version
    }

    // This line is optional. Access Transformers are automatically detected
    // accessTransformers.add("src/main/resources/META-INF/accesstransformer.cfg")

    // Default run configurations.
    // These can be tweaked, removed, or duplicated as needed.
    runs {
        create("client") {
            client()
            jvmArgument("-Xmx4G")

            // Comma-separated list of namespaces to load gametests from. Empty = all namespaces.
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
        }

        // Second dev client with a different username/uuid and its own game directory (so it
        // doesn't fight the "client" run over run/options.txt, logs, etc. if launched at the
        // same time) -- for testing multiplayer-only features (radio, proximity chat) locally
        // by connecting two clients to the same server/LAN world at once.
        create("client2") {
            client()
            jvmArgument("-Xmx4G")
            devLogin = false
            gameDirectory = file("run-client2")
            programArgument("--username")
            programArgument("Client2")
            programArgument("--uuid")
            programArgument("00000000-0000-0000-0000-000000000002")
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
        }

        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
        }

        // This run config launches GameTestServer and runs all registered gametests, then exits.
        // By default, the server will crash when no gametests are provided.
        // The gametest system is also enabled by default for other run configs under the /test command.
        create("gameTestServer") {
            type = "gameTestServer"
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
        }

        create("data") {
            data()

            // example of overriding the workingDirectory set in configureEach above, uncomment if you want to use it
            // gameDirectory = project.file("run-data")

            // Specify the modid for data generation, where to output the resulting resource, and where to look for existing resources.
            programArguments.addAll(
                "--mod", mod_id,
                "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath
            )
        }

        // applies to all the run configs above
        configureEach {
            // Recommended logging data for a userdev environment
            // The markers can be added/remove as needed separated by commas.
            // "SCAN": For mods scan.
            // "REGISTRIES": For firing of registry events.
            // "REGISTRYDUMP": For getting the contents of all registries.
            systemProperty("forge.logging.markers", "REGISTRIES")

            // Recommended logging level for the console
            // You can set various levels here.
            // Please read: https://stackoverflow.com/questions/2031163/when-to-use-the-different-log-levels
            logLevel = Level.DEBUG

            // Plain (non-mod) jarJar'd library dependencies -- see the `dependencies` block's
            // comment on engine:audio for why this is needed on top of `implementation`/`jarJar`.
            // Each RunModel's `additionalRuntimeClasspathConfiguration` is a real per-run
            // Configuration (named e.g. "clientAdditionalRuntimeClasspath"), not a DSL function,
            // so dependencies are added to it by name via the project's DependencyHandler.
            // (engine:audio is plain Java specifically so it has no transitive kotlin-stdlib to
            // fight over here -- an earlier Kotlin version of it hit a "reads more than one
            // module named kotlin.stdlib" JPMS error from ending up in a different module layer
            // than the copy already on the main classpath. See engine/audio/build.gradle.kts.)
            project.dependencies.add(additionalRuntimeClasspathConfiguration.name, "engine:audio:1.0")
            project.dependencies.add(additionalRuntimeClasspathConfiguration.name, "com.plasmoverse:opus-jni-rust:1.0.4")
            project.dependencies.add(additionalRuntimeClasspathConfiguration.name, "engine:fmod:1.0")
        }
    }

    mods {
        // define mod <-> source bindings
        // these are used to tell the game which sources are for which mod
        // mostly optional in a single mod project
        // but multi mod projects should define one per mod
        create(mod_id) {
            sourceSet(sourceSets["main"])
        }
    }
}

// Include resources generated by data generators.
sourceSets["main"].resources.srcDir("src/generated/resources")

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:5.3.0")

    // TACZ, included as a composite build submodule (mods/TACZ-1.21.1) so it
    // shares this project's NeoForge version and Minecraft artifact instead
    // of generating its own.
    implementation("com.tacz:tacz-neoforge-1.21.1:${mods_tacz_version}")

    // First Aid, included as a composite build submodule
    // (mods/First-Aid-New/neoforge1.21.1) for the same reason as TACZ.
    implementation("ichttt.mods.firstaid:firstaid:${mods_firstaid_version}")

    // Automobility, included as a composite build submodule (mods/Automobility, neoforge
    // subproject only -- see settings.gradle.kts) for the same reason as TACZ/First Aid.
    implementation("io.github.foundationgames:automobility:${mods_automobility_version}")

    // engine/audio (miniaudio JNI capture/playback bridge), wired in via includeBuild in
    // settings.gradle.kts -- see io.github.jwyoon1220.dncity.audio.NativeAudio. jarJar embeds it
    // (same pattern TACZ uses for engine/fmod) so it's not just a dev-run classpath artifact --
    // it ships inside the mod jar and reaches players. jarJar's embedding only actually takes
    // effect for an already-packaged mod jar (FML's jar-in-jar extraction runs against a real
    // jar file) -- on MC 1.21.1's ModDevGradle, runClient/runClient2/runServer instead launch
    // straight from this project's compiled classes directory, which has no jar to extract from,
    // so a plain library dependency (not a mod, so FML's own mod-file loader won't pick it up
    // either) silently never reaches the dev-run classpath without *also* being added to each
    // run's `additionalRuntimeClasspath` below (see the `runs { configureEach { ... } }` block).
    // (Confirmed by a NoClassDefFoundError on NativeAudio when that was missing; see
    // https://docs.neoforged.net/toolchain/docs/dependencies/nonmclibs/ -- fixed in 1.21.9+,
    // where jarJar deps are added to run classpaths automatically.)
    implementation("engine:audio:1.0")
    jarJar("engine:audio:1.0")

    // Opus codec (close-range voice chat tier -- see io.github.jwyoon1220.dncity.audio.OpusCodec)
    // via opus-jni-rust, a prebuilt multi-platform (win/linux/mac, x86/x86_64/aarch64) JNI
    // binding, jarJar'd for the same reason as engine:audio above (also needs
    // additionalRuntimeClasspath, see the `runs` block).
    implementation("com.plasmoverse:opus-jni-rust:1.0.4")
    jarJar("com.plasmoverse:opus-jni-rust:1.0.4")

    // engine/fmod (hand-written JNI FMOD bindings, see CLAUDE.md) -- was already an indirect
    // dependency via TACZ's own jarJar, but that's TACZ's private copy; depending on it directly
    // here gives DNCity its own FMODCoreSystem instance (io.github.jwyoon1220.dncity.music.
    // AudioPlayer) for direct OGG/FLAC/MP3/Opus file playback, independent of TACZ being
    // installed at all. Same version as TACZ's own dependency (1.0), so FML's jar-in-jar
    // deduplication (same group:artifact:version embedded by two mods resolves to one shared
    // copy) applies rather than loading the native library twice.
    implementation("engine:fmod:1.0")
    jarJar("engine:fmod:1.0")

    // Example mod dependency with JEI
    // The JEI API is declared for compile time use, while the full JEI artifact is used at runtime
    // compileOnly("mezz.jei:jei-${mc_version}-common-api:${jei_version}")
    // compileOnly("mezz.jei:jei-${mc_version}-forge-api:${jei_version}")
    // runtimeOnly("mezz.jei:jei-${mc_version}-forge:${jei_version}")

    // Example mod dependency using a mod jar from ./libs with a flat dir repository
    // This maps to ./libs/coolmod-${mc_version}-${coolmod_version}.jar
    // The group id is ignored when searching -- in this case, it is "blank"
    // implementation("blank:coolmod-${mc_version}:${coolmod_version}")

    // Example mod dependency using a file as dependency
    // implementation(files("libs/coolmod-${mc_version}-${coolmod_version}.jar"))

    // Example project dependency using a sister or child project:
    // implementation(project(":myproject"))

    // For more info:
    // http://www.gradle.org/docs/current/userguide/artifact_dependencies_tutorial.html
    // http://www.gradle.org/docs/current/userguide/dependency_management.html
}

// This block of code expands all declared replace properties in the specified resource targets.
// A missing property will result in an error. Properties are expanded using ${} Groovy notation.
val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version" to minecraft_version,
        "minecraft_version_range" to minecraft_version_range,
        "neo_version" to neo_version,
        "neo_version_range" to neo_version_range,
        "loader_version_range" to loader_version_range,
        "mod_id" to mod_id,
        "mod_name" to mod_name,
        "mod_license" to mod_license,
        "mod_version" to mod_version,
        "mod_authors" to mod_authors,
        "mod_description" to mod_description
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from("src/main/templates")
    into("build/generated/sources/modMetadata")
}

// Include the output of "generateModMetadata" as an input directory for the build
// this works with both building through Gradle and the IDE.
sourceSets["main"].resources.srcDir(generateModMetadata)
// To avoid having to run "generateModMetadata" manually, make it run on every project reload
neoForge.ideSyncTask(generateModMetadata)

// Example configuration to allow publishing using the maven-publish plugin
publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            url = uri("file://${project.projectDir}/repo")
        }
    }
}

// IDEA no longer automatically downloads sources/javadoc jars for dependencies, so we need to explicitly enable the behavior.
idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

// Resolves the actual Sodium/Iris jar files (same versions TACZ already depends on for its own
// compat code, see mods_sodium_version/mods_iris_version above -- already in the local Gradle
// cache from that, so this doesn't trigger a new download) so collectDistributionJars below can
// copy them alongside everything else. A detached configuration, not the main dependencies block,
// since neither is an actual compile/runtime dependency of this project -- just something to hand
// players alongside it.
val sodiumIrisDistribution = configurations.detachedConfiguration(
    dependencies.create("maven.modrinth:sodium:${mods_sodium_version}"),
    dependencies.create("maven.modrinth:iris:${mods_iris_version}"),
)

// Distributing this mod means handing out DNCity's own jar, the TACZ/First Aid submodule jars
// (see CLAUDE.md's "Composite builds / submodules" -- they're separate mods, not merged into this
// one), and Sodium/Iris (TACZ is built against them, and this pack is meant to be played with
// them) together, which by default land in three different build/libs directories (each
// submodule is its own included build) plus the Gradle dependency cache for Sodium/Iris. Copy all
// of them into this project's own build/libs so a plain "./gradlew build" leaves one folder with
// everything needed to distribute.
val collectDistributionJars = tasks.register<Copy>("collectDistributionJars") {
    group = "distribution"
    description = "Copies this project's jar, the TACZ/First Aid submodule jars, and Sodium/Iris into the root build/libs."

    dependsOn(gradle.includedBuild("TACZ-1.21.1").task(":jar"))
    dependsOn(gradle.includedBuild("First-Aid-New").task(":jar"))
    dependsOn(gradle.includedBuild("Automobility").task(":neoforge:jar"))

    from(sodiumIrisDistribution)

    // Excludes sources/javadoc jars in case either submodule's build ever starts producing them
    // (neither does today) -- only the runtime mod jar belongs in a distribution folder.
    from(file("mods/TACZ-1.21.1/build/libs")) {
        include("*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
    }
    from(file("mods/First-Aid-New/build/libs")) {
        include("*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
    }
    from(file("mods/Automobility/neoforge/build/libs")) {
        include("*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
    }
    into(layout.buildDirectory.dir("libs"))
}

tasks.named("assemble") {
    dependsOn(collectDistributionJars)
}
