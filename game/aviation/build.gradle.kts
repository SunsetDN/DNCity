// AGENT-DONE(claude): feature-mods
// dncity_aviation: aircraft flight model for SuperbWarfare's aircraft. Shared setup is in the root build.gradle.kts
// (featureMods).

val mods_superbwarfare_version: String by project

dependencies {
    // SI flight integrator and atmosphere (ballistics.flight) live in dncity_ballistics.
    "implementation"(project(":game:ballistics"))
    "compileOnly"("com.atsuishio.superbwarfare:superbwarfare:${mods_superbwarfare_version}")
}
