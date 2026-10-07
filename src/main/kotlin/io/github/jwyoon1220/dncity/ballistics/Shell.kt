// AGENT-DONE(claude): shell-ballistics
package io.github.jwyoon1220.dncity.ballistics

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One kind of ammunition, any ammunition: a rifle round (TACZ), a tank shell, a rocket or a mortar bomb (SuperbWarfare).
 * The list of shells is data (`data/<namespace>/dncity/shells/*.json`, see [ShellRegistry]); there is no enum of ammo types.
 *
 * A shell is found from the projectile that caused a hit: [entity] is the projectile's entity type, [key] (optional) is
 * what a registered key resolver says about that projectile (TACZ: the ammo id) and [nbt] (optional) must be contained in
 * the projectile's saved data (SuperbWarfare cannon shells: `{"Type":"AP"}`). The most specific match wins.
 *
 * @property ammo the name of the ammunition type (display/documentation only, e.g. "5.56x45mm NATO", "120mm AP")
 * @property caliberMm size of the projectile, the width of the wound follows from it
 * @property penetration 0..1, the share of the damage that ignores armor
 * @property damageMultiplier power: scales the damage the other mod deals
 * @property damageIsLimbHp true if the other mod's damage value already means limb hit points instead of vanilla health
 */
data class Shell(
    val id: ResourceLocation,
    val ammo: String,
    val entity: ResourceLocation,
    val key: String?,
    val nbt: CompoundTag?,
    val caliberMm: Float,
    val penetration: Float,
    val damageMultiplier: Float,
    val damageIsLimbHp: Boolean,
    val bleedChanceBonus: Float,
    val heavyBleedHitFraction: Float?,
    val fractureChanceBonus: Float,
    val overkillFactor: Float?,
    val explosive: Boolean,
) {
    /** How much wider than a reference rifle wound this shell's wound is (square root of the caliber ratio). */
    val woundFactor: Float
        get() = if (caliberMm <= 0f) 1f else sqrt(caliberMm / REFERENCE_CALIBER_MM)

    /** Bleed chance added on top of the configured one: +4% per doubling of the caliber over a rifle round. */
    val effectiveBleedChanceBonus: Float
        get() {
            val doublings = ln(maxOf(caliberMm, 0.01f) / REFERENCE_CALIBER_MM) / ln(2f)
            return bleedChanceBonus + (0.04f * doublings).coerceIn(0f, 0.6f)
        }

    /** A wider wound turns into a heavy bleed at a smaller share of the limb lost. */
    fun effectiveHeavyBleedHitFraction(configured: Float): Float =
        heavyBleedHitFraction ?: (configured / maxOf(woundFactor, 0.1f))

    /** A wider wound passes more of the excess of an emptied limb on: a tank shell does not stop at the arm. */
    fun effectiveOverkillFactor(configured: Float): Float =
        overkillFactor ?: (configured * woundFactor).coerceIn(0f, 1f)

    companion object {
        /** The caliber First Aid's default wound behaviour is balanced for (a 7.62 mm rifle round). */
        const val REFERENCE_CALIBER_MM = 7.62f
    }
}
