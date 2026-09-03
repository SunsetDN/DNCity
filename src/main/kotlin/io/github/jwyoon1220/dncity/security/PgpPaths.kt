package io.github.jwyoon1220.dncity.security

import net.neoforged.fml.loading.FMLPaths
import java.io.File

/**
 * Where PGP login-gate data lives, each side only ever touching its own files (see
 * [PgpKeyRegistry] for the server-side registry, [io.github.jwyoon1220.dncity.client.security.PgpAuthScreen]
 * for the client's own secret key) -- same `<config>/dncity/...`-rooted convention as
 * [io.github.jwyoon1220.dncity.music.MusicPaths].
 *
 * - `keys/<uuid>.asc` -- one registered armored public key per player, written by `/pgp register`.
 * - `incoming/<playername>.asc` -- staging area an operator drops a player's exported public key
 *   into (out-of-band, e.g. via Kleopatra's "Export Public Keys...") before running
 *   `/pgp register <playername>` to move it into `keys/`.
 * - `secret.asc` -- the player's own exported secret key (Kleopatra's "Export Secret Keys..."),
 *   placed by the player themselves into their own client's config folder. Never read by the
 *   server -- the passphrase that unlocks it never leaves that machine either, only the resulting
 *   signature does (see [PgpCrypto.sign]).
 */
object PgpPaths {
    private val configDir: File by lazy { FMLPaths.CONFIGDIR.get().resolve("dncity/security/pgp").toFile() }

    val keysDir: File get() = File(configDir, "keys").apply { mkdirs() }
    val incomingDir: File get() = File(configDir, "incoming").apply { mkdirs() }
    val secretKeyFile: File get() = File(configDir, "secret.asc")
}
