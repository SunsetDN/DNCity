package io.github.jwyoon1220.dncity.phone

import io.github.jwyoon1220.dncity.network.PhoneCallAudioRelayPayload
import io.github.jwyoon1220.dncity.network.PhoneCallStatePayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

/**
 * Server-side 1:1 phone-call state machine and audio relay. One [Call] per pair of participants,
 * either ringing ([Call.active] `false`, waiting on [accept]/[decline]) or connected
 * ([Call.active] `true`). A player can only ever be in one call (ringing or active) at a time --
 * indexed by [callsByPlayer] on both participants' [UUID]s, so a lookup by either side finds the
 * same [Call] instance.
 */
object PhoneCallSession {
    private class Call(val caller: UUID, val callee: UUID, var active: Boolean)

    private val callsByPlayer = HashMap<UUID, Call>()

    /** Called from `network.PhoneNetworking`'s handler for [io.github.jwyoon1220.dncity.network.PhoneCallRequestPayload]. */
    fun requestCall(server: MinecraftServer, caller: ServerPlayer, number: String) {
        if (callsByPlayer.containsKey(caller.uuid)) return // already ringing/in a call -- ignore

        val calleeId = PhoneDirectory.lookup(number)
        if (calleeId == null) {
            sendState(caller, PhoneCallState.NO_SUCH_NUMBER, "")
            return
        }
        val callee = server.playerList.getPlayer(calleeId)
        if (callee == null) {
            sendState(caller, PhoneCallState.UNREACHABLE, "")
            return
        }
        if (callsByPlayer.containsKey(calleeId)) {
            sendState(caller, PhoneCallState.BUSY, "")
            return
        }

        val call = Call(caller.uuid, calleeId, active = false)
        callsByPlayer[caller.uuid] = call
        callsByPlayer[calleeId] = call
        sendState(caller, PhoneCallState.CALLING, callee.gameProfile.name)
        sendState(callee, PhoneCallState.INCOMING, caller.gameProfile.name)
    }

    /** Called from `network.PhoneNetworking`'s handler for [io.github.jwyoon1220.dncity.network.PhoneCallAcceptPayload]. */
    fun accept(server: MinecraftServer, callee: ServerPlayer) {
        val call = callsByPlayer[callee.uuid]?.takeIf { it.callee == callee.uuid && !it.active } ?: return
        val caller = server.playerList.getPlayer(call.caller)
        if (caller == null) {
            // Caller disconnected while this call was still ringing -- nothing to connect to.
            callsByPlayer.remove(call.caller)
            callsByPlayer.remove(call.callee)
            sendState(callee, PhoneCallState.ENDED, "")
            return
        }
        call.active = true
        sendState(caller, PhoneCallState.ACTIVE, callee.gameProfile.name)
        sendState(callee, PhoneCallState.ACTIVE, caller.gameProfile.name)
    }

    /** Called from `network.PhoneNetworking`'s handler for [io.github.jwyoon1220.dncity.network.PhoneCallDeclinePayload]. */
    fun decline(server: MinecraftServer, callee: ServerPlayer) {
        val call = callsByPlayer[callee.uuid]?.takeIf { it.callee == callee.uuid && !it.active } ?: return
        callsByPlayer.remove(call.caller)
        callsByPlayer.remove(call.callee)
        server.playerList.getPlayer(call.caller)?.let { sendState(it, PhoneCallState.DECLINED, "") }
    }

    /** Called from `network.PhoneNetworking`'s handler for [io.github.jwyoon1220.dncity.network.PhoneCallHangupPayload]. */
    fun hangup(server: MinecraftServer, player: ServerPlayer) = endCall(server, player)

    /** Called on player logout (`phone.PhoneServerEvents`) -- treated the same as an explicit hangup. */
    fun onDisconnect(server: MinecraftServer, player: ServerPlayer) = endCall(server, player)

    private fun endCall(server: MinecraftServer, player: ServerPlayer) {
        val call = callsByPlayer.remove(player.uuid) ?: return
        val peerId = if (call.caller == player.uuid) call.callee else call.caller
        callsByPlayer.remove(peerId)
        server.playerList.getPlayer(peerId)?.let { sendState(it, PhoneCallState.ENDED, "") }
    }

    /** Called from `network.PhoneNetworking`'s handler for [io.github.jwyoon1220.dncity.network.PhoneCallAudioPayload]. */
    fun relayAudio(sender: ServerPlayer, opusData: ByteArray) {
        val call = callsByPlayer[sender.uuid]?.takeIf { it.active } ?: return
        val peerId = if (call.caller == sender.uuid) call.callee else call.caller
        val peer = sender.serverLevel().server.playerList.getPlayer(peerId) ?: return
        PacketDistributor.sendToPlayer(peer, PhoneCallAudioRelayPayload(opusData))
    }

    private fun sendState(player: ServerPlayer, state: PhoneCallState, peerName: String) {
        PacketDistributor.sendToPlayer(player, PhoneCallStatePayload(state.ordinal, peerName))
    }
}
