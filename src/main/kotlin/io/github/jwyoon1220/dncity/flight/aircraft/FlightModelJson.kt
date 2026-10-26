// AGENT-DONE(claude): aircraft-flight-model
package io.github.jwyoon1220.dncity.flight.aircraft

import com.google.gson.JsonObject
import kotlin.math.PI

/** Where a number in a flight model file comes from. */
enum class ValueStatus {
    /** Copied from the cited public source. */
    SOURCED,

    /** Computed from sourced numbers (the file's `notes` say how). */
    DERIVED,

    /** A public estimate or a typical textbook value for the class of aircraft; not this aircraft's measured value. */
    ESTIMATE,

    /** Game design, not a physical value. */
    DESIGN,

    /**
     * A characteristic War Thunder publishes on its official wiki (wiki.warthunder.com, upgraded aircraft, Realistic Battles),
     * used as a target the model is fitted to (user request, 2026-10-25). Never datamined game files.
     */
    WAR_THUNDER,
}

/** A parsed flight model: which SuperbWarfare vehicle it drives and its spec. */
sealed interface FlightModel {
    val vehicle: String

    data class FixedWing(override val vehicle: String, val spec: FixedWingSpec) : FlightModel
    data class Helicopter(override val vehicle: String, val spec: HelicopterSpec) : FlightModel
}

/**
 * `data/<ns>/dncity/flight_models/(any depth)/name.json`. Unknown keys are errors. Every number in the file must have an entry in
 * `provenance` (dot path -> [ValueStatus]), and `sources` must list where the SOURCED ones come from: no War Thunder data, ever.
 *
 * `polar.cd_min` (fixed wing) and `drag_area_m2` (helicopter) may instead be derived from a sourced top speed with
 * `derive_cd_min_from` / `derive_drag_area_from` (`{max_speed_mps, altitude_m}`, fixed wing also `with_wep`). Fixed wing:
 * `fit_climb` (`{climb_mps, altitude_m}`, with `derive_cd_min_from`) scales the thrust so the climb rate matches while the top speed
 * holds ([FixedWingSim.fitClimbAndTopSpeed]); `fit_turn_time` (`{turn_time_s, altitude_m, with_wep}`) then fits `cl_max` / Oswald to a
 * sustained turn time ([FixedWingSim.fitTurnTime]). Helicopter: `fit_climb` (`{climb_mps}`, with `derive_drag_area_from`) scales the
 * engine power and derives the drag area together ([HelicopterSim.fitClimbAndTopSpeed]); `fit_hover` (`{collective, altitude_m}`)
 * then raises the power if a hover margin needs more ([HelicopterSim.fitHoverMargin]).
 */
object FlightModelJson {
    fun parse(json: JsonObject): FlightModel {
        checkProvenance(json)
        val vehicle = json.str("vehicle")
        return when (val type = json.str("type")) {
            "fixed_wing" -> FlightModel.FixedWing(vehicle, fixedWing(json))
            "helicopter" -> FlightModel.Helicopter(vehicle, helicopter(json))
            else -> throw IllegalArgumentException("flight model: unknown type '$type'")
        }
    }

    private val TOP_KEYS = setOf("vehicle", "type", "sources", "notes", "provenance")

    /** Rounds of alternating the climb and the turn fit; each round changes the other's result less. */
    private const val FIT_ROUNDS = 6

