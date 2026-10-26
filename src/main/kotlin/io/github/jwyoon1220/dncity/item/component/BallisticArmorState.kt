// AGENT-DONE(claude): armor-wear-suppression
package io.github.jwyoon1220.dncity.item.component

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/**
 * What is left of an armor piece's ballistic protection ([io.github.jwyoon1220.dncity.ballistics.smallarms.BodyArmorTable]):
 * [integrity] 0..1 scales the energy it takes out of a bullet, [hits] counts the bullets it took. Stamped (fresh) onto a ballistic
 * piece when it is equipped, so the client knows which pieces count for the HUD bar without the server's armor table.
 */
data class BallisticArmorState(val integrity: Float, val hits: Int) {
    val spent: Boolean get() = integrity <= 0f

    companion object {
        val FRESH = BallisticArmorState(1f, 0)

        val CODEC: Codec<BallisticArmorState> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.floatRange(0f, 1f).fieldOf("integrity").forGetter(BallisticArmorState::integrity),
                Codec.intRange(0, Int.MAX_VALUE).fieldOf("hits").forGetter(BallisticArmorState::hits),
            ).apply(i, ::BallisticArmorState)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, BallisticArmorState> = StreamCodec.composite(
            ByteBufCodecs.FLOAT, BallisticArmorState::integrity,
            ByteBufCodecs.VAR_INT, BallisticArmorState::hits,
            ::BallisticArmorState,
        )
    }
}
