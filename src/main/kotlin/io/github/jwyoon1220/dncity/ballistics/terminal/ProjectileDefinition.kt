// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonObject

/** Outer shape of the projectile. Physical descriptors, not an ammo-type enum: APCR, APDS or APFSDS are combinations of these. */
enum class Geometry { BLUNT, FLAT_NOSE, OGIVE, POINTED, LONG_ROD, SHAPED_CHARGE_CONE }

enum class Stabilization { NONE, SPIN, FIN }

/**
 * The part of the projectile that does the penetrating. For a full-caliber AP round it is the whole projectile; for APFSDS
 * only the rod, which is far narrower than the gun that fired it, so [diameterM] is *not* the gun caliber.
 */
data class PenetratorSpec(
    val material: Ident,
    val diameterM: Double,
    val lengthM: Double,
    val massKg: Double,
    val densityKgM3: Double,
    val hardnessBhn: Double?,
) {
    init {
        require(diameterM > 0.0 && lengthM > 0.0 && massKg > 0.0 && densityKgM3 > 0.0) { "penetrator dimensions must be positive" }
    }

    /** Length over diameter, the number that separates a bullet from a long rod. */
    val slenderness: Double get() = lengthM / diameterM
}

/**
 * Fixed fuze data; the changing part is [FuzeState].
 *
 * @property armingDistanceM flight distance before the fuze can work (0 = armed from the start)
 * @property delayS time from trigger to detonation (0 = instant)
 * @property minTriggerThicknessM an armor layer thinner than this does not trigger the fuze (null = any contact)
 * @property failureChance 0..1 chance that the trigger does nothing
 */
data class FuzeSpec(
    val armingDistanceM: Double,
    val delayS: Double,
    val minTriggerThicknessM: Double?,
    val failureChance: Double,
) {
    init {
        require(armingDistanceM >= 0.0 && delayS >= 0.0) { "fuze distance and delay must not be negative" }
        require(failureChance in 0.0..1.0) { "failure chance must be in 0..1" }
    }
}

/** What the projectile carries besides the penetrator: explosive filler, a shaped-charge liner, a fuze. */
data class PayloadSpec(
    val explosiveKgTnt: Double,
    /** Diameter of the shaped-charge cone, null when the payload is not a shaped charge. */
    val coneDiameterM: Double?,
    /** Distance at which the jet works best, null when not a shaped charge. */
    val optimalStandoffM: Double?,
    /** Null for a payload with no fuze (inert filler). */
    val fuze: FuzeSpec?,
)

/** What flight needs: not the muzzle velocity of any gun (a gun module supplies that), only the projectile's own drag. */
data class ExternalBallisticsSpec(
    val dragCoefficient: Double,
    val muzzleVelocityMps: Double,
)

/**
 * Fixed data of one kind of projectile. Never changes while a projectile flies, that is [ProjectileState]'s job.
 *
 * @property terminalModel the id of the penetrator model ([PenetratorModels]) that decides what happens on armor;
 *   also the key of [ArmorMaterial.resistance]
 */
data class ProjectileDefinition(
    val id: Ident,
    val gunCaliberM: Double,
    val projectileDiameterM: Double,
    val massKg: Double,
    val geometry: Geometry,
    val stabilization: Stabilization,
    val penetrator: PenetratorSpec?,
    val payload: PayloadSpec?,
    val external: ExternalBallisticsSpec,
    val terminalModel: Ident,
) {
    init {
        require(gunCaliberM > 0.0 && projectileDiameterM > 0.0) { "calibers must be positive" }
        require(massKg > 0.0) { "mass must be positive" }
    }

    companion object {
        fun fromJson(id: Ident, json: JsonObject): ProjectileDefinition {
            val projectileDiameter = SiJson.requireLength(json, "diameter")
            val mass = SiJson.requireMass(json, "mass")
            val penetrator = json.getAsJsonObject("penetrator")?.let { p ->
                PenetratorSpec(
                    material = Ident.parse(p.get("material").asString),
                    diameterM = SiJson.length(p, "diameter") ?: projectileDiameter,
                    lengthM = SiJson.requireLength(p, "length"),
                    massKg = SiJson.mass(p, "mass") ?: mass,
                    densityKgM3 = SiJson.density(p) ?: throw IllegalArgumentException("penetrator needs density_kg_m3 or density_g_cm3"),
                    hardnessBhn = p.get("hardness_bhn")?.asDouble,
                )
            }
            val payload = json.getAsJsonObject("payload")?.let { p ->
                PayloadSpec(
                    explosiveKgTnt = SiJson.mass(p, "explosive") ?: 0.0,
                    coneDiameterM = SiJson.length(p, "cone_diameter"),
                    optimalStandoffM = SiJson.length(p, "optimal_standoff"),
                    fuze = p.getAsJsonObject("fuze")?.let { f ->
                        FuzeSpec(
                            armingDistanceM = SiJson.length(f, "arming_distance") ?: 0.0,
                            delayS = f.get("delay_s")?.asDouble ?: 0.0,
                            minTriggerThicknessM = SiJson.length(f, "min_trigger_thickness"),
                            failureChance = f.get("failure_chance")?.asDouble ?: 0.0,
                        )
                    },
                )
            }
            val ext = json.getAsJsonObject("external") ?: JsonObject()
            return ProjectileDefinition(
                id = id,
                gunCaliberM = SiJson.length(json, "gun_caliber") ?: projectileDiameter,
                projectileDiameterM = projectileDiameter,
                massKg = mass,
                geometry = SiJson.enumOf<Geometry>(json, "geometry"),
                stabilization = if (json.has("stabilization")) SiJson.enumOf<Stabilization>(json, "stabilization") else Stabilization.NONE,
                penetrator = penetrator,
                payload = payload,
                external = ExternalBallisticsSpec(
                    dragCoefficient = ext.get("drag_coefficient")?.asDouble ?: 0.3,
                    muzzleVelocityMps = ext.get("muzzle_velocity_mps")?.asDouble ?: 0.0,
                ),
                terminalModel = Ident.parse(json.get("terminal_model")?.asString ?: throw IllegalArgumentException("missing terminal_model")),
            )
        }
    }
}