    private fun fixedWing(json: JsonObject): FixedWingSpec {
        json.allow(
            TOP_KEYS + setOf(
                "mass_kg", "wing_area_m2", "wingspan_m", "polar", "flaps", "mach", "gear_cd", "airbrake_cd", "engine", "controls",
                "handling", "limits", "ground", "derive_cd_min_from", "fit_turn_time", "fit_climb",
            ),
        )
        val area = json.num("wing_area_m2")
        val span = json.num("wingspan_m")
        val ar = span * span / area
        val p = json.obj("polar").allow("cl0", "cl_max", "cl_min", "cd_min", "oswald", "stall_width_deg")
        val derive = json.optObj("derive_cd_min_from")
        require((derive != null) != p.has("cd_min")) { "flight model: give exactly one of polar.cd_min and derive_cd_min_from" }
        val polar = WingPolar(
            liftSlopePerRad = finiteWingLiftSlope(ar),
            cl0 = p.num("cl0"),
            clMax = p.num("cl_max"),
            clMin = p.num("cl_min"),
            cdMin = if (derive == null) p.num("cd_min") else 0.01,
            oswald = p.num("oswald"),
            aspectRatio = ar,
            stallWidthRad = p.num("stall_width_deg") * PI / 180.0,
        )
        val flaps = json.optObj("flaps")?.allow("d_cl0", "d_cl_max", "d_cd_min")?.let { FlapsEffect(it.num("d_cl0"), it.num("d_cl_max"), it.num("d_cd_min")) }
        val mach = json.obj("mach").allow("critical", "wave_drag_peak").let { MachDrag(it.num("critical"), it.num("wave_drag_peak")) }
        val ctl = json.obj("controls").allow("full_authority_ias_mps", "compression_start_ias_mps", "compression_end_ias_mps", "compression_floor")
        val han = json.obj("handling").allow(
            "max_roll_rate_deg_s", "max_pitch_rate_deg_s", "max_yaw_rate_deg_s", "yaw_stability_per_s", "aoa_response_per_s",
            "stall_nose_drop_deg_s", "side_force_per_rad", "instructor_aoa_margin",
        )
        val gr = json.obj("ground").allow("rolling_friction", "brake_friction", "lateral_friction")
        val spec = FixedWingSpec(
            massKg = json.num("mass_kg"),
            wingAreaM2 = area,
            polar = polar,
            flaps = flaps,
            mach = mach,
            gearCd = json.num("gear_cd"),
            airbrakeCd = json.num("airbrake_cd"),
            engine = engine(json.obj("engine")),
            authority = ControlAuthority(
                ctl.num("full_authority_ias_mps"), ctl.num("compression_start_ias_mps"), ctl.num("compression_end_ias_mps"), ctl.num("compression_floor"),
            ),
            handling = FixedWingHandling(
                han.num("max_roll_rate_deg_s"), han.num("max_pitch_rate_deg_s"), han.num("max_yaw_rate_deg_s"), han.num("yaw_stability_per_s"),
                han.num("aoa_response_per_s"), han.num("stall_nose_drop_deg_s"), han.num("side_force_per_rad"), han.num("instructor_aoa_margin"),
            ),
            limits = limits(json.obj("limits")),
            ground = GroundHandling(gr.num("rolling_friction"), gr.num("brake_friction"), gr.num("lateral_friction")),
        )
        var fitted = spec
        if (derive != null) {
            derive.allow("max_speed_mps", "altitude_m", "with_wep")
            val wep = derive.get("with_wep")?.asBoolean ?: false
            val cd0 = FixedWingSim.deriveCdMin(spec, derive.num("max_speed_mps"), derive.num("altitude_m"), wep)
            fitted = fitted.copy(polar = fitted.polar.copy(cdMin = cd0))
        } else {
            require(!json.has("fit_climb")) { "flight model: fit_climb needs derive_cd_min_from (the top speed it must keep)" }
        }
        val climb = json.optObj("fit_climb")?.allow("climb_mps", "altitude_m")
        val turn = json.optObj("fit_turn_time")?.allow("turn_time_s", "altitude_m", "with_wep")
        // Climb (thrust + cd_min) and turn (Oswald / cl_max) pull on each other: alternate until both hold
        repeat(if (climb != null && turn != null) FIT_ROUNDS else 1) {
            if (climb != null && derive != null) {
                fitted = FixedWingSim.fitClimbAndTopSpeed(
                    fitted, climb.num("climb_mps"), climb.num("altitude_m"), derive.num("max_speed_mps"), derive.num("altitude_m"),
                    derive.get("with_wep")?.asBoolean ?: false,
                )
            }
            if (turn != null) {
                fitted = FixedWingSim.fitTurnTime(fitted, turn.num("turn_time_s"), turn.num("altitude_m"), turn.get("with_wep")?.asBoolean ?: false)
                if (derive != null) {
                    val cd0 = FixedWingSim.deriveCdMin(fitted, derive.num("max_speed_mps"), derive.num("altitude_m"), derive.get("with_wep")?.asBoolean ?: false)
                    fitted = fitted.copy(polar = fitted.polar.copy(cdMin = cd0))
                }
            }
        }
        return fitted
    }

    private fun engine(e: JsonObject): Powerplant = when (val kind = e.str("kind")) {
        "jet" -> {
            e.allow("kind", "count", "static_thrust_n", "wep_thrust_n", "density_exponent", "mach_lapse", "spool_time_s")
            Powerplant.Jet(
                e.int("count"), e.num("static_thrust_n"), e.optNum("wep_thrust_n"), e.num("density_exponent"), e.num("mach_lapse"), e.num("spool_time_s"),
            )
        }
        "propeller" -> {
            e.allow("kind", "count", "power_w", "wep_power_w", "efficiency", "diameter_m", "static_figure_of_merit", "spool_time_s")
            Powerplant.Propeller(
                e.int("count"), e.num("power_w"), e.optNum("wep_power_w"), e.num("efficiency"), e.num("diameter_m"), e.num("static_figure_of_merit"),
                e.num("spool_time_s"),
            )
        }
        else -> throw IllegalArgumentException("flight model: unknown engine kind '$kind'")
    }

    private fun limits(l: JsonObject): StructuralLimits {
        l.allow("load_factor_pos", "load_factor_neg", "ultimate_factor", "vne_ias_mps", "flaps_max_ias_mps", "gear_max_ias_mps", "vne_mach")
        return StructuralLimits(
            l.num("load_factor_pos"), l.num("load_factor_neg"), l.num("ultimate_factor"), l.num("vne_ias_mps"), l.optNum("flaps_max_ias_mps"),
            l.optNum("gear_max_ias_mps"), l.optNum("vne_mach"),
        )
    }

