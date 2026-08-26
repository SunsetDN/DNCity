package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class RadioTunePayload(val slot: Int, val frequencyKhz: Double, val mode: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RadioTunePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "radio_tune"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioTunePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RadioTunePayload::slot,
            ByteBufCodecs.DOUBLE, RadioTunePayload::frequencyKhz,
            ByteBufCodecs.STRING_UTF8, RadioTunePayload::mode,
            ::RadioTunePayload,
        )
    }
}

data class RadioSetActivePayload(val slot: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RadioSetActivePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "radio_set_active"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioSetActivePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RadioSetActivePayload::slot,
            ::RadioSetActivePayload,
        )
    }
}

data class RadioSetPoweredPayload(val powered: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RadioSetPoweredPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "radio_set_powered"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioSetPoweredPayload> = StreamCodec.composite(
            ByteBufCodecs.BOOL, RadioSetPoweredPayload::powered,
            ::RadioSetPoweredPayload,
        )
    }
}
