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
 * <p>Windows-only today: {@link #load} throws {@link UnsupportedOperationException} on any other
 * platform, since this module's native code is compiled only for Windows and no
 * {@code natives/<os>-<arch>/} resource exists for other platforms yet -- see build.gradle.kts's
 * comment for why extending this is low-effort whenever it's actually needed (the algorithm
 * itself has no OS-specific calls).
 */
final class NativeKcpLibrary {

    private static boolean loaded = false;

    private NativeKcpLibrary() {
    }

    static synchronized void load() {
        if (loaded) return;

        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("win")) {
            throw new UnsupportedOperationException(
                "engine/kcp is Windows-only today (os.name=" + osName + ")");
        }
        String platform = "windows";
        String libFileName = "dncity_kcp.dll";

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
