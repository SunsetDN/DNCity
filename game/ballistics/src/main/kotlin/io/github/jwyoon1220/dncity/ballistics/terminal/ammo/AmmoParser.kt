// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.terminal.ammo

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.ballistics.terminal.FuzeSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.Geometry
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.PayloadSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetratorSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.SiJson
import io.github.jwyoon1220.dncity.ballistics.terminal.Stabilization
import java.util.Locale

/**
 * JSON -> [AmmoDefinition]. Quantities use the same `<name>_m|_mm`, `<name>_kg|_g`, `<name>_kg_m3|_g_cm3` convention as the rest of
 * terminal ballistics ([SiJson]); everything leaves this class in SI. Unknown keys are an error: a typo such as `penetraton`
 * silently dropping a whole block is worse than a failed reload.
 *
 * Type-specific rules (which fields a HEAT round needs, that an APFSDS rod is narrower than the gun) live in [AmmoValidation],
 * which runs as the last step of [parse].
 */
internal object AmmoParser {
    const val SCHEMA_VERSION = 1
    const val ANGLE_CONVENTION = "FROM_ARMOR_NORMAL"

    private val TOP = setOf("schema_version", "display", "item", "compatibility", "projectile", "penetration", "terminal", "provenance")

    fun parse(id: Ident, json: JsonObject): AmmoDefinition {
        noUnknown(json, TOP, "ammo")
        val version = json.get("schema_version")?.asInt ?: throw IllegalArgumentException("missing schema_version")
        require(version == SCHEMA_VERSION) { "unsupported schema_version $version (this build reads $SCHEMA_VERSION)" }

        val projectile = parseProjectile(obj(json, "projectile"))
        val ammo = AmmoDefinition(
            id = id,
            display = parseDisplay(id, json.getAsJsonObject("display")),
            item = AmmoItemSpec(json.getAsJsonObject("item")?.also { noUnknown(it, setOf("max_stack_size"), "item") }?.get("max_stack_size")?.asInt ?: 64),
            compat = parseCompat(obj(json, "compatibility")),
            projectile = projectile,
            penetration = parsePenetration(obj(json, "penetration")),
            terminal = parseTerminal(obj(json, "terminal")),
            provenance = parseProvenance(json.getAsJsonObject("provenance")),
        )
        AmmoValidation.checkStructure(ammo)
        return ammo
    }

    private fun parseDisplay(id: Ident, j: JsonObject?): AmmoDisplay {
        j?.let { noUnknown(it, setOf("name", "icon", "rarity"), "display") }
        return AmmoDisplay(
            name = j?.get("name")?.asString ?: id.path,
            icon = j?.get("icon")?.asString?.let(Ident::parse),
            rarity = if (j?.has("rarity") == true) SiJson.enumOf<Rarity>(j, "rarity") else Rarity.COMMON,
        )
    }

    private fun parseCompat(j: JsonObject): AmmoCompat {
        noUnknown(j, setOf("gun_caliber_m", "gun_caliber_mm", "weapon_tags"), "compatibility")
        return AmmoCompat(
            gunCaliberM = SiJson.requireLength(j, "gun_caliber"),
            weaponTags = j.getAsJsonArray("weapon_tags")?.map { Ident.parse(it.asString) } ?: emptyList(),
        )
    }

