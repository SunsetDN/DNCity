package io.github.jwyoon1220.dncity.window;

/**
 * JNI bridge to the native Win32 child-window functions (engine/window's src/main/cpp/jni_window.cpp).
 * Thin, purely mechanical declarations class -- mirrors engine/audio's {@code NativeAudio}
 * (plain Java, not Kotlin, for the same module-layer/kotlin-stdlib-visibility reason documented
 * there).
 *
 * <p>{@link #nCreateChild} underlies both of this module's use cases: called alone, its return
 * value is a completely empty native child window handle suitable for external native rendering.
 * For hosting an AWT {@code Frame}/{@code JFrame} instead, a caller separately makes the AWT
 * window real (a unique-titled, visible top-level window), locates its HWND via
 * {@link #nFindWindowByTitle}, and reparents it under the handle {@link #nCreateChild} returned
 * via {@link #nReparent} -- this class has no AWT/Swing dependency itself.
 *
 * <p>Windows-only today (see {@link NativeWindowLibrary}); every native method here throws
 * {@code UnsupportedOperationException} on any other platform since the static initializer's
 * {@code NativeWindowLibrary.load()} call fails first.
 */
public final class NativeWindow {

    static {
        NativeWindowLibrary.load();
    }

    private NativeWindow() {
    }

    /** Creates a WS_CHILD window parented directly to {@code parentHwnd}. Returns its HWND. */
    public static native long nCreateChild(long parentHwnd, int x, int y, int width, int height, String title);

    public static native void nDestroy(long hwnd);

    public static native void nShow(long hwnd, boolean show);

    public static native void nMove(long hwnd, int x, int y, int width, int height);

    /** Looks up a top-level window by its exact title. Returns 0 if none is found. */
    public static native long nFindWindowByTitle(String title);

    /**
     * Strips top-level style bits from {@code childHwnd}, reparents it under
     * {@code newParentHwnd} as a WS_CHILD window, and resizes it to fill the new parent's client
     * area.
     */
    public static native void nReparent(long childHwnd, long newParentHwnd);

    /** Returns the HWND of the OS foreground window (used for focus-handoff polling). */
    public static native long nGetForegroundWindow();

    /**
     * Gives OS input focus to {@code hwnd} via {@code SetForegroundWindow} -- used to hand focus
     * back to Minecraft's own window when an overlay hosting another process's window (see
     * {@code BrowserHostProcess}) is hidden or destroyed, since clicking into that window can give
     * it real OS focus that a plain click back on Minecraft's window doesn't reliably reclaim on
     * its own.
     */
    public static native void nSetForegroundWindow(long hwnd);

    /**
     * Clips {@code hwnd} to a rounded-rectangle region ({@code width}x{@code height}, corner
     * radius {@code cornerRadius}) via {@code CreateRoundRectRgn}/{@code SetWindowRgn} -- pixels
     * outside the region are cut away at the OS level, exposing whatever is behind the window.
     * {@code cornerRadius <= 0} restores the window's normal rectangular region.
     */
    public static native void nSetRoundRectRgn(long hwnd, int width, int height, int cornerRadius);
}
