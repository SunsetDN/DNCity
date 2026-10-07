// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import com.google.gson.JsonObject

/** How well the mass behind a calibration is established. Only [TWO_INDEPENDENT_SOURCES] may back a production preset. */
enum class MassVerification { UNVERIFIED_FIXTURE, SINGLE_SOURCE, TWO_INDEPENDENT_SOURCES }

/**
 * Where a calibration coefficient comes from. A coefficient without this does not load (see [FullCaliberApPreset]): the
 * coefficient absorbs armor resistance, projectile construction, limit-definition mismatch, test method and model error, so
 * it means nothing without the conditions it was fitted under.
 *
 * @property originalDocument the document the data is from; [accessCopy] is the copy actually read, when that is not the original
 * @property massBoundary what the projectile mass includes (complete round / projectile assembly / steel body, windshield, tracer)
 * @property independentSamples null = unknown; never inflated to the row count of a piecewise-linear table
 * @property systematicUncertaintyNotes known biases of this calibration; must not be empty
 * @property limitOutcomeMismatch how the observed limit differs from DNCity's `PERFORATED`; required for every definition except
 *   [BallisticLimitDefinition.MINIMUM_PERFORATION] (a PROTECTION_LIMIT counts armor fragments through the witness plate)
 */
data class CalibrationProvenance(
    val sourceId: String,
    val title: String,
    val originalDocument: String,
    val accessCopy: String?,
    val armorStandard: String,
    val plateClass: String?,
    val ballisticLimitDefinition: BallisticLimitDefinition,
    val projectileDesignation: String,
    val projectileMassKg: Double,
    val massSource: String,
    val massBoundary: String,
    val massVerification: MassVerification,
    val fitMethod: String,
    val sampleCount: Int,
    val independentSamples: Int?,
    val rmsResidualPct: Double?,
    val systematicUncertaintyNotes: List<String>,
    val limitOutcomeMismatch: String?,
) {
    init {
        require(ballisticLimitDefinition == BallisticLimitDefinition.MINIMUM_PERFORATION || !limitOutcomeMismatch.isNullOrBlank()) {
            "provenance: limit_outcome_mismatch is required: a $ballisticLimitDefinition observation is not 'the projectile perforated', " +
                "so it must state how it differs from DNCity's PERFORATED (a residual projectile exists behind the armor)"
        }
        require(projectileMassKg > 0.0 && projectileMassKg.isFinite()) { "provenance: projectile mass must be positive" }
        require(systematicUncertaintyNotes.isNotEmpty()) { "provenance: systematic_uncertainty_notes must not be empty" }
        require(sourceId.isNotBlank() && originalDocument.isNotBlank() && massSource.isNotBlank() && massBoundary.isNotBlank()) {
            "provenance: source_id, original_document, mass_source and mass_boundary are required"
        }
    }

    companion object {
        fun fromJson(json: JsonObject?): CalibrationProvenance {
            requireNotNull(json) { "preset has no provenance" }
            fun str(o: JsonObject, k: String): String = o.get(k)?.takeIf { !it.isJsonNull }?.asString ?: throw IllegalArgumentException("provenance: missing $k")
            val projectile = json.getAsJsonObject("projectile") ?: throw IllegalArgumentException("provenance: missing projectile")
            val defName = str(json, "ballistic_limit_definition")
            return CalibrationProvenance(
                sourceId = str(json, "source_id"),
                title = str(json, "title"),
                originalDocument = str(json, "original_document"),
                accessCopy = json.get("access_copy")?.takeIf { !it.isJsonNull }?.asString,
                armorStandard = str(json, "armor_standard"),
                plateClass = json.get("plate_class")?.takeIf { !it.isJsonNull }?.asString,
                ballisticLimitDefinition = BallisticLimitDefinition.entries.firstOrNull { it.name == defName }
                    ?: throw IllegalArgumentException("provenance: unknown ballistic_limit_definition '$defName'"),
                projectileDesignation = str(projectile, "designation"),
                projectileMassKg = projectile.get("mass_kg")?.takeIf { !it.isJsonNull }?.asDouble
                    ?: throw IllegalArgumentException("provenance: missing projectile.mass_kg"),
                massSource = str(projectile, "mass_source"),
                massBoundary = str(projectile, "mass_boundary"),
                massVerification = str(projectile, "mass_verification").let { name ->
                    MassVerification.entries.firstOrNull { it.name == name }
                        ?: throw IllegalArgumentException("provenance: unknown mass_verification '$name'")
                },
                fitMethod = str(json, "fit_method"),
                sampleCount = json.get("sample_count")?.asInt ?: throw IllegalArgumentException("provenance: missing sample_count"),
                independentSamples = json.get("independent_samples")?.takeIf { !it.isJsonNull }?.asInt,
                rmsResidualPct = json.get("rms_residual_pct")?.takeIf { !it.isJsonNull }?.asDouble,
                systematicUncertaintyNotes = json.getAsJsonArray("systematic_uncertainty_notes")?.map { it.asString }
                    ?: throw IllegalArgumentException("provenance: missing systematic_uncertainty_notes"),
                limitOutcomeMismatch = json.get("limit_outcome_mismatch")?.takeIf { !it.isJsonNull }?.asString,
            )
        }
    }
}
