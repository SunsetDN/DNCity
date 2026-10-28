package io.github.jwyoon1220.dncity.block

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.neoforge.registries.DeferredRegister

// THIS LINE IS REQUIRED FOR USING PROPERTY DELEGATES
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModBlocks {
    val REGISTRY = DeferredRegister.createBlocks(Dncity.ID)

    // If you get an "overload resolution ambiguity" error, include the arrow at the start of the closure.
    val EXAMPLE_BLOCK by REGISTRY.register("example_block") { ->
        Block(BlockBehaviour.Properties.of().lightLevel { 15 }.strength(3.0f))
    }

    val RADIO_STATION by REGISTRY.register("radio_station") { ->
        RadioStationBlock(BlockBehaviour.Properties.of().strength(3.0f).noOcclusion())
    }
}
