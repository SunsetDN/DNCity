package io.github.jwyoon1220.dncity.network.kcp

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.client.phone.PhoneCallReceiver
import io.github.jwyoon1220.dncity.voice.ClientRadioReceiver
import io.github.jwyoon1220.dncity.voice.ClientVoiceReceiver
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import net.minecraft.client.Minecraft
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * The client side of [VoiceKcpServer] -- see that class's doc comment for the overall design.
 * Opens its own UDP socket as soon as the server's one-time handshake payload arrives
 * (`network.VoiceKcpHandshakePayload`, sent on login), authenticates with a signed
 * [VoiceKcpProtocol.encodeHello], then carries every close-range voice/radio/phone-call audio
 * frame in both directions for the rest of the session.
 */
object VoiceKcpClient {
    private const val TICK_INTERVAL_MS = 10L

    private var group: NioEventLoopGroup? = null
    private var channel: Channel? = null
    private var kcp: Kcp? = null

    fun connect(host: String, port: Int, conv: Int, secret: Long) {
        disconnect()
        val eventLoopGroup = NioEventLoopGroup(1)
        group = eventLoopGroup
        val remote = InetSocketAddress(host, port)
        try {
            val bootstrap = Bootstrap()
                .group(eventLoopGroup)
                .channel(NioDatagramChannel::class.java)
                .handler(object : ChannelInitializer<NioDatagramChannel>() {
                    override fun initChannel(ch: NioDatagramChannel) {
                        ch.pipeline().addLast(object : SimpleChannelInboundHandler<DatagramPacket>() {
                            override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
                                val bytes = ByteArray(msg.content().readableBytes())
                                msg.content().readBytes(bytes)
                                kcp?.input(bytes)
                                drainReceived()
                            }
                        })
                    }
                })
            val ch = bootstrap.bind(0).sync().channel()
            channel = ch
            val session = Kcp(conv) { out -> ch.writeAndFlush(DatagramPacket(Unpooled.wrappedBuffer(out), remote)) }
            kcp = session
            session.send(VoiceKcpProtocol.encodeHello(conv, secret))
            eventLoopGroup.next().scheduleAtFixedRate({ tick() }, 0, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Dncity.LOGGER.error("VoiceKcpClient failed to connect to {}:{} -- voice/radio/phone audio disabled this session", host, port, e)
            disconnect()
        }
    }

    fun disconnect() {
        channel?.close()
        group?.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS)
        channel = null
        group = null
        kcp = null
    }

    /** Same host the game's own TCP connection uses -- an integrated (singleplayer) server has no
     * real socket to read that from, so falls back to loopback since it's running in this same
     * process either way. */
    fun resolveHost(): String {
        val minecraft = Minecraft.getInstance()
        if (minecraft.hasSingleplayerServer()) return "127.0.0.1"
        return minecraft.currentServer?.ip?.substringBeforeLast(':') ?: "127.0.0.1"
    }

    fun sendVoiceAudio(opusData: ByteArray) {
        kcp?.send(VoiceKcpProtocol.encodeAudio(VoiceKcpProtocol.TYPE_VOICE_AUDIO, opusData))
    }

    fun sendRadioAudio(audioData: ByteArray) {
        kcp?.send(VoiceKcpProtocol.encodeAudio(VoiceKcpProtocol.TYPE_RADIO_AUDIO, audioData))
    }

    fun sendPhoneCallAudio(opusData: ByteArray) {
        kcp?.send(VoiceKcpProtocol.encodeAudio(VoiceKcpProtocol.TYPE_PHONE_CALL_AUDIO, opusData))
    }

    private fun tick() {
        kcp?.update(System.currentTimeMillis())
        drainReceived()
    }

    private fun drainReceived() {
        val session = kcp ?: return
        while (true) {
            val msg = session.recv() ?: break
            handleMessage(msg)
        }
    }

    private fun handleMessage(msg: ByteArray) {
        if (msg.isEmpty()) return
        // Hop onto the render/client thread -- these calls feed VoiceAudioMixer, which
        // VoiceClientLoop also drains from that same thread every client tick; like the
        // IPayloadHandler.enqueueWork bodies this replaced, they're not meant to run on an
        // arbitrary I/O thread (this UDP channel's own Netty event loop, see connect()).
        Minecraft.getInstance().execute {
            when (msg[0]) {
                VoiceKcpProtocol.TYPE_VOICE_AUDIO_RELAY -> {
                    val relay = VoiceKcpProtocol.decodeVoiceRelay(msg)
                    if (!relay.isStale) ClientVoiceReceiver.handleRelayedFrame(relay.senderEntityId, relay.audio)
                }
                VoiceKcpProtocol.TYPE_RADIO_AUDIO_RELAY -> {
                    val relay = VoiceKcpProtocol.decodeRadioRelay(msg)
                    if (!relay.isStale) {
                        ClientRadioReceiver.handleRelayedFrame(
                            relay.senderEntityId, relay.audio, relay.frequencyKhz, relay.modeName,
                            relay.senderPosition, relay.rangeInfo.effectiveMaxRangeBlocks, relay.rangeInfo.obstructed, relay.rangeInfo.stormNoise,
                        )
                    }
                }
                VoiceKcpProtocol.TYPE_PHONE_CALL_AUDIO_RELAY -> {
                    val audio = VoiceKcpProtocol.decodeAudio(msg)
                    if (!audio.isStale) PhoneCallReceiver.handleFrame(audio.audio)
                }
            }
        }
    }
}
