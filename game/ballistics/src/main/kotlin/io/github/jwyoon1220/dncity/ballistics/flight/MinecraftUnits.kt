// AGENT-DONE(claude): external-ballistics-si
package io.github.jwyoon1220.dncity.ballistics.flight

/**
 * The one place that says how Minecraft units map to SI for ballistics: 1 block = 1 m, 1 tick = 0.05 s (so 1 block/tick =
 * 20 m/s). Game code (TACZ bullets keep velocity in blocks/tick) converts through here and never scales by a literal 20.
 */
object MinecraftUnits {
    const val BLOCK_M = 1.0
    const val TICK_S = 0.05
    const val TICKS_PER_SECOND = 20.0

    fun blocksPerTickToMps(v: Double): Double = v * BLOCK_M / TICK_S
    fun mpsToBlocksPerTick(v: Double): Double = v * TICK_S / BLOCK_M

    /** Altitude of a world Y above sea level; the overworld sea level is Y 63, but a pack may choose another. */
    fun altitudeM(worldY: Double, seaLevelY: Double = 63.0): Double = (worldY - seaLevelY) * BLOCK_M
}
