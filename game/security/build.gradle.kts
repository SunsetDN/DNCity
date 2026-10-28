// AGENT-DONE(claude): feature-mods
// dncity_security: PGP login gate. Shared setup is in the root build.gradle.kts (featureMods).

dependencies {
    // BouncyCastle OpenPGP (PgpCrypto), not shipped by NeoForge/Minecraft.
    "implementation"("org.bouncycastle:bcpg-jdk18on:1.78.1")
    "implementation"("org.bouncycastle:bcprov-jdk18on:1.78.1")
    "jarJar"("org.bouncycastle:bcpg-jdk18on:1.78.1")
    "jarJar"("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
