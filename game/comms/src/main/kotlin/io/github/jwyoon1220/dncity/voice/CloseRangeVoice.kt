package io.github.jwyoon1220.dncity.voice

/**
 * Distance rules for the close-range voice chat tier (full-duplex, no PTT -- see the radio
 * voice-chat design plan's tier table). Pure math, no networking/audio-device concerns, so both
 * the server (who's in range at all) and the client (how loud they sound) can share it.
 */
object CloseRangeVoice {

    /** Beyond this distance, a speaker is inaudible -- the server won't even relay to them. */
    const val MAX_RANGE_BLOCKS = 32.0

    /**
     * Playback gain for a listener [distanceBlocks] away from the speaker, in `0f..1f`. Falls
     * off quadratically (rather than linearly) with distance: real sound intensity falls off
     * with the square of distance, and a quadratic curve also *sounds* more natural than a
     * linear one, which reads as artificially loud until it abruptly starts fading near the
     * cutoff. Full volume at distance 0, silence at/beyond [MAX_RANGE_BLOCKS].
     */
    fun gain(distanceBlocks: Double): Float {
        if (distanceBlocks <= 0.0) return 1f
        if (distanceBlocks >= MAX_RANGE_BLOCKS) return 0f
        val linear = 1.0 - (distanceBlocks / MAX_RANGE_BLOCKS)
        return (linear * linear).toFloat()
    }

    /** Whether a listener [distanceBlocks] away can hear this tier at all. */
    fun isInRange(distanceBlocks: Double): Boolean = distanceBlocks < MAX_RANGE_BLOCKS
}
