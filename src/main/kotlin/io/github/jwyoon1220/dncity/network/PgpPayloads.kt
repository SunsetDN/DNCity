package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Server -> client, sent during the configuration phase (before the player ever reaches the
 * world): the random string the client must PGP-sign -- see
 * [io.github.jwyoon1220.dncity.security.PgpAuthConfigurationTask].
 */
data class PgpChallengePayload(val challenge: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PgpChallengePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "pgp_challenge"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, PgpChallengePayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PgpChallengePayload::challenge,
            ::PgpChallengePayload,
        )
    }
}

/**
 * Client -> server, sent during the configuration phase: the armored detached signature over the
 * challenge, computed client-side by [io.github.jwyoon1220.dncity.client.security.PgpAuthScreen].
 */
data class PgpResponsePayload(val signatureArmored: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PgpResponsePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "pgp_response"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, PgpResponsePayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PgpResponsePayload::signatureArmored,
            ::PgpResponsePayload,
        )
    }
}
