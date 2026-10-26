// AGENT-DONE(claude): oblique-full-caliber-ap
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.JsonDataRegistry

/**
 * `data/<ns>/dncity/full_caliber_ap_presets/(any depth)/name.json` as a reload listener: the calibrations [FullCaliberApModel] reads.
 * Strict on purpose ([FullCaliberApPreset.fromJson] with `requireVerifiedMass = true`): a preset without a verified mass and a
 * named source does not load. **No preset is shipped**; until a data pack provides one, every query is answered Unresolved.
 */
object FullCaliberApPresetRegistry : JsonDataRegistry<FullCaliberApPreset>("dncity/full_caliber_ap_presets", "full-caliber AP presets") {
    override fun parse(id: Ident, json: JsonObject) = FullCaliberApPreset.fromJson(id, json)
}
