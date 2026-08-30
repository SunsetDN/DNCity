package io.github.jwyoon1220.dncity.phone

import java.util.UUID

/**
 * Server-side, in-memory phone-number registry. Numbers are never chosen by players -- [numberOf]
 * derives an [DIGITS]-digit decimal number deterministically from a player's [UUID] the first
 * time it's asked for (and caches it thereafter), so the same player always gets the same number
 * across sessions without needing any registration step. [PhoneCallSession] looks numbers up via
 * [lookup] when a call is placed. Deliberately not persisted: the cache repopulates itself
 * on-demand (same derivation every time), and this mod has no config/save system anyway (see
 * CLAUDE.md's `VoiceSettingsScreen` precedent).
 */
object PhoneDirectory {
    private const val DIGITS = 8
    private const val MODULUS = 100_000_000L // 10^DIGITS

    private val numberToPlayer = HashMap<String, UUID>()
    private val playerToNumber = HashMap<UUID, String>()

    /** This player's phone number -- derived from [hash] on first call, cached (and stable) thereafter. */
    fun numberOf(player: UUID): String {
        playerToNumber[player]?.let { return it }
        return assign(player)
    }

    fun lookup(number: String): UUID? = numberToPlayer[number]

    private fun assign(player: UUID): String {
        var number = hash(player)
        // Collision resolution: two different UUIDs hashing to the same DIGITS-digit number is
        // astronomically unlikely at any real player count, but walk forward to the next free
        // number rather than letting two players silently share one.
        while (numberToPlayer[number].let { it != null && it != player }) {
            number = next(number)
        }
        numberToPlayer[number] = player
        playerToNumber[player] = number
        return number
    }

    /**
     * XORs the UUID's two 64-bit halves rather than using [UUID.hashCode] (which folds all 128
     * bits down through only a 32-bit int) -- keeps more of the UUID's entropy before reducing
     * mod [MODULUS]. Masked to non-negative before the mod so the result is always exactly
     * [DIGITS] digits once zero-padded, never a negative number.
     */
    private fun hash(player: UUID): String {
        val bits = (player.mostSignificantBits xor player.leastSignificantBits) and Long.MAX_VALUE
        return (bits % MODULUS).toString().padStart(DIGITS, '0')
    }

    private fun next(number: String): String =
        ((number.toLong() + 1) % MODULUS).toString().padStart(DIGITS, '0')
}
