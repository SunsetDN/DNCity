package io.github.jwyoon1220.dncity.security

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Generates the per-connection random challenge string the client must PGP-sign to log in (see
 * [PgpAuthServerEvents]). The player id and a fixed protocol tag are folded into the string
 * alongside the random nonce so a signature over one challenge can't be replayed against a
 * different player or a different protocol that also happens to PGP-sign arbitrary strings.
 */
object PgpChallengeGenerator {
    private val random = SecureRandom()

    fun generate(playerId: UUID): String {
        val nonce = ByteArray(32)
        random.nextBytes(nonce)
        return "dncity-pgp-auth:v1:$playerId:${Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)}"
    }
}
