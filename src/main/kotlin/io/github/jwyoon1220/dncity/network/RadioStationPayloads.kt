package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

private const val MAX_NAME_LENGTH = 32
private const val MAX_BROADCASTERS = 64

/**
 * Server -> client: sent when a player right-clicks a
 * [io.github.jwyoon1220.dncity.block.RadioStationBlock] (see
 * [io.github.jwyoon1220.dncity.block.RadioStationBlock.useWithoutItem]), carrying everything
 * [io.github.jwyoon1220.dncity.client.RadioStationScreen] needs to render -- the station's current
 * tuning, who's currently broadcasting through it (see
 * [io.github.jwyoon1220.dncity.block.RadioStationBlockEntity.broadcasterIds], resolved to display
 * names server-side since the client has no reliable way to resolve a UUID it might not have seen
 * before), and whether the opening player themself is already one of them (so the screen shows
 * "나가기" instead of "발신 참여하기").
 */
data class RadioStationOpenPayload(
    val blockPos: BlockPos,
    val stationName: String,
    val frequencyKhz: Double,
    val mode: String,
    val broadcasterNames: List<String>,
    val joinedByMe: Boolean,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RadioStationOpenPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "radio_station_open"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioStationOpenPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RadioStationOpenPayload::blockPos,
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), RadioStationOpenPayload::stationName,
            ByteBufCodecs.DOUBLE, RadioStationOpenPayload::frequencyKhz,
            ByteBufCodecs.STRING_UTF8, RadioStationOpenPayload::mode,
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).apply(ByteBufCodecs.list(MAX_BROADCASTERS)), RadioStationOpenPayload::broadcasterNames,
            ByteBufCodecs.BOOL, RadioStationOpenPayload::joinedByMe,
            ::RadioStationOpenPayload,
        )
    }
}

/** Client -> server: from [io.github.jwyoon1220.dncity.client.RadioStationScreen]'s "설정"
 * (configure) button -- anyone can retune a station's name/frequency/mode, same
 * no-ownership-gating precedent as the rest of this mod's radio system. Server re-validates
 * [frequencyKhz]/[mode] against [io.github.jwyoon1220.dncity.radio.RadioBandGroup.BROADCAST]
 * exactly like [io.github.jwyoon1220.dncity.radio.RadioActions.tune] does for a handheld radio. */
data class RadioStationConfigurePayload(
    val blockPos: BlockPos,
    val stationName: String,
    val frequencyKhz: Double,
    val mode: String,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RadioStationConfigurePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "radio_station_configure"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioStationConfigurePayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RadioStationConfigurePayload::blockPos,
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), RadioStationConfigurePayload::stationName,
            ByteBufCodecs.DOUBLE, RadioStationConfigurePayload::frequencyKhz,
            ByteBufCodecs.STRING_UTF8, RadioStationConfigurePayload::mode,
            ::RadioStationConfigurePayload,
        )
    }
}

/** Client -> server: join or leave broadcasting through the station at [blockPos] (see
 * [io.github.jwyoon1220.dncity.radio.RadioStationRegistry]). Joining a new station while already
 * joined to a different one implicitly leaves the old one -- there's no need for an explicit leave
 * first. */
data class RadioStationJoinPayload(val blockPos: BlockPos, val joining: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RadioStationJoinPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "radio_station_join"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioStationJoinPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RadioStationJoinPayload::blockPos,
            ByteBufCodecs.BOOL, RadioStationJoinPayload::joining,
            ::RadioStationJoinPayload,
        )
    }
}

/**
 * Server -> client: confirms a join/leave for the requesting player specifically -- drives both
 * [io.github.jwyoon1220.dncity.voice.RadioStationClientState] (which station, if any,
 * [io.github.jwyoon1220.dncity.voice.RadioTransmitter] should route PTT audio to instead of a
 * held radio item) and the action-bar reminder while joined. [stationName]/[frequencyKhz] are
 * meaningless (left at their default/zero) when [joined] is `false`.
 */
data class RadioStationMembershipPayload(
    val joined: Boolean,
    val blockPos: BlockPos,
    val stationName: String,
    val frequencyKhz: Double,
    val mode: String,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RadioStationMembershipPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "radio_station_membership"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioStationMembershipPayload> = StreamCodec.composite(
            ByteBufCodecs.BOOL, RadioStationMembershipPayload::joined,
            BlockPos.STREAM_CODEC, RadioStationMembershipPayload::blockPos,
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), RadioStationMembershipPayload::stationName,
            ByteBufCodecs.DOUBLE, RadioStationMembershipPayload::frequencyKhz,
            ByteBufCodecs.STRING_UTF8, RadioStationMembershipPayload::mode,
            ::RadioStationMembershipPayload,
        )
    }
}
