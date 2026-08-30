package io.github.jwyoon1220.dncity.phone

/**
 * Call-state machine shared by the server ([PhoneCallSession]) and the client
 * ([io.github.jwyoon1220.dncity.client.phone.PhoneCallManager]) -- sent over the wire as this
 * enum's ordinal (see [io.github.jwyoon1220.dncity.network.PhoneCallStatePayload]), so append new
 * entries rather than reordering existing ones.
 */
enum class PhoneCallState {
    /** No call in progress. */
    IDLE,
    /** This player placed a call that's still ringing for the callee. */
    CALLING,
    /** Someone is calling this player. */
    INCOMING,
    /** Both participants are connected -- audio is being relayed. */
    ACTIVE,
    /** The call ended (the peer hung up, or this player's own hangup/the peer's disconnect was processed). */
    ENDED,
    /** The callee declined this player's outgoing call. */
    DECLINED,
    /** The callee is already ringing/in another call. */
    BUSY,
    /** No player is registered under the dialed number ([PhoneDirectory]). */
    NO_SUCH_NUMBER,
    /** The dialed number is registered, but that player isn't currently online. */
    UNREACHABLE,
}