    private fun parseProjectile(j: JsonObject): AmmoProjectile {
        noUnknown(
            j,
            setOf(
                "type", "construction", "geometry", "stabilization", "diameter_m", "diameter_mm", "mass_kg", "mass_g",
                "drag_coefficient", "muzzle_velocity_mps", "penetrator", "payload", "armor_piercing_cap", "ballistic_cap",
            ),
            "projectile",
        )
        val diameter = SiJson.requireLength(j, "diameter")
        val mass = SiJson.requireMass(j, "mass")
        val penetrator = j.getAsJsonObject("penetrator")?.let { p ->
            noUnknown(
                p,
                setOf(
                    "material", "diameter_m", "diameter_mm", "length_m", "length_mm", "mass_kg", "mass_g",
                    "density_kg_m3", "density_g_cm3", "hardness_bhn",
                ),
                "penetrator",
            )
            PenetratorSpec(
                material = Ident.parse(p.get("material")?.asString ?: throw IllegalArgumentException("penetrator needs a material")),
                diameterM = SiJson.length(p, "diameter") ?: diameter,
                lengthM = SiJson.requireLength(p, "length"),
                massKg = SiJson.mass(p, "mass") ?: mass,
                densityKgM3 = SiJson.density(p) ?: throw IllegalArgumentException("penetrator needs density_kg_m3 or density_g_cm3"),
                hardnessBhn = p.get("hardness_bhn")?.asDouble,
            )
        }
        val payload = j.getAsJsonObject("payload")?.let { p ->
            noUnknown(p, setOf("explosive_kg", "explosive_g", "cone_diameter_m", "cone_diameter_mm", "optimal_standoff_m", "optimal_standoff_mm", "fuze"), "payload")
            PayloadSpec(
                explosiveKgTnt = SiJson.mass(p, "explosive") ?: 0.0,
                coneDiameterM = SiJson.length(p, "cone_diameter"),
                optimalStandoffM = SiJson.length(p, "optimal_standoff"),
                fuze = p.getAsJsonObject("fuze")?.let { f ->
                    noUnknown(f, setOf("arming_distance_m", "arming_distance_mm", "delay_s", "min_trigger_thickness_m", "min_trigger_thickness_mm", "failure_chance"), "fuze")
                    FuzeSpec(
                        armingDistanceM = SiJson.length(f, "arming_distance") ?: 0.0,
                        delayS = f.get("delay_s")?.asDouble ?: 0.0,
                        minTriggerThicknessM = SiJson.length(f, "min_trigger_thickness"),
                        failureChance = f.get("failure_chance")?.asDouble ?: 0.0,
                    )
                },
            )
        }
        return AmmoProjectile(
            type = SiJson.enumOf<AmmoType>(j, "type"),
            construction = SiJson.enumOf<AmmoConstruction>(j, "construction"),
            geometry = SiJson.enumOf<Geometry>(j, "geometry"),
            stabilization = if (j.has("stabilization")) SiJson.enumOf<Stabilization>(j, "stabilization") else Stabilization.NONE,
            diameterM = diameter,
            massKg = mass,
            dragCoefficient = j.get("drag_coefficient")?.asDouble ?: throw IllegalArgumentException("missing drag_coefficient"),
            muzzleVelocityMps = j.get("muzzle_velocity_mps")?.asDouble ?: throw IllegalArgumentException("missing muzzle_velocity_mps"),
            penetrator = penetrator,
            payload = payload,
            armorPiercingCap = j.get("armor_piercing_cap")?.asBoolean ?: false,
            ballisticCap = j.get("ballistic_cap")?.asBoolean ?: false,
        )
    }

    private fun parsePenetration(j: JsonObject): PenetrationSpec {
        noUnknown(
            j,
            setOf(
                "mode", "criterion", "reference_material", "reference_spec", "angle_convention", "thickness_unit", "source",
                "uncertainty", "interpolation", "out_of_range", "extrapolation", "table", "tolerance_relative", "on_conflict",
            ),
            "penetration",
        )
        return when (val mode = j.get("mode")?.asString?.uppercase(Locale.ROOT) ?: throw IllegalArgumentException("missing penetration mode")) {
            "PHYSICS" -> {
                val tableKeys = setOf("table", "criterion", "reference_material", "interpolation", "out_of_range", "tolerance_relative", "on_conflict")
                require(j.keySet().none { it in tableKeys }) { "PHYSICS penetration takes no table or table metadata; use HYBRID to cross-check against one" }
                PenetrationSpec.Physics
            }
            "TABLE" -> parseTable(j)
            "HYBRID" -> PenetrationSpec.Hybrid(
                table = parseTable(j),
                toleranceRelative = j.get("tolerance_relative")?.asDouble ?: throw IllegalArgumentException("HYBRID needs tolerance_relative"),
                onConflict = SiJson.enumOf<ConflictPolicy>(j, "on_conflict"),
            )
            else -> throw IllegalArgumentException("unknown penetration mode '$mode' (TABLE, PHYSICS, HYBRID)")
        }
    }

