// AGENT-DONE(claude): small-arms-penetration
package io.github.jwyoon1220.dncity.ballistics.smallarms

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState

/**
 * Which [BlockCategory] a block is, for bullets. First the block tags `dncity:ballistics/<category>` (data, a modpack can extend
 * them), checked in [BlockCategory] order; then, for blocks no tag names (most modded blocks), the block's sound type, which is the
 * closest thing 1.21 has to the old block material. Anything else is [BlockCategory.UNKNOWN] and stops bullets.
 */
object BallisticBlockCategories {
    private val tags: List<Pair<BlockCategory, TagKey<Block>>> = BlockCategory.entries.filter { it != BlockCategory.UNKNOWN }.map {
        it to TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Dncity.ID, "ballistics/${it.name.lowercase()}"))
    }

    private val bySound: Map<SoundType, BlockCategory> = buildMap {
        listOf(SoundType.GLASS, SoundType.AMETHYST).forEach { put(it, BlockCategory.GLASS) }
        listOf(
            SoundType.GRASS, SoundType.WET_GRASS, SoundType.CROP, SoundType.HARD_CROP, SoundType.VINE, SoundType.SWEET_BERRY_BUSH,
            SoundType.LILY_PAD, SoundType.AZALEA_LEAVES, SoundType.CHERRY_LEAVES, SoundType.CAVE_VINES, SoundType.WEEPING_VINES,
            SoundType.TWISTING_VINES, SoundType.GLOW_LICHEN, SoundType.HANGING_ROOTS, SoundType.ROOTS, SoundType.NETHER_SPROUTS,
            SoundType.FUNGUS, SoundType.BIG_DRIPLEAF, SoundType.SMALL_DRIPLEAF, SoundType.SPORE_BLOSSOM, SoundType.PINK_PETALS,
            SoundType.MOSS_CARPET, SoundType.AZALEA, SoundType.FLOWERING_AZALEA, SoundType.BAMBOO_SAPLING, SoundType.CHERRY_SAPLING,
            SoundType.COBWEB,
        ).forEach { put(it, BlockCategory.FOLIAGE) }
        listOf(
            SoundType.WOOD, SoundType.BAMBOO_WOOD, SoundType.CHERRY_WOOD, SoundType.NETHER_WOOD, SoundType.STEM, SoundType.BAMBOO,
            SoundType.LADDER, SoundType.SCAFFOLDING, SoundType.CHISELED_BOOKSHELF, SoundType.HANGING_SIGN, SoundType.NETHER_WOOD_HANGING_SIGN,
            SoundType.BAMBOO_WOOD_HANGING_SIGN, SoundType.CHERRY_WOOD_HANGING_SIGN,
        ).forEach { put(it, BlockCategory.WOOD) }
        listOf(
            SoundType.GRAVEL, SoundType.SAND, SoundType.SNOW, SoundType.POWDER_SNOW, SoundType.SOUL_SAND, SoundType.SOUL_SOIL,
            SoundType.ROOTED_DIRT, SoundType.MUD, SoundType.PACKED_MUD, SoundType.MOSS, SoundType.SUSPICIOUS_SAND, SoundType.SUSPICIOUS_GRAVEL,
            SoundType.MANGROVE_ROOTS, SoundType.MUDDY_MANGROVE_ROOTS,
        ).forEach { put(it, BlockCategory.SOIL) }
        listOf(
            SoundType.STONE, SoundType.DEEPSLATE, SoundType.DEEPSLATE_BRICKS, SoundType.DEEPSLATE_TILES, SoundType.POLISHED_DEEPSLATE,
            SoundType.TUFF, SoundType.TUFF_BRICKS, SoundType.POLISHED_TUFF, SoundType.CALCITE, SoundType.DRIPSTONE_BLOCK, SoundType.BASALT,
            SoundType.NETHERRACK, SoundType.NETHER_BRICKS, SoundType.NETHER_ORE, SoundType.NETHER_GOLD_ORE, SoundType.NYLIUM,
            SoundType.BONE_BLOCK, SoundType.MUD_BRICKS, SoundType.GILDED_BLACKSTONE, SoundType.CORAL_BLOCK, SoundType.DECORATED_POT,
        ).forEach { put(it, BlockCategory.STONE) }
        listOf(
            SoundType.METAL, SoundType.ANVIL, SoundType.CHAIN, SoundType.COPPER, SoundType.COPPER_BULB, SoundType.COPPER_GRATE,
            SoundType.NETHERITE_BLOCK, SoundType.ANCIENT_DEBRIS, SoundType.LODESTONE, SoundType.LANTERN, SoundType.HEAVY_CORE,
            SoundType.VAULT, SoundType.TRIAL_SPAWNER,
        ).forEach { put(it, BlockCategory.METAL) }
    }

    fun of(state: BlockState): BlockCategory {
        for ((category, tag) in tags) {
            if (state.`is`(tag)) return category
        }
        return bySound[state.soundType] ?: BlockCategory.UNKNOWN
    }
}
