package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.block.RadioStationBlockEntity
import io.github.jwyoon1220.dncity.radio.RadioBand
import io.github.jwyoon1220.dncity.radio.RadioBandGroup
import io.github.jwyoon1220.dncity.radio.RadioMode
import io.github.jwyoon1220.dncity.radio.RadioStationRegistry
import io.github.jwyoon1220.dncity.voice.ClientRadioStationReceiver
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

/** Registers [io.github.jwyoon1220.dncity.block.RadioStationBlock]'s client<->server payloads and
 * carries out the server-side effect of each one -- resolving/validating against the target
 * [RadioStationBlockEntity] (re-reading it from the world every time, never trusting anything the
 * client claims about it) before mutating it or [RadioStationRegistry]. Configuring or joining a
 * station also requires the player to actually be near [MAX_INTERACT_DISTANCE] of it right now --
 * without that, a modified client could send either payload for an arbitrary [BlockPos] anywhere
 * on the map, letting a player grief or eavesdrop on a station they've never even visited. Leaving
 * has no such check -- a member is expected to be able to wander off after joining (that's the
 * whole point of joining rather than needing to stand at the block to talk), so it can't be used
 * to gate leaving too. */
object RadioStationNetworking {
    private const val MAX_INTERACT_DISTANCE = 8.0

    private fun blockEntityAt(player: ServerPlayer, pos: BlockPos): RadioStationBlockEntity? =
        player.serverLevel().getBlockEntity(pos) as? RadioStationBlockEntity

    private fun isNear(player: ServerPlayer, pos: BlockPos): Boolean =
        player.position().distanceTo(Vec3.atCenterOf(pos)) <= MAX_INTERACT_DISTANCE

    fun sendOpen(player: ServerPlayer, pos: BlockPos, blockEntity: RadioStationBlockEntity) {
        val names = blockEntity.broadcasterIds().mapNotNull { id ->
            player.serverLevel().server.playerList.getPlayer(id)?.gameProfile?.name
        }
        val joinedByMe = RadioStationRegistry.joinedStationPos(player) == pos
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
            player,
            RadioStationOpenPayload(pos, blockEntity.stationName, blockEntity.frequencyKhz, blockEntity.mode.name, names, joinedByMe),
        )
    }

    private fun sendMembership(player: ServerPlayer) {
        val pos = RadioStationRegistry.joinedStationPos(player)
        val blockEntity = pos?.let { blockEntityAt(player, it) }
        val payload = if (pos != null && blockEntity != null) {
            RadioStationMembershipPayload(true, pos, blockEntity.stationName, blockEntity.frequencyKhz, blockEntity.mode.name)
        } else {
            RadioStationMembershipPayload(false, BlockPos.ZERO, "", 0.0, RadioMode.FM.name)
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload)
    }

    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")

        registrar.playToServer(RadioStationConfigurePayload.TYPE, RadioStationConfigurePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                if (!isNear(player, payload.blockPos)) return@enqueueWork
                val blockEntity = blockEntityAt(player, payload.blockPos) ?: return@enqueueWork
                val mode = RadioMode.entries.firstOrNull { it.name.equals(payload.mode, ignoreCase = true) } ?: return@enqueueWork
                val band = RadioBand.fromFrequencyKhz(payload.frequencyKhz) ?: return@enqueueWork
                if (!RadioBandGroup.BROADCAST.supports(band) || !RadioBandGroup.BROADCAST.supportsMode(mode)) return@enqueueWork

                blockEntity.stationName = payload.stationName.take(32).ifBlank { RadioStationBlockEntity.DEFAULT_NAME }
                blockEntity.frequencyKhz = payload.frequencyKhz
                blockEntity.mode = mode
            }
        })

        registrar.playToServer(RadioStationJoinPayload.TYPE, RadioStationJoinPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val level = player.serverLevel()
                if (payload.joining) {
                    if (!isNear(player, payload.blockPos)) return@enqueueWork
                    val blockEntity = blockEntityAt(player, payload.blockPos) ?: return@enqueueWork
                    RadioStationRegistry.join(level, player, payload.blockPos, blockEntity)
                } else {
                    RadioStationRegistry.leave(level, player)
                }
                sendMembership(player)
            }
        })

        registrar.playToClient(RadioStationOpenPayload.TYPE, RadioStationOpenPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                ClientRadioStationReceiver.handleOpen(payload)
            }
        })

        registrar.playToClient(RadioStationMembershipPayload.TYPE, RadioStationMembershipPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                ClientRadioStationReceiver.handleMembership(payload)
            }
        })
    }
}
