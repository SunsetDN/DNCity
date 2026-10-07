// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

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
    val origin: Vec3,
    val coneAxis: Vec3,
    val coneHalfAngleRad: Double,
    val energyJ: Double,
    val fragmentMassKg: Double,
    val materialId: ResourceLocation,
)

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
    val perforationPoint: Vec3?,
    val exitDirection: Vec3?,
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

        fun perforated(residual: ProjectileState, deposited: Double, point: Vec3, spall: SpallSource? = null) =
            PenetrationResult(PenetrationOutcome.PERFORATED, residual, deposited, point, residual.velocity.normalize(), spall)
    }
}
