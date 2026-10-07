// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import net.minecraft.world.phys.Vec3

/** Fuze progress: a delay fuze starts counting at the first armor contact, until then [delayRemainingS] is null. */
data class FuzeState(val armed: Boolean, val delayRemainingS: Double?)

/**
 * The state of one projectile in flight, passed from layer to layer. After a plate the original [ProjectileDefinition] is no
 * longer the truth: a cap is gone, the nose is deformed, a rod is shorter, the speed is lower. Immutable; solvers return
 * a changed copy.
 */
data class ProjectileState(
    val definition: ProjectileDefinition,
    val position: Vec3,
    val velocity: Vec3,
    val massRemainingKg: Double,
    /** Length of the penetrator still intact; null if the definition has no penetrator. */
    val penetratorLengthRemainingM: Double?,
    /** 1 = undamaged, 0 = destroyed. Solvers define what falls below which threshold. */
    val integrity: Double,
    /** 0 = original nose shape, 1 = fully mushroomed or blunted. */
    val deformation: Double,
    val fuze: FuzeState,
) {
    val speedMps: Double get() = velocity.length()

    val kineticEnergyJ: Double get() = 0.5 * massRemainingKg * velocity.lengthSqr()

    fun withVelocity(v: Vec3): ProjectileState = copy(velocity = v)

    fun withSpeed(speedMps: Double): ProjectileState {
        val len = velocity.length()
        return if (len < 1e-9) this else copy(velocity = velocity.scale(speedMps / len))
    }

    companion object {
        fun launch(definition: ProjectileDefinition, position: Vec3, direction: Vec3, speedMps: Double): ProjectileState =
            ProjectileState(
                definition = definition,
                position = position,
                velocity = direction.normalize().scale(speedMps),
                massRemainingKg = definition.massKg,
                penetratorLengthRemainingM = definition.penetrator?.lengthM,
                integrity = 1.0,
                deformation = 0.0,
                fuze = FuzeState(armed = true, delayRemainingS = null),
            )
    }
}
