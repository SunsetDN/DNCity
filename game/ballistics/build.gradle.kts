// AGENT-DONE(claude): feature-mods
// dncity_ballistics: shells, terminal ballistics, ammo, small arms, vehicle armor and damage, cartridges. Shared setup
// is in the root build.gradle.kts (featureMods).

val mods_tacz_version: String by project
val mods_firstaid_version: String by project
val mods_superbwarfare_version: String by project

dependencies {
    // Optional at runtime (the compat bridges register only when the mod is loaded); the dev runs load them from the
    // root project. Plain JVM tests that touch their classes get them on the test classpath too.
    "compileOnly"("com.tacz:tacz-neoforge-1.21.1:${mods_tacz_version}")
    "compileOnly"("ichttt.mods.firstaid:firstaid:${mods_firstaid_version}")
    "compileOnly"("com.atsuishio.superbwarfare:superbwarfare:${mods_superbwarfare_version}")
    "compileOnly"("io.netty:netty-transport:4.1.97.Final")
}
