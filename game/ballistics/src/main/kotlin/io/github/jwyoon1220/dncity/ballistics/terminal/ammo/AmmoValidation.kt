// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.terminal.ammo

import io.github.jwyoon1220.dncity.ballistics.terminal.BallisticsCatalogView
import io.github.jwyoon1220.dncity.ballistics.terminal.Geometry
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.Stabilization

/** Whether a table may be used as production data, and why not. */
enum class CalibrationStatus { PRODUCTION, UNCALIBRATED, FICTIONAL }

object AmmoValidation {
    /** Minimum length/diameter of an APFSDS rod. Below this it is a bullet, not a long rod. */
    const val MIN_ROD_SLENDERNESS = 5.0

    /**
     * Type-specific field rules, run while parsing (the data classes already enforce positivity). Throws on the first problem:
     * the message names the type and the missing/forbidden field so the author knows what to add.
     */
    fun checkStructure(a: AmmoDefinition) {
        val p = a.projectile
        val pen = p.penetrator
        val pay = p.payload
        fun fail(msg: String): Nothing = throw IllegalArgumentException("${p.type}: $msg")

        if (p.diameterM > a.compat.gunCaliberM * (1.0 + 1e-9)) fail("projectile diameter exceeds the gun caliber")
        if (pen != null && pen.massKg > p.massKg * (1.0 + 1e-9)) fail("penetrator mass exceeds projectile mass")
        if (pen != null && pen.diameterM > p.diameterM * (1.0 + 1e-9)) fail("penetrator diameter exceeds projectile diameter")

        when (p.type) {
            AmmoType.AP, AmmoType.APCBC, AmmoType.APHE -> {
                pen ?: fail("needs a penetrator (material, length, density)")
                if (p.type == AmmoType.APCBC) {
                    if (p.construction != AmmoConstruction.CAPPED_FULL_CALIBER) fail("construction must be CAPPED_FULL_CALIBER")
                    if (!p.armorPiercingCap || !p.ballisticCap) fail("needs armor_piercing_cap and ballistic_cap = true")
                } else if (p.construction != AmmoConstruction.FULL_CALIBER && p.construction != AmmoConstruction.CAPPED_FULL_CALIBER) {
                    fail("construction must be FULL_CALIBER or CAPPED_FULL_CALIBER")
                }
                if (p.type == AmmoType.APHE) {
                    if (pay == null || pay.explosiveKgTnt <= 0.0) fail("needs a payload with explosive > 0")
                    if (pay.fuze == null) fail("needs a payload fuze")
                } else if (pay != null && pay.explosiveKgTnt > 0.0) {
                    fail("is solid shot; use APHE for a filled round")
                }
                if (p.geometry == Geometry.LONG_ROD || p.geometry == Geometry.SHAPED_CHARGE_CONE) fail("geometry ${p.geometry} is not a full-caliber AP shape")
            }
            AmmoType.APCR, AmmoType.APDS -> {
                pen ?: fail("needs a penetrator")
                if (p.construction != AmmoConstruction.SUB_CALIBER_CORE) fail("construction must be SUB_CALIBER_CORE")
                if (pen.diameterM >= p.diameterM) fail("penetrator core must be narrower than the projectile")
                if (pen.massKg >= p.massKg) fail("penetrator core must be lighter than the projectile")
            }
            AmmoType.APFSDS -> {
                pen ?: fail("needs a penetrator (rod)")
                if (p.construction != AmmoConstruction.LONG_ROD_SABOT) fail("construction must be LONG_ROD_SABOT")
                if (p.geometry != Geometry.LONG_ROD) fail("geometry must be LONG_ROD")
                if (p.stabilization != Stabilization.FIN) fail("stabilization must be FIN")
                if (pen.diameterM >= a.compat.gunCaliberM) fail("rod diameter must be smaller than the gun caliber")
                if (pen.massKg >= p.massKg) fail("rod must be lighter than the whole round (sabot)")
                if (pen.slenderness < MIN_ROD_SLENDERNESS) fail("rod length/diameter ${pen.slenderness} is below $MIN_ROD_SLENDERNESS")
                if (pay != null) fail("takes no payload")
            }
            AmmoType.HE -> {
                if (pen != null) fail("has no kinetic penetrator")
                if (p.construction != AmmoConstruction.BLAST_FILLED) fail("construction must be BLAST_FILLED")
                if (pay == null || pay.explosiveKgTnt <= 0.0) fail("needs a payload with explosive > 0")
                if (pay.fuze == null) fail("needs a payload fuze")
                if (a.penetration !is PenetrationSpec.Physics) fail("has no tabulated penetration; use mode PHYSICS")
            }
            AmmoType.HEAT -> {
                if (pen != null) fail("a shaped-charge jet is not a kinetic penetrator block")
                if (p.construction != AmmoConstruction.SHAPED_CHARGE) fail("construction must be SHAPED_CHARGE")
                if (pay == null || pay.explosiveKgTnt <= 0.0) fail("needs a payload with explosive > 0")
                if (pay.coneDiameterM == null || pay.optimalStandoffM == null) fail("needs payload cone_diameter and optimal_standoff")
                if (pay.coneDiameterM > p.diameterM) fail("cone diameter exceeds the projectile diameter")
                if (pay.fuze == null) fail("needs a payload fuze")
            }
        }

        a.table?.let { t ->
            if (t.meta.rangePolicy.mode == RangeMode.EXTRAPOLATE && t.grid.distancesM.size < 2 && t.grid.anglesRad.size < 2) fail("cannot extrapolate a one-point table")
        }
    }

