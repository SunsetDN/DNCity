package io.github.jwyoon1220.dncity.block

import com.mojang.serialization.MapCodec
import io.github.jwyoon1220.dncity.network.RadioStationNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * The physical radio station block -- right-clicking it (see [useWithoutItem]) opens
 * [io.github.jwyoon1220.dncity.client.RadioStationScreen] via
 * [RadioStationNetworking.sendOpen] rather than a vanilla `MenuProvider`/`AbstractContainerMenu`,
 * matching this mod's existing pattern for [io.github.jwyoon1220.dncity.item.RadioItem]'s tuning
 * screen (a plain payload carrying the data to display, and a plain `Screen` on the receiving
 * end) instead of the inventory-container machinery. See [RadioStationBlockEntity] for what's
 * actually being edited/joined.
 */
class RadioStationBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<RadioStationBlock> = CODEC

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = RadioStationBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val blockEntity = level.getBlockEntity(pos) as? RadioStationBlockEntity ?: return InteractionResult.PASS
        RadioStationNetworking.sendOpen(player as ServerPlayer, pos, blockEntity)
        return InteractionResult.CONSUME
    }

    companion object {
        private val CODEC: MapCodec<RadioStationBlock> = simpleCodec(::RadioStationBlock)
    }
}