    private fun parseTable(j: JsonObject): PenetrationSpec.Table {
        val convention = j.get("angle_convention")?.asString ?: throw IllegalArgumentException("missing angle_convention (must be $ANGLE_CONVENTION)")
        require(convention == ANGLE_CONVENTION) { "angle_convention must be $ANGLE_CONVENTION, got '$convention'" }
        val unit = j.get("thickness_unit")?.asString ?: "mm"
        val scale = when (unit) {
            "mm" -> 0.001
            "m" -> 1.0
            else -> throw IllegalArgumentException("thickness_unit must be 'mm' or 'm', got '$unit'")
        }
        val rows = (j.get("table") as? JsonArray ?: throw IllegalArgumentException("missing table array")).map { it.asJsonObject }
        require(rows.isNotEmpty()) { "table has no rows" }
        val distances = rows.map { r -> SiJson.requireLength(r, "distance") }
        val angleKeys = rows.map { r ->
            val a = r.getAsJsonObject("angles") ?: throw IllegalArgumentException("table row without angles")
            a.entrySet().associate { (k, v) -> k.toDouble() to v.asDouble * scale }
        }
        val firstAngles = angleKeys.first().keys.sorted()
        require(angleKeys.all { it.keys.sorted() == firstAngles }) { "every table row must list the same angles (rectangular table)" }
        val grid = PenetrationTable(
            distancesM = distances.toDoubleArray(),
            anglesRad = firstAngles.map { Math.toRadians(it) }.toDoubleArray(),
            limitsM = Array(rows.size) { i -> DoubleArray(firstAngles.size) { k -> angleKeys[i].getValue(firstAngles[k]) } },
        )

        val unc = j.getAsJsonObject("uncertainty")?.also { noUnknown(it, setOf("thickness_relative", "notes"), "uncertainty") }
        val src = j.getAsJsonObject("source")?.also { noUnknown(it, setOf("kind", "citation"), "source") }
        val range = parseRange(j)
        return PenetrationSpec.Table(
            TableMeta(
                criterion = if (j.has("criterion")) SiJson.enumOf<PenetrationCriterion>(j, "criterion") else null,
                referenceMaterial = j.get("reference_material")?.asString?.let(Ident::parse),
                referenceSpec = j.get("reference_spec")?.asString,
                source = src?.let { DataSource(SiJson.enumOf<SourceKind>(it, "kind"), it.get("citation")?.asString?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("source needs a citation")) },
                uncertainty = unc?.let { Uncertainty(it.get("thickness_relative")?.asDouble, it.get("notes")?.asString) },
                interpolation = if (j.has("interpolation")) SiJson.enumOf<Interpolation>(j, "interpolation") else Interpolation.LINEAR,
                rangePolicy = range,
            ),
            grid,
        )
    }

    private fun parseRange(j: JsonObject): RangePolicy {
        val mode = if (j.has("out_of_range")) SiJson.enumOf<RangeMode>(j, "out_of_range") else RangeMode.UNRESOLVED
        val ex = j.getAsJsonObject("extrapolation")
        if (mode != RangeMode.EXTRAPOLATE) {
            require(ex == null) { "extrapolation block only makes sense with out_of_range = EXTRAPOLATE" }
            return RangePolicy(mode, 0.0, 0.0)
        }
        require(ex != null) { "out_of_range = EXTRAPOLATE needs an extrapolation block with explicit margins" }
        noUnknown(ex, setOf("max_distance_beyond_m", "max_angle_beyond_deg"), "extrapolation")
        return RangePolicy(
            mode,
            ex.get("max_distance_beyond_m")?.asDouble ?: throw IllegalArgumentException("extrapolation needs max_distance_beyond_m"),
            Math.toRadians(ex.get("max_angle_beyond_deg")?.asDouble ?: throw IllegalArgumentException("extrapolation needs max_angle_beyond_deg")),
        )
    }

    private fun parseTerminal(j: JsonObject): TerminalSpec {
        noUnknown(j, setOf("solver", "ricochet_profile", "shatter_profile", "spall_profile"), "terminal")
        fun ident(k: String) = j.get(k)?.asString?.let(Ident::parse)
        return TerminalSpec(
            solver = ident("solver") ?: throw IllegalArgumentException("terminal needs a solver"),
            ricochetProfile = ident("ricochet_profile"),
            shatterProfile = ident("shatter_profile"),
            spallProfile = ident("spall_profile"),
        )
    }

    private fun parseProvenance(j: JsonObject?): Provenance {
        if (j == null) return Provenance(ProvenanceStatus.FICTIONAL, emptyList(), "no provenance given")
        noUnknown(j, setOf("status", "sources", "notes"), "provenance")
        return Provenance(
            status = SiJson.enumOf<ProvenanceStatus>(j, "status"),
            sources = j.getAsJsonArray("sources")?.map {
                val s = it.asJsonObject
                DataSource(SiJson.enumOf<SourceKind>(s, "kind"), s.get("citation")?.asString ?: throw IllegalArgumentException("source needs a citation"))
            } ?: emptyList(),
            notes = j.get("notes")?.asString,
        )
    }

    private fun obj(j: JsonObject, name: String): JsonObject = j.getAsJsonObject(name) ?: throw IllegalArgumentException("missing '$name' block")

    private fun noUnknown(j: JsonObject, allowed: Set<String>, where: String) {
        val extra = j.keySet() - allowed
        require(extra.isEmpty()) { "unknown key(s) in $where: ${extra.sorted().joinToString()}" }
    }
}
