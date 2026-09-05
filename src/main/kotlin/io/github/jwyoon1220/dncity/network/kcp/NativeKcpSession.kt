package io.github.jwyoon1220.dncity.network.kcp

import io.github.jwyoon1220.dncity.kcp.NativeKcp

/**
 * Drop-in replacement for the previous pure-Kotlin `Kcp` class (see git history), now backed by
 * `engine/kcp`'s native `DncityKcp`/`NativeKcp` JNI bridge -- same wire format, same simplified
 * feature set (see `DncityKcp.h`'s doc comment), so [VoiceKcpServer]/[VoiceKcpClient]'s on-the-wire
 * behavior is unchanged, only where the per-segment ARQ bookkeeping runs (native, off the JVM
 * heap/GC, instead of Kotlin objects).
 *
 * Keeps the same call shape the Kotlin version had -- constructor-supplied [output] callback,
 * [send]/[recv]/[input]/[update] -- so call sites needed no changes beyond the class name. The
 * native side has no way to call back into this [output] lambda directly (see [NativeKcp]'s doc
 * comment), so [update] drains [NativeKcp.pollOutput] in a loop afterward instead -- the only
 * native call that can produce outbound datagrms (see `DncityKcp.h`'s `flush()`, only invoked
 * from `update()`).
 *
 * Every method is synchronized on this instance: [VoiceKcpServer] drives [update]/[input]/[recv]
 * from the UDP channel's Netty event-loop thread while [send] (and, on session teardown, [close])
 * is called from the Minecraft main server thread, and the equivalent split exists client-side --
 * so the underlying native handle (not thread-safe on its own) needs the lock. It also makes
 * [close] safe to race against every other method: once [closed] flips, [send]/[input]/[update]
 * become no-ops and [recv] returns null instead of touching a freed native handle.
 */
class NativeKcpSession(conv: Int, private val output: (ByteArray) -> Unit) {
    private val handle: Long = NativeKcp.create(conv)
    private var closed = false

    @Synchronized
    fun send(data: ByteArray) {
        if (closed || data.isEmpty()) return
        NativeKcp.send(handle, data)
    }

    @Synchronized
    fun recv(): ByteArray? {
        if (closed) return null
        return NativeKcp.recv(handle)
    }

    @Synchronized
    fun input(data: ByteArray) {
        if (closed) return
        NativeKcp.input(handle, data)
    }

    @Synchronized
    fun update(currentMs: Long) {
        if (closed) return
        NativeKcp.update(handle, currentMs)
        while (true) {
            val out = NativeKcp.pollOutput(handle) ?: break
            output(out)
        }
    }

    /** Releases the underlying native handle. Must be called exactly once when this session ends
     * (player disconnect, client teardown) -- see [NativeKcp.destroy]'s own contract. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        NativeKcp.destroy(handle)
    }
}
