package io.github.jwyoon1220.dncity.client.render.fsr

/**
 * In-memory-only settings for FSR2 temporal upscaling (see AGENTS.md's
 * "Architecture: FSR2 temporal upscaling") -- no [net.neoforged.neoforge.common.ModConfigSpec]/
 * toml, matching [io.github.jwyoon1220.dncity.client.VoiceSettingsScreen]'s documented precedent
 * that this mod has no persisted client-config system at all. Resets to defaults on game restart.
 *
 * Unlike most of this scaffold's new files, this one is fully implemented (not a stub) since it's
 * pure data with no rendering-API dependency.
 */
enum class FsrQuality(val scaleFactor: Float, val label: String) {
    ULTRA_QUALITY(1.3f, "Ultra Quality"),
    QUALITY(1.5f, "Quality"),
    BALANCED(1.7f, "Balanced"),
    PERFORMANCE(2.0f, "Performance"),
    ;

    /** Render resolution = output resolution / [scaleFactor], per axis. */
    fun renderWidth(outputWidth: Int): Int = (outputWidth / scaleFactor).toInt().coerceAtLeast(1)

    fun renderHeight(outputHeight: Int): Int = (outputHeight / scaleFactor).toInt().coerceAtLeast(1)

    fun next(): FsrQuality = entries[(ordinal + 1) % entries.size]
}

object FsrConfig {
    var enabled: Boolean = false
    var quality: FsrQuality = FsrQuality.QUALITY

    /**
     * RCAS sharpening strength, 0f (no sharpening) to 1f (maximum) -- exposed separately from
     * [quality] since AMD's own FSR2 sample lets users tune sharpness independent of the
     * performance/quality tradeoff.
     */
    var sharpness: Float = 0.5f
}
