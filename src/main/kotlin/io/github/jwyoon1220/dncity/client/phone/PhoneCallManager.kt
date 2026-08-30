package io.github.jwyoon1220.dncity.client.phone

import io.github.jwyoon1220.dncity.network.PhoneCallAcceptPayload
import io.github.jwyoon1220.dncity.network.PhoneCallDeclinePayload
import io.github.jwyoon1220.dncity.network.PhoneCallHangupPayload
import io.github.jwyoon1220.dncity.network.PhoneCallRequestPayload
import io.github.jwyoon1220.dncity.phone.PhoneCallState
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Client-side phone-call state, driven from two directions: the phone UI's dialer
 * ([connect]/[accept]/[decline]/[hangup], called from [PhoneFragment]'s ModernUI screen)
 * and the server's authoritative state pushes ([handleServerState], called from
 * `network.PhoneNetworking`'s client-side payload handler). Opus VoIP, same codec as close-range
 * voice: [io.github.jwyoon1220.dncity.voice.VoiceClientLoop] reads [state] every capture-frame
 * flush to decide whether captured mic audio should route to [PhoneCallTransmitter] instead of
 * close-range/radio.
 */
object PhoneCallManager {
    // Shown to the UI for a few seconds after the call didn't connect or just ended, then
    // auto-clears back to IDLE (see [state]'s getter) -- simpler than needing the (stateless,
    // polling) UI to explicitly acknowledge a terminal state before it's cleared.
    private val TERMINAL_STATES = setOf(
        PhoneCallState.ENDED,
        PhoneCallState.DECLINED,
        PhoneCallState.BUSY,
        PhoneCallState.NO_SUCH_NUMBER,
        PhoneCallState.UNREACHABLE,
    )
    private const val TERMINAL_DISPLAY_MILLIS = 3000L

    @Volatile
    private var currentState: PhoneCallState = PhoneCallState.IDLE

    @Volatile
    private var currentPeerName: String = ""
    private var stateSetAtMillis: Long = 0L

    /** This player's own phone number, pushed by the server on login (see `PhoneNumberAssignedPayload`). Empty until then. */
    @Volatile
    var myNumber: String = ""
        private set

    /** Called by `network.PhoneNetworking`'s client-side handler for `PhoneNumberAssignedPayload`. */
    fun setMyNumber(number: String) {
        myNumber = number
    }

    val state: PhoneCallState
        get() {
            if (currentState in TERMINAL_STATES && System.currentTimeMillis() - stateSetAtMillis > TERMINAL_DISPLAY_MILLIS) {
                setLocalState(PhoneCallState.IDLE, "")
            }
            return currentState
        }

    val peerName: String get() = currentPeerName

    /** Called from the phone UI's dialer to place a call to [number]. No-op if already ringing/in a call. */
    fun connect(number: String) {
        if (currentState != PhoneCallState.IDLE) return
        setLocalState(PhoneCallState.CALLING, "")
        PacketDistributor.sendToServer(PhoneCallRequestPayload(number))
    }

    /** Accepts the currently-ringing incoming call. */
    fun accept() {
        if (currentState != PhoneCallState.INCOMING) return
        PacketDistributor.sendToServer(PhoneCallAcceptPayload())
    }

    /** Declines the currently-ringing incoming call. */
    fun decline() {
        if (currentState != PhoneCallState.INCOMING) return
        PacketDistributor.sendToServer(PhoneCallDeclinePayload())
        setLocalState(PhoneCallState.IDLE, "")
    }

    /** Ends the current call, whether it's still ringing outgoing or already connected. */
    fun hangup() {
        if (currentState != PhoneCallState.CALLING && currentState != PhoneCallState.ACTIVE) return
        PacketDistributor.sendToServer(PhoneCallHangupPayload())
        setLocalState(PhoneCallState.IDLE, "")
        PhoneCallReceiver.reset()
    }

    /** Called by `network.PhoneNetworking`'s client-side handler for `PhoneCallStatePayload`. */
    fun handleServerState(state: PhoneCallState, peerName: String) {
        setLocalState(state, peerName)
        if (state != PhoneCallState.ACTIVE) PhoneCallReceiver.reset()
    }

    /** Called on world logout ([io.github.jwyoon1220.dncity.client.ClientModEvents]). */
    fun reset() {
        setLocalState(PhoneCallState.IDLE, "")
        myNumber = ""
        PhoneCallReceiver.reset()
    }

    private fun setLocalState(state: PhoneCallState, peerName: String) {
        currentState = state
        currentPeerName = peerName
        stateSetAtMillis = System.currentTimeMillis()
    }
}
