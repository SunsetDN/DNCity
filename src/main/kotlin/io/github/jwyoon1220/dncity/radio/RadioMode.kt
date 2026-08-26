package io.github.jwyoon1220.dncity.radio

/**
 * Voice mode used on a radio slot. Each mode defines the audio bandpass window and how much
 * static is mixed in relative to signal strength.
 */
enum class RadioMode(
    val lowCutoffHz: Double,
    val highCutoffHz: Double,
    val baseNoiseLevel: Double,
) {
    AM(lowCutoffHz = 300.0, highCutoffHz = 3000.0, baseNoiseLevel = 0.12),
    USB(lowCutoffHz = 300.0, highCutoffHz = 2700.0, baseNoiseLevel = 0.06),
    FM(lowCutoffHz = 50.0, highCutoffHz = 15000.0, baseNoiseLevel = 0.03),
}
