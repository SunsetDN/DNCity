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
 *
 * @property coneAxis unit vector, the middle of the cone
 * @property coneHalfAngleRad 0..pi
 * @property energyJ kinetic energy carried by the fragments together; when it is above zero there must be fragment mass
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
        require(kotlin.math.abs(coneAxis.length() - 1.0) < UNIT_TOL) { "spall cone axis must be a unit vector, length ${coneAxis.length()}" }
        require(coneHalfAngleRad.isFinite() && coneHalfAngleRad in 0.0..Math.PI) { "spall cone half angle must be in 0..pi" }
        require(energyJ.isFinite() && energyJ >= 0.0) { "spall energy must be finite and not negative" }
        require(fragmentMassKg.isFinite() && fragmentMassKg >= 0.0) { "spall mass must be finite and not negative" }
        require(energyJ == 0.0 || fragmentMassKg > 0.0) { "spall with energy needs fragment mass" }
    }

    companion object {
        const val UNIT_TOL = 1e-6
    }
}

/**
 * The result of one layer. Contract with the traversal, which owns the canonical state:
 *
 * - [residual] is non-null for [PenetrationOutcome.PERFORATED] and [PenetrationOutcome.RICOCHET] and must be moving (speed > 0).
 * - For PERFORATED, [perforationPoint] is where the projectile left the layer. The traversal places the residual there
 *   (it overwrites `residual.position`), so a solver does not have to keep two positions in agreement.
 * - The direction after the layer is the direction of `residual.velocity` ([exitDirection] only reads it).
 *
 * @property depositedEnergyJ energy the layer took out of the projectile
 * @property spall fragments this event produced, if any
 */
data class PenetrationResult(
    val outcome: PenetrationOutcome,
    val residual: ProjectileState?,
    val depositedEnergyJ: Double,
    val perforationPoint: V3?,
    val spall: SpallSource?,
) {
    init {
        require(depositedEnergyJ.isFinite() && depositedEnergyJ >= 0.0) { "deposited energy must be finite and not negative" }
        when (outcome) {
            PenetrationOutcome.PERFORATED -> {
                require(residual != null && perforationPoint != null) { "PERFORATED needs a residual and a perforation point" }
                require(perforationPoint.isFinite) { "perforation point must be finite" }
                require(residual.speedMps > 0.0) { "a perforating residual must be moving" }
            }
            PenetrationOutcome.RICOCHET -> {
                require(residual != null) { "RICOCHET needs a residual" }
                require(perforationPoint == null) { "RICOCHET has no perforation point" }
                require(residual.speedMps > 0.0) { "a ricocheting residual must be moving" }
            }
            else -> {
                require(residual == null) { "$outcome must not leave a residual projectile" }
                require(perforationPoint == null) { "$outcome has no perforation point" }
            }
        }
    }

    /** Direction the residual projectile flies in, derived from its velocity so the two cannot disagree. */
    val exitDirection: V3? get() = residual?.velocity?.normalize()

    val continues: Boolean get() = residual != null

    companion object {
        fun stopped(deposited: Double) = PenetrationResult(PenetrationOutcome.STOPPED, null, deposited, null, null)

        fun partial(deposited: Double, spall: SpallSource? = null) =
            PenetrationResult(PenetrationOutcome.PARTIAL, null, deposited, null, spall)

        fun shattered(deposited: Double, spall: SpallSource? = null) =
            PenetrationResult(PenetrationOutcome.SHATTERED, null, deposited, null, spall)

        fun ricochet(residual: ProjectileState, deposited: Double) =
            PenetrationResult(PenetrationOutcome.RICOCHET, residual, deposited, null, null)

        fun perforated(residual: ProjectileState, deposited: Double, point: V3, spall: SpallSource? = null) =
            PenetrationResult(PenetrationOutcome.PERFORATED, residual, deposited, point, spall)
    }
}

