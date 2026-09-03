package io.github.jwyoon1220.dncity.client.security

import io.github.jwyoon1220.dncity.network.PgpChallengePayload
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client-side handler for [io.github.jwyoon1220.dncity.network.PgpNetworking]'s configuration-phase
 * challenge payload -- opens [PgpAuthScreen] to collect the player's passphrase and sign it. Same
 * "referenced only from inside a `configurationToClient` closure" pattern as
 * [io.github.jwyoon1220.dncity.voice.ClientRadioStationReceiver] for why it's safe to reference
 * client-only classes here despite `PgpNetworking.onRegisterPayloadHandlers` itself running on
 * both sides -- a dedicated server never actually invokes this handler, so this class is never
 * loaded there.
 */
object ClientPgpAuthReceiver {
    fun handleChallenge(payload: PgpChallengePayload, context: IPayloadContext) {
        Minecraft.getInstance().setScreen(PgpAuthScreen(payload.challenge, context))
    }
}
