package io.github.jwyoon1220.dncity.radio

import io.github.jwyoon1220.dncity.block.RadioStationBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Server-side authority on who's currently joined to broadcast through which
 * [RadioStationBlockEntity] -- the actual routing decision
 * [io.github.jwyoon1220.dncity.voice.RadioRelay.relay] makes for a player's PTT audio (station
 * membership, if any, always wins over a held [io.github.jwyoon1220.dncity.item.RadioItem]).
 * [RadioStationBlockEntity.broadcasterIds] is kept in sync with this map purely for
 * [io.github.jwyoon1220.dncity.client.RadioStationScreen]'s "지금 방송 중" list -- this map is the
 * one thing that actually decides whether a frame gets relayed as a station broadcast.
 *
 * Not persisted across a server restart (a player who was mid-broadcast when the server stopped
 * just isn't, once it comes back -- they'd need to press PTT again anyway, which requires them to
 * be at their keyboard, so nothing meaningful is lost).
 */
object RadioStationRegistry {
    private val joinedStation = HashMap<UUID, BlockPos>()

    fun join(level: ServerLevel, player: ServerPlayer, pos: BlockPos, blockEntity: RadioStationBlockEntity) {
        leaveWithoutClearing(level, player)
        joinedStation[player.uuid] = pos
        blockEntity.addBroadcaster(player.uuid)
    }

    fun leave(level: ServerLevel, player: ServerPlayer) {
        leaveWithoutClearing(level, player)
    }

    /** The station a player is currently joined to, or `null`. */
    fun joinedStationPos(player: ServerPlayer): BlockPos? = joinedStation[player.uuid]

    fun onPlayerDisconnected(level: ServerLevel, player: ServerPlayer) = leaveWithoutClearing(level, player)

    private fun leaveWithoutClearing(level: ServerLevel, player: ServerPlayer) {
        val previous = joinedStation.remove(player.uuid) ?: return
        (level.getBlockEntity(previous) as? RadioStationBlockEntity)?.removeBroadcaster(player.uuid)
    }
}
