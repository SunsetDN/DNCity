// AGENT-DONE(claude): sbw-removed-content
package io.github.jwyoon1220.dncity.compat

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.jwyoon1220.dncity.Dncity
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.loot.IGlobalLootModifier
import net.neoforged.neoforge.common.loot.LootModifier
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.OnDatapackSyncEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries

/**
 * Takes SuperbWarfare's sci-fi weapons and all of its hand-held guns out of the game without touching its registries (which the rest of its code refers
 * to): no recipes, no loot, nothing in the creative tabs, and the entities never join a level (ones already in a world are
 * dropped when their chunk loads). Items a player already carries are taken away on login.
 */
object SbwRemovedContent {
    private val NAMES = SbwRemovedNames.NAMES

    private val IDS: Set<ResourceLocation> = NAMES.flatMap { listOf(sbw(it), sbw(it + "_blueprint")) }.toSet()

    private val LOOT_MODIFIERS: DeferredRegister<MapCodec<out IGlobalLootModifier>> =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Dncity.ID)

    fun register(modBus: IEventBus) {
        LOOT_MODIFIERS.register("remove_sbw_content") { -> RemoveLootModifier.CODEC }
        LOOT_MODIFIERS.register(modBus)
        modBus.addListener(::onBuildCreativeTab)
        NeoForge.EVENT_BUS.addListener(::onEntityJoin)
        // after SuperbWarfare's own join handler, which puts a gun in a mob's hand
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ::onMobArmed)
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onDatapackSync)
        NeoForge.EVENT_BUS.addListener(::onLogin)
    }

    fun isRemoved(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (BuiltInRegistries.ITEM.getKey(stack.item) in IDS) return true
        // vehicles are handed out as a container item that names the entity it unpacks into
        val type = stack.get(DataComponents.BLOCK_ENTITY_DATA)?.copyTag()?.getString("EntityType") ?: return false
        return type.isNotEmpty() && ResourceLocation.tryParse(type) in IDS
    }

    private fun isRemoved(recipe: RecipeHolder<*>, server: MinecraftServer): Boolean {
        val id = recipe.id()
        if (SbwRemovedNames.isRemovedRecipe(id.namespace, id.path)) return true
        return isRemoved(recipe.value().getResultItem(server.registryAccess()))
    }

    private fun onBuildCreativeTab(event: BuildCreativeModeTabContentsEvent) {
        for (stack in event.parentEntries.filter(::isRemoved)) event.remove(stack, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY)
        for (stack in event.searchEntries.filter(::isRemoved)) event.remove(stack, CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY)
    }

    private fun onEntityJoin(event: EntityJoinLevelEvent) {
        val entity = event.entity
        if (BuiltInRegistries.ENTITY_TYPE.getKey(entity.type) in IDS || (entity is ItemEntity && isRemoved(entity.item))) {
            event.isCanceled = true
        }
    }

    private fun onMobArmed(event: EntityJoinLevelEvent) {
        val mob = event.entity as? Mob ?: return
        for (hand in InteractionHand.entries) {
            if (isRemoved(mob.getItemInHand(hand))) mob.setItemInHand(hand, ItemStack.EMPTY)
        }
    }

    private fun onServerStarted(event: ServerStartedEvent) = stripRecipes(event.server)

    /** After `/reload` (player is null) the recipes were loaded again; this fires before they are sent to anyone. */
    private fun onDatapackSync(event: OnDatapackSyncEvent) {
        if (event.player == null) stripRecipes(event.playerList.server)
    }

    private fun stripRecipes(server: MinecraftServer) {
        val manager = server.recipeManager
        val all = manager.recipes
        val kept = all.filterNot { isRemoved(it, server) }
        if (kept.size == all.size) return
        manager.replaceRecipes(kept)
        Dncity.LOGGER.info("Removed {} SuperbWarfare sci-fi weapon recipes", all.size - kept.size)
    }

    private fun onLogin(event: PlayerEvent.PlayerLoggedInEvent) {
        val inventory = event.entity.inventory
        for (i in 0 until inventory.containerSize) {
            if (isRemoved(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY)
        }
    }

    private fun sbw(path: String) = ResourceLocation.fromNamespaceAndPath("superbwarfare", path)

    /** `dncity:remove_sbw_content`: strips removed items from every loot roll (SuperbWarfare's blueprint chests included). */
    class RemoveLootModifier(conditions: Array<LootItemCondition>) : LootModifier(conditions) {
        override fun doApply(generatedLoot: ObjectArrayList<ItemStack>, context: LootContext): ObjectArrayList<ItemStack> {
            generatedLoot.removeIf(::isRemoved)
            return generatedLoot
        }

        override fun codec(): MapCodec<out IGlobalLootModifier> = CODEC

        companion object {
            val CODEC: MapCodec<RemoveLootModifier> =
                RecordCodecBuilder.mapCodec { inst -> codecStart(inst).apply(inst, ::RemoveLootModifier) }
        }
    }
}

/** The removed names and recipe rule, kept free of Minecraft classes so plain unit tests can check them against the data. */
internal object SbwRemovedNames {
    /**
     * Every SuperbWarfare hand-held gun: TACZ is the small-arms system (it has the RPG-7 and M320 too). Kept: the guided
     * launchers TACZ has nothing like (javelin, igla_9k38), the repair tool, and the internal vehicle_gun / empty_gun.
     */
    val SMALL_ARMS = setOf(
        "taser", "taser_v2", "glock_17", "glock_18", "mp_443", "m_1911", "homemade_shotgun", "trachelium", "mp_5", "vector",
        "ak_47", "ak_12", "sks", "m_4", "hk_416", "qbz_95", "qbz_191", "insidious", "mk_14", "ql_1031", "marlin", "k_98",
        "mosin_nagant", "svd", "awm", "m_98b", "sentinel", "hunting_rifle", "ntw_20", "m_870", "aa_12", "devotion", "rpk",
        "m_60", "m_2_hb", "minigun", "m_79", "secondary_cataclysm", "rpg", "bocek", "super_star_shooter", "beast_gun_test",
    )

    /** SuperbWarfare ids (entities, items, and each one's `_blueprint`) that are gone. */
    val NAMES = setOf(
        // energy weapons and fictional future vehicles
        "annihilator", "laser_tower", "prism_tank", "prismatic_bolt", "waveforce_tower", "yx_100", "swarm_drone",
    ) + SMALL_ARMS

    /**
     * SuperbWarfare names its recipes after what they make: `yx_100`, `sentinel_smithing`, `rpg_blueprint_copy_researching`.
     * Exact name or one of those suffixes, so `rpg` does not take `rpg_rocket_standard` with it.
     */
    fun isRemovedRecipe(namespace: String, path: String): Boolean {
        if (namespace != "superbwarfare") return false
        val name = path.substringAfterLast('/')
        return NAMES.any { name == it || name.startsWith(it + "_blueprint") || name == it + "_smithing" }
    }
}