/** The blast an effect or a detonation produced: where, and how much (TNT equivalent, a mass, *not* an energy; see [EnergyAccounting.blastJ]). */
data class BlastSource(val origin: V3, val explosiveKgTnt: Double) {
    init {
        require(origin.isFinite) { "blast origin must be finite" }
        require(explosiveKgTnt.isFinite() && explosiveKgTnt >= 0.0) { "blast mass must be finite and not negative" }
    }
}

/**
 * Where the energy of one step went, and where it could have come from. A model may lose energy (heat, deformation, anything
 * not tracked) but must never create it: what leaves (residual + deposited + fragments + blast) cannot exceed what is
 * available (the projectile's own energy + what an active armor stores and releases), apart from float error.
 *
 * Only an active armor has a source of its own, [ArmorEffectSpec.storedEnergyJ], released when the effect uses itself up. It
 * is an *accounting upper bound*: the most a conservation check will ever let that effect add, not an amount that is
 * delivered to the projectile, spall or blast. How much of it actually goes where is the effect model's physics (an explosive
 * model must distribute at most this). Passive armor (NERA, spaced plates, ...) has none, so any energy it "makes" is an error.
 *
 * What a layer absorbed ([PenetrationResult.depositedEnergyJ]) is *not* available to effects: until a layer reports where that
 * energy went (plastic work, heat, fracture, plate motion, spall), nothing may take mechanical energy back out of it.
 */
data class EnergyBudget(
    val projectileInputJ: Double,
    val armorEffectAvailableJ: Double,
    val residualProjectileJ: Double,
    val depositedJ: Double,
    val fragmentJ: Double,
    val blastJ: Double,
) {
    val availableJ: Double get() = projectileInputJ + armorEffectAvailableJ
    val outputJ: Double get() = residualProjectileJ + depositedJ + fragmentJ + blastJ

    /** Energy not accounted for; negative means energy was created. */
    val lostJ: Double get() = availableJ - outputJ

    val isSound: Boolean get() = !EnergyAccounting.createsEnergy(availableJ, outputJ)

    companion object {
        /** A penetrator model at one layer: nothing but the projectile's own energy is available. */
        fun ofLayer(inputJ: Double, result: PenetrationResult) = EnergyBudget(
            inputJ, 0.0, result.residual?.kineticEnergyJ ?: 0.0, result.depositedEnergyJ, result.spall?.energyJ ?: 0.0, 0.0,
        )

        /**
         * An effect model. [inputJ] is the projectile energy the effect may work on: the projectile's energy when it is still
         * flying, 0 when the effect only observes a layer that already ended the projectile. [storedReleasedJ] is the effect's
         * stored energy and counts only when the effect uses itself up in this very interaction.
         */
        fun ofEffect(inputJ: Double, storedReleasedJ: Double, result: EffectInteractionResult) = EnergyBudget(
            inputJ, storedReleasedJ,
            when (val change = result.projectile) {
                is ProjectileChange.Replaced -> change.projectile.kineticEnergyJ
                ProjectileChange.Untouched -> inputJ // carries on unchanged: in and out cancel
                ProjectileChange.Destroyed -> 0.0
            },
            0.0, result.generatedSpall.sumOf { it.energyJ }, EnergyAccounting.blastJ(result.generatedBlast),
        )
    }
}

object EnergyAccounting {
    const val REL_TOL = 1e-6
    private const val ABS_TOL = 1e-9

    /** Energy of one kilogram of TNT, the conventional 4.184 MJ (the definition of the TNT equivalent). */
    const val TNT_J_PER_KG = 4.184e6

    fun blastJ(blast: BlastSource?): Double = (blast?.explosiveKgTnt ?: 0.0) * TNT_J_PER_KG

    /** Energy that left a layer step through the result: residual + deposited + spall fragments. */
    fun accountedJ(result: PenetrationResult): Double = EnergyBudget.ofLayer(0.0, result).outputJ

    /** Energy not accounted for (>= 0 for a sound model): initial - residual - deposited - fragments. */
    fun balance(initialJ: Double, result: PenetrationResult): Double = initialJ - accountedJ(result)

    fun createsEnergy(availableJ: Double, afterJ: Double): Boolean = afterJ > availableJ * (1.0 + REL_TOL) + ABS_TOL
}
