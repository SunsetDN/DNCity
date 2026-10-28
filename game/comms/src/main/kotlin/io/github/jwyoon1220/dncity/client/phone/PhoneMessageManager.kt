package io.github.jwyoon1220.dncity.client.phone

/**
 * Phone-messaging backend hook, called from [PhoneFragment]'s messages page. Same status as
 * [PhoneCallManager] -- deliberately unimplemented until there's an actual message transport to
 * wire it to.
 */
object PhoneMessageManager {
    /** Called when the phone UI's messages app sends [text] to [to]. */
    fun sendMessage(to: String, text: String) {
        // TODO: wire up an actual message transport.
    }
}
