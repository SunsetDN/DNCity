package io.github.jwyoon1220.dncity.kcp;

/**
 * JNI bridge to {@code DncityKcp.h} (a direct C++ translation of this mod's own simplified KCP
 * ARQ transport -- see that header's doc comment for the algorithm itself: per-segment selective
 * ACK, adaptive RTO, fast retransmit, sliding window; deliberately no congestion-control window
 * and no window-probe handshake, matching this mod's private/LAN-scale voice/LOD traffic). Wire
 * format is unchanged from the previous pure-Kotlin implementation, so this is a drop-in backend
 * swap for {@code network.kcp.NativeKcpSession} -- see that class for the Kotlin-facing API
 * (constructor-supplied output callback, output draining), which this class deliberately does
 * *not* provide directly: JNI has no clean way to call back into arbitrary Kotlin lambdas from a
 * C++ callback invoked mid-flush, so the native side just queues completed outbound datagrams
 * instead, and {@link #pollOutput} drains that queue -- call it in a loop until it returns
 * {@code null} after every {@link #update} call (the only call that can produce output; see
 * {@code DncityKcp.h}'s {@code flush()}).
 *
 * <p>Handle-based, not object-oriented: {@link #create} allocates one native {@code DncityKcp}
 * and returns it as an opaque {@code long}; every handle obtained from {@link #create} must be
 * passed to exactly one {@link #destroy} call once its session ends, or the native allocation
 * leaks. A handle is <b>not</b> thread-safe -- state is mutated in place by every call, so
 * concurrent use of the same handle from multiple threads corrupts it; one handle per session,
 * used from one thread (matches how {@code Codec2} handles are used elsewhere in this mod).
 */
public final class NativeKcp {

    static {
        NativeKcpLibrary.load();
    }

    private NativeKcp() {
    }

    /** Allocates a new KCP session state for the given 32-bit conv id. */
    public static native long create(int conv);

    /** Releases a handle obtained from {@link #create}. Safe to call at most once per handle. */
    public static native void destroy(long handle);

    /** Queues {@code data} for delivery -- fragmented internally if needed, actually sent later
     * from {@link #update}. */
    public static native void send(long handle, byte[] data);

    /** Returns the next fully-reassembled received message, or {@code null} if none is ready. */
    public static native byte[] recv(long handle);

    /** Feeds one raw incoming UDP datagram into the protocol state machine. */
    public static native void input(long handle, byte[] data);

    /** Drives retransmission/flush timing -- call at a fixed short interval with the caller's own
     * monotonic millisecond clock. May produce outbound datagrams; drain with {@link #pollOutput}
     * afterward. */
    public static native void update(long handle, long currentMs);

    /** Pops the next pending outbound datagram queued by {@link #update}, or {@code null} if none
     * is queued. Call repeatedly until it returns {@code null}. */
    public static native byte[] pollOutput(long handle);
}
