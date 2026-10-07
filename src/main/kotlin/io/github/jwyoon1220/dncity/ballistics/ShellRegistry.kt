// AGENT-DONE(claude): shell-ballistics
package io.github.jwyoon1220.dncity.ballistics

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.neoforged.neoforge.event.AddReloadListenerEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the shells from `data/<namespace>/dncity/shells/<name>.json` and finds the shell of a hit.
 *
 * ```json
 * {
 *   "ammo": "120mm AP",
 *   "entity": "superbwarfare:cannon_shell",
 *   "nbt": { "Type": "AP" },
 *   "caliber_mm": 120,
 *   "penetration": 1.0
 * }
 * ```
 * Optional: `key`, `damage_multiplier` (1), `damage_is_limb_hp` (false), `bleed_chance_bonus` (0),
 * `heavy_bleed_hit_fraction`, `fracture_chance_bonus` (0), `overkill_factor`, `explosive` (false).
 */
object ShellRegistry : SimpleJsonResourceReloadListener(Gson(), "dncity/shells") {
    @Volatile
    private var byEntity: Map<ResourceLocation, List<Shell>> = emptyMap()
    private val keyResolvers = ConcurrentHashMap<ResourceLocation, (Entity) -> String?>()

    /** All loaded shells, for commands and diagnostics. */
    val all: List<Shell> get() = byEntity.values.flatten()

    fun onAddReloadListeners(event: AddReloadListenerEvent) {
        event.addListener(this)
    }

    /**
     * Lets a mod tell shells of the same projectile entity apart by something that is not saved with the entity (TACZ
     * bullets are one entity type, the ammo id is a field). The string is compared with a shell's `key`.
     */
    fun registerKeyResolver(entityType: ResourceLocation, resolver: (Entity) -> String?) {
        keyResolvers[entityType] = resolver
    }

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        val built = HashMap<ResourceLocation, MutableList<Shell>>()
        for ((id, json) in objects) {
            val shell = try {
                parse(id, json.asJsonObject)
            } catch (e: Exception) {
                Dncity.LOGGER.error("Skipping invalid shell {}: {}", id, e.message)
                continue
            }
            built.getOrPut(shell.entity) { ArrayList() }.add(shell)
        }
        byEntity = built
        Dncity.LOGGER.info("Loaded {} shells for {} projectile types", built.values.sumOf { it.size }, built.size)
    }

    private fun parse(id: ResourceLocation, json: JsonObject): Shell {
        fun float(name: String, default: Float) = if (json.has(name)) json.get(name).asFloat else default
        fun optFloat(name: String) = if (json.has(name)) json.get(name).asFloat else null
        val nbt: CompoundTag? = if (json.has("nbt")) {
            CompoundTag.CODEC.parse(JsonOps.INSTANCE, json.get("nbt")).getOrThrow { IllegalArgumentException(it) }
        } else null
        return Shell(
            id = id,
            ammo = if (json.has("ammo")) json.get("ammo").asString else id.path,
            entity = ResourceLocation.parse(json.get("entity").asString),
            key = if (json.has("key")) json.get("key").asString else null,
            nbt = nbt,
            caliberMm = float("caliber_mm", Shell.REFERENCE_CALIBER_MM),
            penetration = float("penetration", 0f).coerceIn(0f, 1f),
            damageMultiplier = float("damage_multiplier", 1f),
            damageIsLimbHp = json.has("damage_is_limb_hp") && json.get("damage_is_limb_hp").asBoolean,
            bleedChanceBonus = float("bleed_chance_bonus", 0f),
            heavyBleedHitFraction = optFloat("heavy_bleed_hit_fraction"),
            fractureChanceBonus = float("fracture_chance_bonus", 0f),
            overkillFactor = optFloat("overkill_factor"),
            explosive = json.has("explosive") && json.get("explosive").asBoolean,
        )
    }

    /** The shell of the projectile that caused [source], null if the hit was not made by known ammunition. */
    fun resolve(source: DamageSource): Shell? {
        val direct = source.directEntity ?: return null
        val type = BuiltInRegistries.ENTITY_TYPE.getKey(direct.type)
        val candidates = byEntity[type] ?: return null
        val key = keyResolvers[type]?.invoke(direct)
        var saved: CompoundTag? = null
        var best: Shell? = null
        var bestScore = -1
        for (shell in candidates) {
            var score = 0
            if (shell.key != null) {
                if (shell.key != key) continue
                score += 2
            }
            if (shell.nbt != null) {
                val data = saved ?: direct.saveWithoutId(CompoundTag()).also { saved = it }
                if (!NbtUtils.compareNbt(shell.nbt, data, true)) continue
                score += 1
            }
            if (score > bestScore) {
                best = shell
                bestScore = score
            }
        }
        return best
    }
}
