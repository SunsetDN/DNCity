// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller

/**
 * A data-driven catalog loaded from `data/<ns>/<directory>/(any depth)/name.json`. This is the Minecraft boundary: ids are
 * converted to [Ident] here and the parsing itself lives in the plain data classes (`fromJson`), testable without the game.
 *
 * Strict by default: one broken file fails the whole (re)load, because a server that comes up with a missing shell, armor or
 * material would quietly hand out wrong ballistics. For development, `-Ddncity.ballistics.lenient=true` logs the broken
 * file and skips it instead.
 */
abstract class JsonDataRegistry<T : Any>(directory: String, private val label: String) :
    SimpleJsonResourceReloadListener(Gson(), directory) {

    @Volatile
    private var entries: Map<Ident, T> = emptyMap()

    protected abstract fun parse(id: Ident, json: JsonObject): T

    operator fun get(id: Ident): T? = entries[id]

    fun getOrThrow(id: Ident): T = entries[id] ?: throw NoSuchElementException("unknown $label $id")

    fun all(): Collection<T> = entries.values

    fun ids(): Set<Ident> = entries.keys

    fun asMap(): Map<Ident, T> = entries

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        val loaded = HashMap<Ident, T>()
        val errors = ArrayList<String>()
        for ((rl, json) in objects) {
            val id = Ident(rl.namespace, rl.path)
            try {
                loaded[id] = parse(id, json.asJsonObject)
            } catch (e: Exception) {
                errors += "$id: ${e.message}"
            }
        }
        if (errors.isNotEmpty()) {
            if (STRICT) {
                throw IllegalStateException("Invalid $label (${errors.size}):\n  " + errors.joinToString("\n  "))
            }
            errors.forEach { Dncity.LOGGER.error("Skipping invalid {} {}", label, it) }
        }
        entries = loaded
        Dncity.LOGGER.info("Loaded {} {}", loaded.size, label)
    }

    companion object {
        val STRICT: Boolean get() = !java.lang.Boolean.getBoolean("dncity.ballistics.lenient")
    }
}
