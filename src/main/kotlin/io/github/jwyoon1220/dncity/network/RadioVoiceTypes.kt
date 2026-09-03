package io.github.jwyoon1220.dncity.network

/**
 * The transmitter's world position at the moment it sent a frame, as of
 * [io.github.jwyoon1220.dncity.voice.RadioRelay]'s own snapshot of it. Plain value holder now --
 * radio audio (and its relay) moved off Minecraft's packet channel onto
 * [io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer]/[io.github.jwyoon1220.dncity.network.kcp.VoiceKcpClient],
 * so this no longer needs its own [net.minecraft.network.codec.StreamCodec] -- see
 * [io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol] for the wire encoding.
 */
data class RadioSenderPosition(val x: Double, val y: Double, val z: Double)

/**
 * [effectiveMaxRangeBlocks] and the obstructed/storm-noise flags for one radio relay frame,
 * bundled together the same way [RadioSenderPosition] bundles a position -- see that class's doc
 * comment for why this is no longer a [net.minecraft.network.protocol.common.custom.CustomPacketPayload]
 * field type.
 */
data class RadioRangeInfo(val effectiveMaxRangeBlocks: Double, private val flags: Int) {
    val obstructed: Boolean get() = (flags and FLAG_OBSTRUCTED) != 0
    val stormNoise: Boolean get() = (flags and FLAG_STORM_NOISE) != 0

    companion object {
        private const val FLAG_OBSTRUCTED = 1
        private const val FLAG_STORM_NOISE = 2

        fun of(effectiveMaxRangeBlocks: Double, obstructed: Boolean, stormNoise: Boolean) = RadioRangeInfo(
            effectiveMaxRangeBlocks,
            (if (obstructed) FLAG_OBSTRUCTED else 0) or (if (stormNoise) FLAG_STORM_NOISE else 0),
        )

        fun flagsOf(info: RadioRangeInfo) = (if (info.obstructed) FLAG_OBSTRUCTED else 0) or (if (info.stormNoise) FLAG_STORM_NOISE else 0)
    }
}
