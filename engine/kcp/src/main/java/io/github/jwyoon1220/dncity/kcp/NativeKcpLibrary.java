package io.github.jwyoon1220.dncity.kcp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Loads the {@code dncity_kcp} native library (this module's JNI bridge to {@code DncityKcp.h},
 * built from CMakeLists.txt) from its classpath resource. Mirrors engine/window's
 * {@code NativeWindowLibrary.java} / engine/audio's {@code NativeLibrary.java} exactly: staged
 * under {@code natives/<os>-<arch>/} by this module's build.gradle.kts (which plain
 * {@code System.loadLibrary} can't see, since it only searches {@code java.library.path}), so
 * it's extracted to a temp file and loaded via {@code System.load} with an absolute path instead.
 *
 * <p>Extraction targets the OS temp root directly ({@code File.createTempFile}, no subdirectory)
 * rather than a shared subdirectory -- see engine/fmod's {@code FModLoad} / engine/window's
 * {@code NativeWindowLibrary} for the same lesson (an unsigned freshly-built DLL extracted into a
 * temp-root *subdirectory* got silently blocked by this dev machine's Application Control policy).
 *
 * <p>Windows ({@code dncity_kcp.dll}) and Linux ({@code libdncity_kcp.so}, x86-64 always shipped);
 * {@link #load} throws {@link UnsupportedOperationException} on any other OS.
 */
final class NativeKcpLibrary {

    private static boolean loaded = false;

    private NativeKcpLibrary() {
    }

    static synchronized void load() {
        if (loaded) return;

        String osName = System.getProperty("os.name").toLowerCase();
        String platform;
        String libFileName;
        if (osName.contains("win")) {
            platform = "windows";
            libFileName = "dncity_kcp.dll";
        } else if (osName.contains("linux")) {
            platform = "linux";
            libFileName = "libdncity_kcp.so";
        } else {
            throw new UnsupportedOperationException(
                "engine/kcp is built for Windows and Linux only (os.name=" + osName + ")");
        }

        String rawArch = System.getProperty("os.arch");
        String arch = (rawArch.equals("amd64") || rawArch.equals("x86_64")) ? "x86-64" : rawArch;
        String resourcePath = "/natives/" + platform + "-" + arch + "/" + libFileName;

        try (InputStream resource = NativeKcpLibrary.class.getResourceAsStream(resourcePath)) {
            if (resource == null) {
                throw new IOException(
                    "Native kcp library resource not found on classpath: " + resourcePath
                        + " (engine/kcp was not built for this platform/arch)"
                );
            }
            String suffix = libFileName.substring(libFileName.lastIndexOf('.'));
            File tempFile = File.createTempFile("dncity_kcp", suffix);
            tempFile.deleteOnExit();
            try (OutputStream output = Files.newOutputStream(tempFile.toPath())) {
                resource.transferTo(output);
            }
            System.load(tempFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        loaded = true;
    }
}
