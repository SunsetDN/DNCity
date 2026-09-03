package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Server -> client, sent once on login: bootstraps the client's
 * [io.github.jwyoon1220.dncity.network.kcp.VoiceKcpClient] UDP session (conv id, shared secret,
 * and the server's KCP port) so all subsequent close-range voice/radio/phone-call audio can flow
 * entirely outside Minecraft's own packet channel -- see
 * [io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer]'s doc comment. Carries no audio itself;
 * this is the one piece of unavoidable bootstrap plumbing a raw UDP session needs, since the
 * client has no other way to learn which session is its own.
 */
class VoiceKcpHandshakePayload(val conv: Int, val secret: Long, val port: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<VoiceKcpHandshakePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "voice_kcp_handshake"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, VoiceKcpHandshakePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VoiceKcpHandshakePayload::conv,
            ByteBufCodecs.VAR_LONG, VoiceKcpHandshakePayload::secret,
            ByteBufCodecs.VAR_INT, VoiceKcpHandshakePayload::port,
            ::VoiceKcpHandshakePayload,
        )
    }
}
