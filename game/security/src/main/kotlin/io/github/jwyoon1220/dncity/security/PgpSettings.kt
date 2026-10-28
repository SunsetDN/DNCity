package io.github.jwyoon1220.dncity.security

import java.io.File

/**
 * Whether the PGP login gate ([PgpAuthServerEvents]) is enforced at all -- defaults to **off**;
 * `/pgp enable` turns it on for a server that actually wants it (e.g. before opening a world up
 * publicly), `/pgp disable` turns it back off without deleting anyone's registered key. Backed by
 * a single marker file rather than an in-memory flag so the setting survives a server restart,
 * same file-per-fact convention as [PgpKeyRegistry] -- presence of the marker means enabled, its
 * absence (the default, e.g. a fresh `config/` dir) means disabled.
 */
object PgpSettings {
    private val enabledMarker: File by lazy { File(PgpPaths.let { it.keysDir.parentFile }, "enabled") }

    /** Whether a connection arriving from loopback (127.0.0.1/::1) is exempted from the gate --
     * defaults to **off**. A same-host reverse proxy (Velocity/BungeeCord) makes every real
     * player's backend connection look like it's from loopback, so this must stay an explicit,
     * informed opt-in (`/pgp trustloopback on`) rather than the default posture, or it would
     * silently exempt every player on that common hosting setup. */
    private val exemptLoopbackMarker: File by lazy { File(PgpPaths.let { it.keysDir.parentFile }, "exempt_loopback") }

    var enabled: Boolean
        get() = enabledMarker.isFile
        set(value) {
            if (value) enabledMarker.createNewFile() else enabledMarker.delete()
        }

    var exemptLoopback: Boolean
        get() = exemptLoopbackMarker.isFile
        set(value) {
            if (value) exemptLoopbackMarker.createNewFile() else exemptLoopbackMarker.delete()
        }
}
