// Win32 JNI glue for io.github.jwyoon1220.dncity.window.NativeWindow. Windows-only (see
// CMakeLists.txt's comment) -- every export below is compiled only under _WIN32 so a non-Windows
// build of this module (if ever attempted) fails loudly at System.load time via
// NativeWindowLibrary's UnsupportedOperationException rather than linking broken stubs.
//
// nCreateChild underlies BOTH public API shapes this module exists for: called alone, its return
// value is handed straight back to callers wanting a raw, completely empty native window handle
// for external rendering. For the AWT/JFrame-hosting path, the caller separately creates a
// uniquely-titled Java Frame, finds its real HWND via nFindWindowByTitle, and reparents it under
// the handle nCreateChild returned via nReparent -- this file never touches AWT/Swing itself.
#ifdef _WIN32
#include <jni.h>
#include <windows.h>
#include <string>

namespace {

constexpr wchar_t kWindowClassName[] = L"DNCityOverlayWindowClass";
bool g_classRegistered = false;

LRESULT CALLBACK OverlayWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    // WM_ERASEBKGND: without handling this, GDI's default background erase for our window class
    // (COLOR_WINDOW below) can cause visible flicker for the raw-handle case, since nothing else
    // paints it. Painting a flat fill here once is enough -- default DefWindowProcW handling of
    // WM_ERASEBKGND already does this via the class background brush, so no override is needed
    // beyond letting DefWindowProcW run; kept as an explicit case for clarity/future tuning.
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

void EnsureWindowClassRegistered() {
    if (g_classRegistered) {
        return;
    }
    WNDCLASSEXW wc = {};
    wc.cbSize = sizeof(WNDCLASSEXW);
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = OverlayWndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursorW(nullptr, reinterpret_cast<LPCWSTR>(IDC_ARROW));
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    wc.lpszClassName = kWindowClassName;
    RegisterClassExW(&wc);
    g_classRegistered = true;
}

std::wstring JStringToWide(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return L"";
    }
    const jchar* chars = env->GetStringChars(value, nullptr);
    jsize len = env->GetStringLength(value);
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), len);
    env->ReleaseStringChars(value, chars);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_jwyoon1220_dncity_window_NativeWindow_nCreateChild(
    JNIEnv* env, jclass, jlong parentHwnd, jint x, jint y, jint width, jint height, jstring title) {
    EnsureWindowClassRegistered();
    std::wstring wTitle = JStringToWide(env, title);
    HWND hwnd = CreateWindowExW(
        0,
        kWindowClassName,
        wTitle.c_str(),
        WS_CHILD | WS_VISIBLE,
        x, y, width, height,
        reinterpret_cast<HWND>(parentHwnd),
        nullptr,
        GetModuleHandleW(nullptr),
        nullptr);
    return reinterpret_cast<jlong>(hwnd);
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_window_NativeWindow_nDestroy(
    JNIEnv*, jclass, jlong hwnd) {
    if (hwnd != 0) {
        DestroyWindow(reinterpret_cast<HWND>(hwnd));
    }
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_window_NativeWindow_nShow(
    JNIEnv*, jclass, jlong hwnd, jboolean show) {
    if (hwnd != 0) {
        ShowWindow(reinterpret_cast<HWND>(hwnd), show ? SW_SHOW : SW_HIDE);
    }
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_window_NativeWindow_nMove(
    JNIEnv*, jclass, jlong hwnd, jint x, jint y, jint width, jint height) {
    if (hwnd != 0) {
        SetWindowPos(reinterpret_cast<HWND>(hwnd), nullptr, x, y, width, height,
                     SWP_NOZORDER | SWP_NOACTIVATE);
    }
}

JNIEXPORT jlong JNICALL Java_io_github_jwyoon1220_dncity_window_NativeWindow_nFindWindowByTitle(
    JNIEnv* env, jclass, jstring title) {
    std::wstring wTitle = JStringToWide(env, title);
    HWND hwnd = FindWindowW(nullptr, wTitle.c_str());
    return reinterpret_cast<jlong>(hwnd);
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_window_NativeWindow_nReparent(
    JNIEnv*, jclass, jlong childHwnd, jlong newParentHwnd) {
    if (childHwnd == 0 || newParentHwnd == 0) {
        return;
    }
    HWND child = reinterpret_cast<HWND>(childHwnd);
    HWND parent = reinterpret_cast<HWND>(newParentHwnd);

    LONG_PTR style = GetWindowLongPtrW(child, GWL_STYLE);
    style &= ~(WS_POPUP | WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU);
    style |= WS_CHILD;
    SetWindowLongPtrW(child, GWL_STYLE, style);

    SetParent(child, parent);

    RECT parentRect;
    GetClientRect(parent, &parentRect);
    SetWindowPos(child, nullptr, 0, 0, parentRect.right - parentRect.left,
                 parentRect.bottom - parentRect.top,
                 SWP_FRAMECHANGED | SWP_NOZORDER | SWP_NOACTIVATE);
}

JNIEXPORT jlong JNICALL Java_io_github_jwyoon1220_dncity_window_NativeWindow_nGetForegroundWindow(
    JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(GetForegroundWindow());
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_window_NativeWindow_nSetForegroundWindow(
    JNIEnv*, jclass, jlong hwnd) {
    if (hwnd != 0) {
        SetForegroundWindow(reinterpret_cast<HWND>(hwnd));
    }
}

// SetWindowRgn takes ownership of the HRGN handle on success -- it must NOT be deleted afterward
// (unlike most GDI object usage). On failure (returns 0) it does NOT take ownership, so the
// region is deleted here in that case to avoid a GDI object leak.
JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_window_NativeWindow_nSetRoundRectRgn(
    JNIEnv*, jclass, jlong hwnd, jint width, jint height, jint cornerRadius) {
    if (hwnd == 0) {
        return;
    }
    HWND window = reinterpret_cast<HWND>(hwnd);
    if (cornerRadius <= 0) {
        SetWindowRgn(window, nullptr, TRUE);
        return;
    }
    HRGN region = CreateRoundRectRgn(0, 0, width + 1, height + 1, cornerRadius, cornerRadius);
    if (region != nullptr && SetWindowRgn(window, region, TRUE) == 0) {
        DeleteObject(region);
    }
}

} // extern "C"

#endif // _WIN32
