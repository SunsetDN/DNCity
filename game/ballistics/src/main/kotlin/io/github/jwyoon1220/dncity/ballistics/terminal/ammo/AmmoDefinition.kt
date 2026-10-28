// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.terminal.ammo

import io.github.jwyoon1220.dncity.ballistics.terminal.Geometry
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.PayloadSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetratorSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.Stabilization

// The data model of one kind of ammunition, as declared in `data/<ns>/dncity/ammo/(any depth)/name.json`.
// Plain JVM (no Minecraft types), SI units everywhere (JSON units are converted while parsing, see AmmoParser).
//
// An AmmoDefinition is a *catalog* entry: what the round is, what it fits, how its penetration is stated and where the numbers
// came from. The physical, frozen-contract view the traversal reads is ProjectileDefinition, produced by AmmoCompiler.

/** Kind of round. Decides which physical fields are required (see [AmmoValidation]); it is not a solver. */
enum class AmmoType { AP, APCBC, APHE, APCR, APDS, APFSDS, HE, HEAT }

/** How the body is built. Only meaningful together with [AmmoType]; validated, not interpreted. */
enum class AmmoConstruction { FULL_CALIBER, CAPPED_FULL_CALIBER, SUB_CALIBER_CORE, LONG_ROD_SABOT, SHAPED_CHARGE, BLAST_FILLED }

enum class Rarity { COMMON, UNCOMMON, RARE, EPIC }

data class AmmoDisplay(val name: String, val icon: Ident?, val rarity: Rarity)

/** [maxStackSize] is applied as an item data component when a stack is created, not at item registration (see AmmoItem). */
data class AmmoItemSpec(val maxStackSize: Int) {
    init {
        require(maxStackSize in 1..99) { "max_stack_size must be in 1..99" }
    }
}

/**
 * @property gunCaliberM the bore the round is fired from. Distinct from the projectile's own diameter (sub-caliber rounds) and
 *   from the penetrator's diameter (APFSDS rod).
 * @property weaponTags free-form tag ids a weapon module matches against; this system does not interpret them.
 */
data class AmmoCompat(val gunCaliberM: Double, val weaponTags: List<Ident>) {
    init {
        require(gunCaliberM > 0.0 && gunCaliberM.isFinite()) { "gun caliber must be positive" }
    }
}

data class AmmoProjectile(
    val type: AmmoType,
    val construction: AmmoConstruction,
    val geometry: Geometry,
    val stabilization: Stabilization,
    val diameterM: Double,
    val massKg: Double,
    val dragCoefficient: Double,
    val muzzleVelocityMps: Double,
    val penetrator: PenetratorSpec?,
    val payload: PayloadSpec?,
    /** Cap flags are data about the construction (the APCBC example); no solver reads them yet. */
    val armorPiercingCap: Boolean,
    val ballisticCap: Boolean,
) {
    init {
        require(diameterM > 0.0 && massKg > 0.0 && diameterM.isFinite() && massKg.isFinite()) { "diameter and mass must be positive" }
        require(dragCoefficient > 0.0 && dragCoefficient.isFinite()) { "drag coefficient must be positive" }
        require(muzzleVelocityMps > 0.0 && muzzleVelocityMps.isFinite()) { "muzzle velocity must be positive" }
    }
}

/** Test standard a penetration number refers to. Not interchangeable: values with different criteria are different quantities. */
enum class PenetrationCriterion { V50, PROTECTION_LIMIT, MINIMUM_PERFORATION, COMPLETE_PENETRATION, OTHER }

enum class Interpolation { LINEAR, LOG_LINEAR }

/** What happens outside the tabulated range. Anything but [UNRESOLVED] is an explicit choice of the author. */
enum class RangeMode { UNRESOLVED, CLAMP, EXTRAPOLATE }

/**
 * @property distanceMarginM / [angleMarginRad] how far beyond the table [RangeMode.EXTRAPOLATE] may go; required for it, the
 *   rule is "no extrapolation without a stated limit".
 */
data class RangePolicy(val mode: RangeMode, val distanceMarginM: Double, val angleMarginRad: Double) {
    init {
        require(distanceMarginM >= 0.0 && angleMarginRad >= 0.0) { "extrapolation margins must not be negative" }
    }

    companion object {
        val UNRESOLVED = RangePolicy(RangeMode.UNRESOLVED, 0.0, 0.0)
    }
}

