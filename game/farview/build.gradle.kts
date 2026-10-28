// AGENT-DONE(claude): feature-mods
// dncity_farview: far players over Distant Horizons' LOD terrain, server-side sight check. Shared setup is in the root
// build.gradle.kts (featureMods).

val mods_superbwarfare_version: String by project

dependencies {
    "compileOnly"("com.atsuishio.superbwarfare:superbwarfare:${mods_superbwarfare_version}")
    // Distant Horizons' remapJar output, as in the root build (see its comment there); the dev runs load it from the
    // root project.
    "compileOnly"(fileTree(rootDir.resolve("mods/distant-horizons/neoforge/build/libs")) {
        include("DistantHorizons-neoforge-*.jar")
        exclude("*-all.jar", "*-sources.jar", "*-dev.jar")
    })
}

listOf("compileJava", "compileKotlin").forEach { taskName ->
    tasks.named(taskName) {
        dependsOn(gradle.includedBuild("distant-horizons").task(":neoforge:remapJar"))
    }
}
