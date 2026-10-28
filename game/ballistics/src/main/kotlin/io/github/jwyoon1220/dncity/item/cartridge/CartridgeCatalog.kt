// AGENT-DONE(claude): cartridge-items
package io.github.jwyoon1220.dncity.item.cartridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorSpec
import java.io.InputStreamReader

/**
 * The cartridge catalog, `src/main/resources/dncity/cartridges.json`: every caliber and the rounds made in it. Read once at
 * item registration (a classpath file, not a data pack: items cannot change after the registries freeze). The same file
 * drives `tools/cartridges/generate_cartridge_assets.py`, which draws the icons and writes the en_us/ko_kr names, so a
 * round added here needs that script re-run.
 */
object CartridgeCatalog {
    const val RESOURCE = "/dncity/cartridges.json"

    /** [labelEn] matches the generator's English type names; [tracer] rounds feed into the 1-in-5 tracer slot. */
    enum class RoundType(val labelEn: String, val tracer: Boolean = false) {
        FMJ("FMJ"), JHP("JHP"), HP("HP"), RIP("RIP"), AP("AP"), API("API"), OTM("OTM"), JSP("JSP"),
        TRACER("Tracer", true), API_T("API-T", true), HEIAP("HEIAP"), APHEI_T("APHEI-T", true),
        BUCK("00 Buck"), SLUG("Slug"),
    }

    data class Round(
        val caliber: Caliber,
        val id: String,
        /** US military designation (M855, M80, ...), null where there is none. */
        val designation: String?,
        val type: RoundType,
        /** Optional: what it penetrates on an armored SuperbWarfare vehicle (community estimate, see the catalog). */
        val armorPenetration: PenetratorSpec? = null,
    ) {
        val tracer: Boolean get() = type.tracer

        /** "5.56x45 M995 AP": the English item name, shown in every language's tooltip. */
        val designationEn: String get() = listOfNotNull(caliber.nameEn, designation, type.labelEn).joinToString(" ")

        /** Registry path of the item, `cartridge_<caliber>_<round>`. */
        val itemPath: String get() = "cartridge_${caliber.id}_$id"
    }

    data class Caliber(
        val id: String,
        /** TACZ ammo id this caliber's rounds count as. */
        val taczAmmo: String,
        val nameEn: String,
        val stackSize: Int,
    ) {
        lateinit var rounds: List<Round>
            internal set
    }

    val calibers: List<Caliber> by lazy { parse(read()) }

    val rounds: List<Round> get() = calibers.flatMap { it.rounds }

    private fun read(): JsonObject {
        val stream = CartridgeCatalog::class.java.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE is missing from the jar")
        return InputStreamReader(stream, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    internal fun parse(root: JsonObject): List<Caliber> = root.getAsJsonArray("calibers").map { element ->
        val obj = element.asJsonObject
        val caliber = Caliber(
            id = obj["id"].asString,
            taczAmmo = obj["tacz_ammo"].asString,
            nameEn = obj["name_en"].asString,
            stackSize = obj["stack_size"].asInt.coerceIn(1, 99),
        )
        caliber.rounds = obj.getAsJsonArray("rounds").map { r ->
            val round = r.asJsonObject
            Round(
                caliber = caliber,
                id = round["id"].asString,
                designation = round["designation"]?.asString,
                type = RoundType.valueOf(round["type"].asString),
                armorPenetration = round.getAsJsonObject("armor_penetration")?.let { PenetratorSpec.fromJson(it) },
            )
        }
        require(caliber.rounds.any { it.tracer }) { "${caliber.id}: every caliber needs at least one tracer round" }
        caliber
    }
}