data class DataSource(val kind: SourceKind, val citation: String)

enum class SourceKind { MEASURED, ESTIMATED, FICTIONAL }

/**
 * @property thicknessRelative relative uncertainty of the tabulated thickness (0.05 = 5 %), null if the author did not state one
 */
data class Uncertainty(val thicknessRelative: Double?, val notes: String?) {
    init {
        require(thicknessRelative == null || (thicknessRelative in 0.0..1.0)) { "relative uncertainty must be in 0..1" }
    }
}

/**
 * Metadata that makes a table a physical statement. All of criterion, reference material and source may be absent in the file
 * (the table then loads as UNCALIBRATED and is refused in production, see [AmmoValidation]); what is present is never guessed.
 *
 * Angles are always from the armor normal, 0 = perpendicular; the parser rejects any other convention.
 */
data class TableMeta(
    val criterion: PenetrationCriterion?,
    val referenceMaterial: Ident?,
    val referenceSpec: String?,
    val source: DataSource?,
    val uncertainty: Uncertainty?,
    val interpolation: Interpolation,
    val rangePolicy: RangePolicy,
)

/**
 * Penetration limit thickness (m of [TableMeta.referenceMaterial], normal plate thickness) by flight distance and impact angle.
 * A limit is *not* damage; see [AmmoImpactResolver]. Rectangular grid: every row has the same angles.
 */
class PenetrationTable(
    val distancesM: DoubleArray,
    val anglesRad: DoubleArray,
    /** `limitsM[distanceIndex][angleIndex]` */
    val limitsM: Array<DoubleArray>,
) {
    init {
        require(distancesM.isNotEmpty() && anglesRad.isNotEmpty()) { "penetration table must not be empty" }
        require(limitsM.size == distancesM.size && limitsM.all { it.size == anglesRad.size }) { "penetration table must be rectangular" }
        require(distancesM.all { it.isFinite() && it >= 0.0 } && strictlyIncreasing(distancesM)) { "table distances must be >= 0 and strictly increasing" }
        require(anglesRad.all { it.isFinite() && it >= 0.0 && it < Math.PI / 2 } && strictlyIncreasing(anglesRad)) { "table angles must be in [0, 90) degrees and strictly increasing" }
        require(limitsM.all { row -> row.all { it.isFinite() && it > 0.0 } }) { "table limits must be positive and finite" }
    }

    companion object {
        private fun strictlyIncreasing(a: DoubleArray) = (1 until a.size).all { a[it] > a[it - 1] }
    }
}

sealed interface PenetrationSpec {
    /** The shared meta + grid of the modes that carry a table. */
    data class Table(val meta: TableMeta, val grid: PenetrationTable) : PenetrationSpec

    /** Physics only: the solver named in [TerminalSpec.solver] computes everything from the projectile's constants. */
    data object Physics : PenetrationSpec

    /** Table is authoritative for the perforation verdict, physics cross-checks it and every disagreement is reported. */
    data class Hybrid(val table: Table, val toleranceRelative: Double, val onConflict: ConflictPolicy) : PenetrationSpec {
        init {
            require(toleranceRelative in 0.0..1.0) { "hybrid tolerance must be in 0..1" }
        }
    }
}

enum class ConflictPolicy { USE_TABLE, FAIL }

data class TerminalSpec(
    val solver: Ident,
    val ricochetProfile: Ident?,
    val shatterProfile: Ident?,
    val spallProfile: Ident?,
)

enum class ProvenanceStatus { MEASURED, ESTIMATED, FICTIONAL }

data class Provenance(val status: ProvenanceStatus, val sources: List<DataSource>, val notes: String?)

data class AmmoDefinition(
    val id: Ident,
    val display: AmmoDisplay,
    val item: AmmoItemSpec,
    val compat: AmmoCompat,
    val projectile: AmmoProjectile,
    val penetration: PenetrationSpec,
    val terminal: TerminalSpec,
    val provenance: Provenance,
) {
    /** The table of a TABLE or HYBRID ammo, null for PHYSICS. */
    val table: PenetrationSpec.Table?
        get() = when (val p = penetration) {
            is PenetrationSpec.Table -> p
            is PenetrationSpec.Hybrid -> p.table
            PenetrationSpec.Physics -> null
        }

    companion object {
        fun fromJson(id: Ident, json: com.google.gson.JsonObject): AmmoDefinition = AmmoParser.parse(id, json)
    }
}
