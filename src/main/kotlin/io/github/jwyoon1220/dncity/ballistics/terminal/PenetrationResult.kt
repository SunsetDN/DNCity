// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

/**
 * What became of the projectile at one layer.
 *
 * - [STOPPED]: did not get through, nothing continues
 * - [RICOCHET]: bounced off, [PenetrationResult.residual] flies on with a new direction
 * - [PARTIAL]: made a crater or bulge, did not get through, the backside may still spall
 * - [PERFORATED]: got through, [PenetrationResult.residual] continues to the next element
 * - [SHATTERED]: the projectile broke up on the layer; fragments, if any, are in [PenetrationResult.spall]
 */
enum class PenetrationOutcome { STOPPED, RICOCHET, PARTIAL, PERFORATED, SHATTERED }

/**
 * A cone of fragments leaving the back of a layer (or a projectile that broke up). The post-penetration solver turns it into
 * rays; this class only says where from, which way, how wide and how much, so the penetration API need not change when
 * spall gets implemented.
 */
data class SpallSource(
    val origin: V3,
    val coneAxis: V3,
    val coneHalfAngleRad: Double,
    val energyJ: Double,
    val fragmentMassKg: Double,
    val materialId: Ident,
) {
    init {
        require(origin.isFinite && coneAxis.isFinite) { "spall vectors must be finite" }
        require(energyJ >= 0.0 && fragmentMassKg >= 0.0 && coneHalfAngleRad >= 0.0) { "spall quantities must not be negative" }
    }
}

/**
 * The result of one layer. [residual] is non-null for [PenetrationOutcome.PERFORATED] and [PenetrationOutcome.RICOCHET],
 * and carries everything the next layer needs (worn penetrator, new velocity, new axis, fuze).
 *
 * @property depositedEnergyJ energy the layer took out of the projectile
 * @property perforationPoint where the projectile left the layer (PERFORATED only)
 * @property exitDirection direction of the residual projectile on leaving, or after a ricochet
 * @property spall fragments this event produced, if any
 */
data class PenetrationResult(
    val outcome: PenetrationOutcome,
    val residual: ProjectileState?,
    val depositedEnergyJ: Double,
    val perforationPoint: V3?,
    val exitDirection: V3?,
    val spall: SpallSource?,
) {
    init {
        require(depositedEnergyJ >= 0.0) { "deposited energy must not be negative" }
        when (outcome) {
            PenetrationOutcome.PERFORATED -> require(residual != null && perforationPoint != null) { "PERFORATED needs a residual and a perforation point" }
            PenetrationOutcome.RICOCHET -> require(residual != null) { "RICOCHET needs a residual" }
            else -> require(residual == null) { "$outcome must not leave a residual projectile" }
        }
    }

    val continues: Boolean get() = residual != null

    companion object {
        fun stopped(deposited: Double) = PenetrationResult(PenetrationOutcome.STOPPED, null, deposited, null, null, null)

        fun partial(deposited: Double, spall: SpallSource? = null) =
            PenetrationResult(PenetrationOutcome.PARTIAL, null, deposited, null, null, spall)

        fun shattered(deposited: Double, spall: SpallSource? = null) =
            PenetrationResult(PenetrationOutcome.SHATTERED, null, deposited, null, null, spall)

        fun ricochet(residual: ProjectileState, deposited: Double) =
            PenetrationResult(PenetrationOutcome.RICOCHET, residual, deposited, null, residual.velocity.normalize(), null)

        fun perforated(residual: ProjectileState, deposited: Double, point: V3, spall: SpallSource? = null) =
            PenetrationResult(PenetrationOutcome.PERFORATED, residual, deposited, point, residual.velocity.normalize(), spall)
    }
}

/** The blast an effect or a detonation produced: where, and how much (TNT equivalent). Turned into pressure/fragments later. */
data class BlastSource(val origin: V3, val explosiveKgTnt: Double) {
    init {
        require(origin.isFinite && explosiveKgTnt >= 0.0) { "invalid blast" }
    }
}

/**
 * Energy bookkeeping. A model may lose energy (heat, deformation, anything not tracked) but must never create it: what
 * leaves a step (residual + deposited + fragments) cannot exceed what entered, apart from float error. The traversal enforces
 * this on every model, tests use [balance] to see how much was lost.
 */
object EnergyAccounting {
    const val REL_TOL = 1e-6
    private const val ABS_TOL = 1e-9

    /** Energy that left the step through the result: residual + deposited + spall fragments. */
    fun accountedJ(result: PenetrationResult): Double =
        (result.residual?.kineticEnergyJ ?: 0.0) + result.depositedEnergyJ + (result.spall?.energyJ ?: 0.0)

    /** Energy not accounted for (>= 0 for a sound model): initial - residual - deposited - fragments. */
    fun balance(initialJ: Double, result: PenetrationResult): Double = initialJ - accountedJ(result)

    fun createsEnergy(initialJ: Double, afterJ: Double): Boolean = afterJ > initialJ * (1.0 + REL_TOL) + ABS_TOL
}
