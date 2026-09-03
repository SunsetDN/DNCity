package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

private const val MAX_NUMBER_LENGTH = 32
private const val MAX_NAME_LENGTH = 32

/**
 * Server -> client: this player's own phone number ([io.github.jwyoon1220.dncity.phone.PhoneDirectory],
 * auto-derived from their UUID), sent once on login (`phone.PhoneServerEvents`) so the dialer UI
 * can display it -- see [io.github.jwyoon1220.dncity.client.phone.PhoneCallManager.myNumber].
 */
class PhoneNumberAssignedPayload(val number: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhoneNumberAssignedPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "phone_number_assigned"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, PhoneNumberAssignedPayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_NUMBER_LENGTH), PhoneNumberAssignedPayload::number,
            ::PhoneNumberAssignedPayload,
        )
    }
}

/** Client -> server: place a call to [number] (looked up via [io.github.jwyoon1220.dncity.phone.PhoneDirectory]). */
class PhoneCallRequestPayload(val number: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhoneCallRequestPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "phone_call_request"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, PhoneCallRequestPayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_NUMBER_LENGTH), PhoneCallRequestPayload::number,
            ::PhoneCallRequestPayload,
        )
    }
}

/** Client -> server: accept the call currently ringing in for this player. No fields -- just a signal. */
class PhoneCallAcceptPayload : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhoneCallAcceptPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "phone_call_accept"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, PhoneCallAcceptPayload> =
            StreamCodec.unit(PhoneCallAcceptPayload())
    }
}

/** Client -> server: decline the call currently ringing in for this player. No fields -- just a signal. */
class PhoneCallDeclinePayload : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhoneCallDeclinePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "phone_call_decline"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, PhoneCallDeclinePayload> =
            StreamCodec.unit(PhoneCallDeclinePayload())
    }
}

/** Client -> server: end the current call, whether it's still ringing outgoing or connected. No fields -- just a signal. */
class PhoneCallHangupPayload : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhoneCallHangupPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "phone_call_hangup"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, PhoneCallHangupPayload> =
            StreamCodec.unit(PhoneCallHangupPayload())
    }
}

// Phone call audio itself no longer rides this channel -- see
// io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer/VoiceKcpClient (was PhoneCallAudioPayload/
// PhoneCallAudioRelayPayload).

/**
 * Server -> client: a call-state transition for this player -- drives both the phone UI's
 * dialer/incoming-call screen ([io.github.jwyoon1220.dncity.client.phone.PhoneCallManager]) and
 * whether [io.github.jwyoon1220.dncity.voice.VoiceClientLoop] routes captured mic audio to the
 * phone channel. [state] is [io.github.jwyoon1220.dncity.phone.PhoneCallState]'s ordinal;
 * [peerName] is the other participant's name (empty string for states with no peer, e.g.
 * `ENDED`/`BUSY`/`NO_SUCH_NUMBER`/`UNREACHABLE`).
 */
class PhoneCallStatePayload(val state: Int, val peerName: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhoneCallStatePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "phone_call_state"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, PhoneCallStatePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PhoneCallStatePayload::state,
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), PhoneCallStatePayload::peerName,
            ::PhoneCallStatePayload,
        )
    }
}