    fun calibrationOf(a: AmmoDefinition): CalibrationStatus {
        val t = a.table ?: return if (a.provenance.status == ProvenanceStatus.FICTIONAL) CalibrationStatus.FICTIONAL else CalibrationStatus.PRODUCTION
        if (a.provenance.status == ProvenanceStatus.FICTIONAL || t.meta.source?.kind == SourceKind.FICTIONAL) return CalibrationStatus.FICTIONAL
        val m = t.meta
        val complete = m.criterion != null && m.referenceMaterial != null && !m.referenceSpec.isNullOrBlank() && m.source != null
        return if (complete) CalibrationStatus.PRODUCTION else CalibrationStatus.UNCALIBRATED
    }

    /**
     * Cross-reference checks once every catalog is loaded.
     *
     * @param registeredSolvers ids in [AmmoSolvers]; an unregistered solver is **not** an error (the ammo loads, every query is
     *   answered UNRESOLVED), it is only reported in the returned warnings when [warnings] is given
     * @param production when true, anything but [CalibrationStatus.PRODUCTION] is a problem
     */
    fun validate(
        ammo: Collection<AmmoDefinition>,
        view: BallisticsCatalogView,
        registeredSolvers: Set<Ident>,
        production: Boolean,
        warnings: MutableList<String>? = null,
    ): List<String> {
        val problems = ArrayList<String>()
        val projectileIds = view.projectiles.mapTo(HashSet()) { it.id }
        for (a in ammo) {
            if (a.id in projectileIds) problems += "ammo ${a.id}: id is also a projectile definition"
            a.table?.meta?.referenceMaterial?.let { if (it !in view.materialIds) problems += "ammo ${a.id}: unknown reference material $it" }
            if (a.terminal.solver !in registeredSolvers) warnings?.add("ammo ${a.id}: solver ${a.terminal.solver} is not implemented; every query will be UNRESOLVED")
            if (a.penetration is PenetrationSpec.Hybrid && a.terminal.solver in registeredSolvers && AmmoSolvers.registry[a.terminal.solver]?.canSolvePhysics == false) {
                problems += "ammo ${a.id}: HYBRID needs a physics solver, ${a.terminal.solver} is table-only"
            }
            val cal = calibrationOf(a)
            if (production && cal != CalibrationStatus.PRODUCTION) problems += "ammo ${a.id}: calibration is $cal, not allowed in production"
        }
        return problems
    }
}
