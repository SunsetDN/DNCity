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
import net.minecraft.world.entity.projectile.Projectile
import net.neoforged.neoforge.event.AddReloadListenerEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place every projectile of every mod turns into a [Shell]: `ShellRegistry.of(entity)`.
 *
 * The list of shells is data (`data/<namespace>/dncity/shells/**.json`, reloaded with datapacks):
 * ```json
 * {
 *   "ammo": "120mm AP (M1A2)",
 *   "entity": "superbwarfare:cannon_shell",
 *   "key": "superbwarfare:m_1a_2",
 *   "nbt": { "Type": "AP" },
 *   "item": "superbwarfare:large_shell_ap",
 *   "tags": ["cannon", "tank", "ap"],
 *   "caliber_mm": 120,
 *   "penetration": 1.0
 * }
 * ```
 * Optional: `key`, `nbt`, `item`, `tags`, `damage_multiplier` (1), `damage_is_limb_hp` (false), `bleed_chance_bonus` (0),
 * `heavy_bleed_hit_fraction`, `fracture_chance_bonus` (0), `overkill_factor`, `explosive` (false).
 *
 * How a projectile gets its shell, in this order:
 * 1. a weapon (a modular tank's gun module, anything) said so when it fired: [stamp];
 * 2. it was decided before and saved with the entity;
 * 3. it is matched: `entity` is the projectile's entity type, `key` (if the shell has one) must equal what a key resolver
 *    says about the projectile, `nbt` (if present) must be contained in its saved data. The most specific shell wins.
 *    Without a registered resolver the key is the entity type of the vehicle the shooter is riding, so a shell
 *    can be bound to "fired from an M1A2" without that vehicle knowing anything about shells.
 * The result of 3 is saved as 2, so the answer for a projectile never changes mid-flight.
 */
object ShellRegistry : SimpleJsonResourceReloadListener(Gson(), "dncity/shells") {
    @Volatile
    private var byEntity: Map<ResourceLocation, List<Shell>> = emptyMap()

    @Volatile
    private var byId: Map<ResourceLocation, Shell> = emptyMap()
    private val keyResolvers = ConcurrentHashMap<ResourceLocation, (Entity) -> String?>()

    /** All loaded shells. */
    val all: Collection<Shell> get() = byId.values

    fun onAddReloadListeners(event: AddReloadListenerEvent) {
        event.addListener(this)
    }

    fun get(id: ResourceLocation): Shell? = byId[id]

    /** The shells an item loads (a weapon module accepts items, the shell says what they become). */
    fun forItem(item: ResourceLocation): List<Shell> = byId.values.filter { it.item == item }

    /** The shells of at most [caliberMm] carrying [tag], e.g. what a 125 mm tank gun module accepts. */
    fun compatible(caliberMm: Float, tag: String? = null): List<Shell> =
        byId.values.filter { it.caliberMm <= caliberMm && (tag == null || tag in it.tags) }

    /**
     * Lets a mod tell shells of the same projectile entity apart by something that is not saved with the entity (TACZ
     * bullets are one entity type, the ammo id is a field). The string is compared with a shell's `key`.
     */
    fun registerKeyResolver(entityType: ResourceLocation, resolver: (Entity) -> String?) {
        keyResolvers[entityType] = resolver
    }

    /** A weapon states which shell it just fired. Always wins over matching. */
    fun stamp(projectile: Entity, shellId: ResourceLocation) {
        projectile.setData(ModAttachments.SHELL_ID.get(), shellId.toString())
    }

    /** The shell of [source]'s direct cause, null if the hit was not made by known ammunition. */
    fun resolve(source: DamageSource): Shell? = source.directEntity?.let(::of)

    fun of(entity: Entity): Shell? {
        val stamped = entity.getExistingDataOrNull(ModAttachments.SHELL_ID.get())
        if (!stamped.isNullOrEmpty()) {
            ResourceLocation.tryParse(stamped)?.let { byId[it] }?.let { return it }
        }
        val matched = match(entity) ?: return null
        if (!entity.level().isClientSide) {
            entity.setData(ModAttachments.SHELL_ID.get(), matched.id.toString())
        }
        return matched
    }

    private fun match(entity: Entity): Shell? {
        val type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        val candidates = byEntity[type] ?: return null
        val key = (keyResolvers[type] ?: ::shooterVehicleKey).invoke(entity)
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
                val data = saved ?: entity.saveWithoutId(CompoundTag()).also { saved = it }
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

    /** Default key: the type of the vehicle (or vehicle module) the shooter is riding when the projectile is in flight. */
    private fun shooterVehicleKey(entity: Entity): String? {
        val owner = (entity as? Projectile)?.owner ?: return null
        val vehicle = owner.rootVehicle
        if (vehicle === owner) return null
        return BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.type).toString()
    }

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        val ids = HashMap<ResourceLocation, Shell>()
        val entities = HashMap<ResourceLocation, MutableList<Shell>>()
        for ((id, json) in objects) {
            val shell = try {
                parse(id, json.asJsonObject)
            } catch (e: Exception) {
                Dncity.LOGGER.error("Skipping invalid shell {}: {}", id, e.message)
                continue
            }
            ids[id] = shell
            entities.getOrPut(shell.entity) { ArrayList() }.add(shell)
        }
        byId = ids
        byEntity = entities
        Dncity.LOGGER.info("Loaded {} shells for {} projectile types", ids.size, entities.size)
    }

    private fun parse(id: ResourceLocation, json: JsonObject): Shell {
        fun float(name: String, default: Float) = if (json.has(name)) json.get(name).asFloat else default
        fun optFloat(name: String) = if (json.has(name)) json.get(name).asFloat else null
        fun flag(name: String) = json.has(name) && json.get(name).asBoolean
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
            damageIsLimbHp = flag("damage_is_limb_hp"),
            bleedChanceBonus = float("bleed_chance_bonus", 0f),
            heavyBleedHitFraction = optFloat("heavy_bleed_hit_fraction"),
            fractureChanceBonus = float("fracture_chance_bonus", 0f),
            overkillFactor = optFloat("overkill_factor"),
            explosive = flag("explosive"),
            item = if (json.has("item")) ResourceLocation.parse(json.get("item").asString) else null,
            tags = if (json.has("tags")) json.getAsJsonArray("tags").map { it.asString }.toSet() else emptySet(),
        )
    }
}
