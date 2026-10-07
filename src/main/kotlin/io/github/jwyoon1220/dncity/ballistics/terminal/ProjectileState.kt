// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

/** Life cycle of a fuze. [INERT] means the projectile has none. */
enum class FuzePhase { INERT, SAFE, ARMED, TRIGGERED, DETONATED, FAILED }

/**
 * Runtime fuze state. Only [FuzeState.advance] style transitions change it, the fixed data is [FuzeSpec]:
 * SAFE -> ARMED (after the arming distance) -> TRIGGERED (armor contact) -> DETONATED (after the delay) or FAILED.
 *
 * @property travelledM flight distance counted towards arming
 * @property delayRemainingS only meaningful in [FuzePhase.TRIGGERED]
 */
data class FuzeState(val phase: FuzePhase, val travelledM: Double, val delayRemainingS: Double) {
    val isFinal: Boolean get() = phase == FuzePhase.DETONATED || phase == FuzePhase.FAILED

    /** Flight of [distanceM]: a SAFE fuze arms once the arming distance is covered. */
    fun flown(distanceM: Double, spec: FuzeSpec?): FuzeState {
        if (phase != FuzePhase.SAFE || spec == null) return this
        val total = travelledM + distanceM
        return if (total >= spec.armingDistanceM) copy(phase = FuzePhase.ARMED, travelledM = total) else copy(travelledM = total)
    }

    /** Armor contact: an ARMED fuze triggers (or fails, [failed]); every other phase ignores the contact. */
    fun contact(spec: FuzeSpec?, failed: Boolean): FuzeState = when {
        phase != FuzePhase.ARMED || spec == null -> this
        failed -> copy(phase = FuzePhase.FAILED)
        spec.delayS <= 0.0 -> copy(phase = FuzePhase.DETONATED, delayRemainingS = 0.0)
        else -> copy(phase = FuzePhase.TRIGGERED, delayRemainingS = spec.delayS)
    }

    /** Time passes inside the target: a TRIGGERED fuze counts down and detonates. */
    fun elapsed(dtS: Double): FuzeState {
        if (phase != FuzePhase.TRIGGERED) return this
        val left = delayRemainingS - dtS
        return if (left <= 0.0) copy(phase = FuzePhase.DETONATED, delayRemainingS = 0.0) else copy(delayRemainingS = left)
    }

    companion object {
        fun initial(payload: PayloadSpec?): FuzeState {
            val fuze = payload?.fuze ?: return FuzeState(FuzePhase.INERT, 0.0, 0.0)
            return FuzeState(if (fuze.armingDistanceM > 0.0) FuzePhase.SAFE else FuzePhase.ARMED, 0.0, 0.0)
        }
    }
}

/**
 * The state of one projectile in flight, handed from layer to layer. After a plate the [ProjectileDefinition] is no
 * longer the whole truth: a cap is gone, the nose is deformed, a rod is shorter, the speed is lower, the rod may be yawed.
 * Immutable; solvers return a changed copy.
 *
 * It refers to its definition by id ([definitionId]), not by object, so it stays valid and saveable across data reloads;
 * whoever needs the fixed data resolves it once (see [ImpactContext]).
 *
 * @property axis **unit** vector along the projectile's body (enforced, never zero). For a stable flight it equals the velocity
 *   direction; after an oblique impact a long penetrator tumbles and the two differ ([yawRad]), which long-rod models need.
 *
 * A projectile at rest (speed 0) is a valid state, it is what lies in the armor after it stopped; it just cannot be the
 * residual of a perforation or ricochet ([PenetrationResult] requires it to be moving).
 */
data class ProjectileState(
    val definitionId: Ident,
    val position: V3,
    val velocity: V3,
    val axis: V3,
    val massRemainingKg: Double,
    /** Length of the penetrator still intact; null if the definition has no penetrator. */
    val penetratorLengthRemainingM: Double?,
    /** 1 = undamaged, 0 = destroyed. Solvers define what falls below which threshold. */
    val integrity: Double,
    /** Normalized: 0 = original nose shape, 1 = fully mushroomed or blunted. Always in 0..1. */
    val deformation: Double,
    val fuze: FuzeState,
) {
    init {
        require(position.isFinite && velocity.isFinite && axis.isFinite) { "projectile vectors must be finite" }
        require(kotlin.math.abs(axis.length() - 1.0) < AXIS_TOL) { "projectile axis must be a unit vector, length ${axis.length()}" }
        require(massRemainingKg >= 0.0) { "mass must not be negative" }
        require(penetratorLengthRemainingM == null || penetratorLengthRemainingM >= 0.0) { "penetrator length must not be negative" }
        require(integrity in 0.0..1.0) { "integrity must be in 0..1" }
        require(deformation in 0.0..1.0) { "deformation must be in 0..1" }
    }

    val speedMps: Double get() = velocity.length()

    val kineticEnergyJ: Double get() = 0.5 * massRemainingKg * velocity.lengthSqr()

    /** Angle between the body axis and the direction of flight; 0 for a projectile flying nose first. */
    val yawRad: Double
        get() {
            val v = velocity.length()
            if (v < 1e-9) return 0.0
            val cos = (velocity.dot(axis) / v).coerceIn(-1.0, 1.0)
            return kotlin.math.acos(cos)
        }

    fun withSpeed(speedMps: Double): ProjectileState {
        val len = velocity.length()
        return if (len < 1e-9) this else copy(velocity = velocity.scale(speedMps / len))
    }

    companion object {
        const val AXIS_TOL = 1e-6

        fun launch(definition: ProjectileDefinition, position: V3, direction: V3, speedMps: Double): ProjectileState {
            val dir = direction.normalize()
            require(dir.lengthSqr() > 0.0) { "launch direction must not be zero" }
            return ProjectileState(
                definitionId = definition.id,
                position = position,
                velocity = dir.scale(speedMps),
                axis = dir,
                massRemainingKg = definition.massKg,
                penetratorLengthRemainingM = definition.penetrator?.lengthM,
                integrity = 1.0,
                deformation = 0.0,
                fuze = FuzeState.initial(definition.payload),
            )
        }
    }
}