    private fun helicopter(json: JsonObject): HelicopterSpec {
        json.allow(
            TOP_KEYS + setOf(
                "mass_kg", "rotor_radius_m", "engine_power_w", "power_density_exponent", "tail_rotor_power_fraction", "figure_of_merit",
                "drag_area_m2", "vertical_drag_area_m2", "vortex_ring_max_thrust_loss", "limits", "derive_drag_area_from", "fit_climb", "fit_hover",
            ),
        )
        val derive = json.optObj("derive_drag_area_from")
        require((derive != null) != json.has("drag_area_m2")) { "flight model: give exactly one of drag_area_m2 and derive_drag_area_from" }
        val spec = HelicopterSpec(
            massKg = json.num("mass_kg"),
            rotorRadiusM = json.num("rotor_radius_m"),
            enginePowerW = json.num("engine_power_w"),
            powerDensityExponent = json.num("power_density_exponent"),
            tailRotorPowerFraction = json.num("tail_rotor_power_fraction"),
            figureOfMerit = json.num("figure_of_merit"),
            dragAreaM2 = if (derive == null) json.num("drag_area_m2") else 1.0,
            verticalDragAreaM2 = json.num("vertical_drag_area_m2"),
            vortexRingMaxThrustLoss = json.num("vortex_ring_max_thrust_loss"),
            limits = limits(json.obj("limits")),
        )
        if (derive == null) return spec
        derive.allow("max_speed_mps", "altitude_m")
        val climb = json.optObj("fit_climb")?.allow("climb_mps")
        val hover = json.optObj("fit_hover")?.allow("collective", "altitude_m")
        if (climb == null) return spec.copy(dragAreaM2 = HelicopterSim.deriveDragArea(spec, derive.num("max_speed_mps"), derive.num("altitude_m")))
        // Power from the climb rate, then raised if the hover margin needs more (the drag area follows the power each time)
        var fitted = HelicopterSim.fitClimbAndTopSpeed(spec, climb.num("climb_mps"), derive.num("max_speed_mps"), derive.num("altitude_m"))
        if (hover != null) {
            fitted = HelicopterSim.fitHoverMargin(fitted, hover.num("collective"), hover.num("altitude_m"), derive.num("max_speed_mps"), derive.num("altitude_m"))
        }
        return fitted
    }

    /** Every number in the file needs a provenance entry, and every entry must name a number that exists. */
    private fun checkProvenance(json: JsonObject) {
        val sources = json.getAsJsonArray("sources") ?: throw IllegalArgumentException("flight model: missing sources")
        require(sources.size() > 0) { "flight model: sources must not be empty" }
        val prov = json.obj("provenance")
        val numbers = mutableSetOf<String>()
        fun walk(o: JsonObject, prefix: String) {
            for ((k, v) in o.entrySet()) {
                if (prefix.isEmpty() && k == "provenance") continue
                val path = if (prefix.isEmpty()) k else "$prefix.$k"
                when {
                    v.isJsonObject -> walk(v.asJsonObject, path)
                    v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> numbers += path
                }
            }
        }
        walk(json, "")
        val declared = prov.entrySet().associate { (k, v) ->
            k to (ValueStatus.entries.firstOrNull { it.name == v.asString } ?: throw IllegalArgumentException("flight model: unknown status '${v.asString}' for $k"))
        }
        val missing = numbers - declared.keys
        val extra = declared.keys - numbers
        require(missing.isEmpty()) { "flight model: no provenance for $missing" }
        require(extra.isEmpty()) { "flight model: provenance for numbers that are not in the file: $extra" }
    }

    private fun JsonObject.allow(vararg keys: String): JsonObject = allow(keys.toSet())

    private fun JsonObject.allow(keys: Set<String>): JsonObject {
        val unknown = keySet() - keys
        require(unknown.isEmpty()) { "flight model: unknown key(s) $unknown" }
        return this
    }

    private fun JsonObject.str(k: String): String = get(k)?.asString ?: throw IllegalArgumentException("flight model: missing $k")
    private fun JsonObject.num(k: String): Double = get(k)?.asDouble ?: throw IllegalArgumentException("flight model: missing $k")
    private fun JsonObject.int(k: String): Int = get(k)?.asInt ?: throw IllegalArgumentException("flight model: missing $k")
    private fun JsonObject.optNum(k: String): Double? = get(k)?.takeUnless { it.isJsonNull }?.asDouble
    private fun JsonObject.obj(k: String): JsonObject = getAsJsonObject(k) ?: throw IllegalArgumentException("flight model: missing $k")
    private fun JsonObject.optObj(k: String): JsonObject? = getAsJsonObject(k)
}
