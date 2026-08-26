package io.github.jwyoon1220.dncity.audio;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Loads the {@code dncity_audio} native library (miniaudio capture/playback + the codec2 JNI
 * bridge, both built from this module's CMakeLists.txt into one shared library) from its
 * classpath resource. Shared by {@link NativeAudio} and {@link Codec2} since both bind into that
 * same library -- {@link #load} is idempotent so either class's static initializer can call it
 * safely regardless of which runs first.
 *
 * <p>The library is staged under {@code natives/<os>-<arch>/} by this module's
 * build.gradle.kts, which {@code System.loadLibrary} cannot see on its own since it only
 * searches {@code java.library.path} -- so it's extracted to a temp file first and loaded from
 * there via {@code System.load} with an absolute path.
 */
final class NativeLibrary {

    private static boolean loaded = false;

    private NativeLibrary() {
    }

    static synchronized void load() {
        if (loaded) return;

        String osName = System.getProperty("os.name").toLowerCase();
        String platform;
        String libFileName;
        if (osName.contains("win")) {
            platform = "windows";
            libFileName = "dncity_audio.dll";
        } else if (osName.contains("mac")) {
            platform = "macos";
            libFileName = "libdncity_audio.dylib";
        } else {
            platform = "linux";
            libFileName = "libdncity_audio.so";
        }

        String rawArch = System.getProperty("os.arch");
        String arch = (rawArch.equals("amd64") || rawArch.equals("x86_64")) ? "x86-64" : rawArch;
        String resourcePath = "/natives/" + platform + "-" + arch + "/" + libFileName;

        try (InputStream resource = NativeLibrary.class.getResourceAsStream(resourcePath)) {
            if (resource == null) {
                throw new IOException(
                    "Native audio library resource not found on classpath: " + resourcePath
                        + " (engine/audio was not built for this platform/arch)"
                );
            }
            String suffix = libFileName.substring(libFileName.lastIndexOf('.'));
            File tempFile = File.createTempFile("dncity_audio", suffix);
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
