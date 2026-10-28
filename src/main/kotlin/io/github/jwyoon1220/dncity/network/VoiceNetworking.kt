package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpClient
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

/**
 * Registers only the one-time KCP handshake payload now -- all actual close-range voice/radio/
 * phone-call audio moved off Minecraft's own channel entirely onto
 * [io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer]/[VoiceKcpClient] (a self-built
 * KCP-over-Netty UDP transport). See [VoiceKcpHandshakePayload].
 */
object VoiceNetworking {

    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        // "2": audio moved to raw datagrams (VoiceKcpProtocol.RAW_DATAGRAM_CMD), which a "1" peer would feed
        // into KCP as garbage segments -- a version mismatch now refuses the connection instead
        val registrar = event.registrar("2")

        registrar.playToClient(VoiceKcpHandshakePayload.TYPE, VoiceKcpHandshakePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                VoiceKcpClient.connect(VoiceKcpClient.resolveHost(), payload.port, payload.conv, payload.secret)
            }
        })
    }
}
