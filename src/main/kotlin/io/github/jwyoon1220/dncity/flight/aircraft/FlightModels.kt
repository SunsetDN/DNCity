// AGENT-DONE(claude): aircraft-flight-model
package io.github.jwyoon1220.dncity.flight.aircraft

import com.google.gson.JsonParser
import io.github.jwyoon1220.dncity.Dncity

/**
 * The shipped flight models, `src/main/resources/dncity/flight_models/` (listed in `index.json`), read once from the classpath.
 *
 * Classpath, not a datapack, on purpose: SuperbWarfare moves a piloted aircraft on the pilot's client (`isControlledByLocalInstance`)
 * and the server only receives the result, so both sides must run the same model with the same numbers, and datapack data never
 * reaches the client. A file that fails to parse is logged and skipped; that aircraft keeps SuperbWarfare's own flight.
 */
object FlightModels {
    const val DIRECTORY = "/dncity/flight_models/"

    val byVehicle: Map<String, FlightModel> by lazy { load() }

    private fun load(): Map<String, FlightModel> {
        val index = read("index.json") ?: return emptyMap<String, FlightModel>().also { Dncity.LOGGER.error("Missing {}index.json", DIRECTORY) }
        val names = JsonParser.parseString(index).asJsonObject.getAsJsonArray("files").map { it.asString }
        val models = names.mapNotNull { name ->
            runCatching {
                val text = read(name) ?: throw IllegalArgumentException("listed but missing")
                FlightModelJson.parse(JsonParser.parseString(text).asJsonObject)
            }.onFailure { Dncity.LOGGER.error("Invalid flight model {}{}: {}", DIRECTORY, name, it.message) }.getOrNull()
        }
        Dncity.LOGGER.info("Flight models: {}", models.joinToString { it.vehicle })
        return models.associateBy { it.vehicle }
    }

    private fun read(name: String): String? =
        FlightModels::class.java.getResourceAsStream(DIRECTORY + name)?.use { it.readBytes().toString(Charsets.UTF_8) }
}
