package io.github.jwyoon1220.dncity.security

import java.io.File

/**
 * Whether the PGP login gate ([PgpAuthServerEvents]) is enforced at all -- `/pgp disable` lets an
 * operator open the server up to unregistered/vanilla-incapable clients temporarily (e.g. while
 * onboarding players who haven't set up a key yet) without deleting anyone's registered key.
 * Backed by a single marker file rather than an in-memory flag so the setting survives a server
 * restart, same file-per-fact convention as [PgpKeyRegistry].
 */
object PgpSettings {
    private val disabledMarker: File by lazy { File(PgpPaths.let { it.keysDir.parentFile }, "disabled") }

    var enabled: Boolean
        get() = !disabledMarker.isFile
        set(value) {
            if (value) disabledMarker.delete() else disabledMarker.createNewFile()
        }
}
