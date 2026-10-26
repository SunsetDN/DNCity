// AGENT-DONE(claude): vehicle-armor-penetration
package io.github.jwyoon1220.dncity.ballistics.vehicle

import com.google.gson.Gson
import com.google.gson.JsonElement
import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.neoforged.neoforge.event.AddReloadListenerEvent

/**
 * `data/<ns>/dncity/vehicle_armor/<name>.json`, one file per vehicle entity type (`"vehicle": "superbwarfare:m_1a_2"`) or one file
 * for several (`"vehicles": [...]`); a vehicle named by two files is an error (the second is skipped and logged).
 * A vehicle without a file keeps SuperbWarfare's own damage handling. A broken file is skipped and logged.
 */
object VehicleArmorRegistry : SimpleJsonResourceReloadListener(Gson(), "dncity/vehicle_armor") {
    @Volatile
    private var byVehicle: Map<String, VehicleArmor> = emptyMap()

    operator fun get(vehicleType: ResourceLocation): VehicleArmor? = byVehicle[vehicleType.toString()]

    fun onAddReloadListeners(event: AddReloadListenerEvent) {
        event.addListener(this)
    }

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        val loaded = HashMap<String, VehicleArmor>()
        for ((id, json) in objects) {
            runCatching { VehicleArmor.fromJson(json.asJsonObject) }
                .onSuccess { armor ->
                    armor.vehicles.forEach { v ->
                        if (loaded.putIfAbsent(v, armor) != null) Dncity.LOGGER.error("Vehicle armor {}: {} already has armor, skipped", id, v)
                    }
                }
                .onFailure { Dncity.LOGGER.error("Invalid vehicle armor {}: {}", id, it.message) }
        }
        byVehicle = loaded
        Dncity.LOGGER.info("Loaded armor for {} vehicles", loaded.size)
    }
}
