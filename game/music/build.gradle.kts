// AGENT-DONE(claude): feature-mods
// dncity_music: /music (server-sent MIDI and audio files). Shared setup is in the root build.gradle.kts (featureMods).

dependencies {
    // engine/fmod: this mod's own FMOD Core system (AudioPlayer), independent of TACZ's Studio system. Same version
    // as TACZ's, so jar-in-jar resolves both to one copy.
    "implementation"("engine:fmod:1.0")
    "jarJar"("engine:fmod:1.0")
}
