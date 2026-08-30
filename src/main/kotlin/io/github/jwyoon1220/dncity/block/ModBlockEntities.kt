package io.github.jwyoon1220.dncity.block

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.neoforge.registries.DeferredRegister

// THIS LINE IS REQUIRED FOR USING PROPERTY DELEGATES
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModBlockEntities {
    val REGISTRY: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Dncity.ID)

    // Explicit type annotation breaks a circular-inference loop with RadioStationBlockEntity's
    // own supertype constructor call, which needs this property's type resolved to build --
    // without it, the two files' mutually-referencing initializers confuse the type checker into
    // unrelated-looking errors (observed: a phantom `kotlin.text.MatchGroup` type).
    val RADIO_STATION: BlockEntityType<RadioStationBlockEntity> by
        REGISTRY.register("radio_station") { -> BlockEntityType.Builder.of(::RadioStationBlockEntity, ModBlocks.RADIO_STATION).build(null) }
}
