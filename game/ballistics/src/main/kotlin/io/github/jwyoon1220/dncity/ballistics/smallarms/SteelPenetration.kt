// AGENT-DONE(claude): soft-vehicle-penetration
package io.github.jwyoon1220.dncity.ballistics.smallarms

/**
 * How much steel a small-arms bullet gets through, from the same `E / A` model as blocks: the `metal` category of
 * [BlockMaterialTable] is calibrated on FM 3-06.11's mild-steel door (a 7.62 mm M80 at 25 m gets through 3/8 in, so the resistance
 * is a lower bound and the depths here are, if anything, on the generous side).
 *
 * Used for bullets that have no sourced `armor_penetration` when they hit a *soft* vehicle (sheet metal, see
 * `ballistics/vehicle/VehicleArmor.soft`). The result is mm of **mild** steel and is compared directly with the vehicle's plate value;
 * no mild-steel-to-RHA conversion is applied because no sourced factor was found. It is never used against armor plate.
 */
object SteelPenetration {
    fun mildSteelMm(table: BlockMaterialTable, bullet: BulletSpec, speedMps: Double): Double =
        table.material(BlockCategory.METAL).depthM(bullet.energyDensity(speedMps)) * 1000.0
}
