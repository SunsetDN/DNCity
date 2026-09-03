package io.github.jwyoon1220.dncity.security

import java.io.File
import java.util.UUID

/**
 * Server-side registry of one armored PGP public key per player UUID, backed by a plain file per
 * player under [PgpPaths.keysDir] -- no in-memory cache, since this is only ever read once per
 * login (see [PgpAuthServerEvents]/[PgpAuthManager]), not on any hot path.
 */
object PgpKeyRegistry {
    private fun keyFile(playerId: UUID): File = File(PgpPaths.keysDir, "$playerId.asc")

    fun hasKey(playerId: UUID): Boolean = keyFile(playerId).isFile

    fun getKey(playerId: UUID): String? = keyFile(playerId).takeIf { it.isFile }?.readText()

    fun register(playerId: UUID, armoredPublicKey: String) {
        keyFile(playerId).writeText(armoredPublicKey)
    }

    fun unregister(playerId: UUID): Boolean = keyFile(playerId).delete()

    fun registeredPlayerIds(): List<UUID> =
        PgpPaths.keysDir.listFiles { f -> f.extension == "asc" }
            ?.mapNotNull { runCatching { UUID.fromString(it.nameWithoutExtension) }.getOrNull() }
            ?: emptyList()
}
