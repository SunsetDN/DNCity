package io.github.jwyoon1220.dncity.window;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Loads the {@code dncity_window} native library (Win32 child-window/AWT-reparenting JNI bridge,
 * built from this module's CMakeLists.txt) from its classpath resource. Mirrors engine/audio's
 * {@code NativeLibrary.java} exactly: staged under {@code natives/<os>-<arch>/} by this module's
 * build.gradle.kts (which plain {@code System.loadLibrary} can't see, since it only searches
 * {@code java.library.path}), so it's extracted to a temp file and loaded via {@code System.load}
 * with an absolute path instead.
 *
 * <p>Extraction targets the OS temp root directly ({@code File.createTempFile}, no subdirectory)
 * rather than a shared subdirectory -- on this project's dev machine, extracting an unsigned,
 * freshly-built DLL into a *subdirectory* of the temp root got silently blocked by Application
 * Control policy (AppLocker/WDAC), while a temp-root-level file did not (same lesson already
 * encoded in engine/fmod's {@code FModLoad}, which extracts its own JNI glue DLL the same way for
 * the same reason).
 *
 * <p>Windows-only today: {@link #load} throws {@link UnsupportedOperationException} on any other
 * platform, since this module's native code (see jni_window.cpp) is compiled only under
 * {@code _WIN32} and no {@code natives/<os>-<arch>/} resource exists for other platforms.
 */
final class NativeWindowLibrary {

    private static boolean loaded = false;

    private NativeWindowLibrary() {
    }

    static synchronized void load() {
        if (loaded) return;

        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("win")) {
            throw new UnsupportedOperationException(
                "engine/window is Windows-only today (os.name=" + osName + ") -- see AGENTS.md's "
                    + "\"Architecture: window overlay\" section.");
        }
        String platform = "windows";
        String libFileName = "dncity_window.dll";

        String rawArch = System.getProperty("os.arch");
        String arch = (rawArch.equals("amd64") || rawArch.equals("x86_64")) ? "x86-64" : rawArch;
        String resourcePath = "/natives/" + platform + "-" + arch + "/" + libFileName;

        try (InputStream resource = NativeWindowLibrary.class.getResourceAsStream(resourcePath)) {
            if (resource == null) {
                throw new IOException(
                    "Native window library resource not found on classpath: " + resourcePath
                        + " (engine/window was not built for this platform/arch)"
                );
            }
            String suffix = libFileName.substring(libFileName.lastIndexOf('.'));
            File tempFile = File.createTempFile("dncity_window", suffix);
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
