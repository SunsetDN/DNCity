// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.client.farentity.FarEntityClient
import io.github.jwyoon1220.dncity.farentity.FarEntityServer
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler
import java.util.UUID

/** Client -> server: how far (blocks, horizontal) this client wants far-player proxies; 0 = none (no LOD terrain). */
class FarEntityRangePayload(val radiusBlocks: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FarEntityRangePayload>(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "far_entity_range"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, FarEntityRangePayload> =
            ByteBufCodecs.VAR_INT.map(::FarEntityRangePayload, FarEntityRangePayload::radiusBlocks).cast()
    }
}

/**
 * Server -> client: players beyond what vanilla tracks for this viewer, and the ones that stopped being far ([removed]: came
 * into vanilla range, left the radius, logged out or changed dimension).
 */
class FarPlayersPayload(val players: List<FarPlayerSnapshot>, val removed: List<UUID>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FarPlayersPayload>(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "far_players"))

        /** More would mean a server with that many players in one dimension; the rest arrive in the next snapshot. */
        const val MAX_PLAYERS = 256

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, FarPlayersPayload> = StreamCodec.composite(
            FarPlayerSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_PLAYERS)), FarPlayersPayload::players,
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_PLAYERS)), FarPlayersPayload::removed,
            ::FarPlayersPayload,
        )
    }
}

/**
 * One far player. Angles are vanilla 256-step bytes. [light] is block light in the high nibble, sky light in the low one,
 * read on the server at the player's feet (the viewer has no chunk there). [intervalTicks] is how often this player is
 * being sent to this viewer, so the client knows how much to lag its interpolation. [gear] only comes when it changed
 * (and with the first snapshot), [vehicle] whenever the player rides something.
 */
class FarPlayerSnapshot(
    val uuid: UUID,
    val x: Double, val y: Double, val z: Double,
    val bodyYaw: Byte, val headYaw: Byte, val pitch: Byte,
    val pose: Pose,
    val invisible: Boolean,
    val light: Byte,
    val intervalTicks: Byte,
    val gear: Gear?,
    val vehicle: Vehicle?,
) {
    /** Name and the six humanoid equipment slots in [SLOTS] order. */
    class Gear(val name: String, val items: List<ItemStack>)

    /**
     * The root vehicle the player rides (a SuperbWarfare tank, a boat, a horse...). [seat] is the player's seat index as the
     * server's vehicle numbers it ([io.github.jwyoon1220.dncity.farentity.FarEntityHooks.seatIndex]), -1 unknown.
     */
    class Vehicle(val type: EntityType<*>, val uuid: UUID, val x: Double, val y: Double, val z: Double, val yaw: Byte, val pitch: Byte, val seat: Int)

    companion object {
        val SLOTS = listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)

        private val ENTITY_TYPE_CODEC = ByteBufCodecs.registry(Registries.ENTITY_TYPE)

        private val GEAR_CODEC: StreamCodec<RegistryFriendlyByteBuf, Gear> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(16), Gear::name,
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(SLOTS.size)), Gear::items,
            ::Gear,
        )

        private val VEHICLE_CODEC: StreamCodec<RegistryFriendlyByteBuf, Vehicle> = StreamCodec.of(
            { buf, v ->
                ENTITY_TYPE_CODEC.encode(buf, v.type)
                UUIDUtil.STREAM_CODEC.encode(buf, v.uuid)
                buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z)
                buf.writeByte(v.yaw.toInt()); buf.writeByte(v.pitch.toInt())
                buf.writeVarInt(v.seat + 1)
            },
            { buf ->
                Vehicle(
                    ENTITY_TYPE_CODEC.decode(buf), UUIDUtil.STREAM_CODEC.decode(buf),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readByte(), buf.readByte(), buf.readVarInt() - 1,
                )
            },
        )

        private val OPTIONAL_GEAR = ByteBufCodecs.optional(GEAR_CODEC)
        private val OPTIONAL_VEHICLE = ByteBufCodecs.optional(VEHICLE_CODEC)

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, FarPlayerSnapshot> = StreamCodec.of(
            { buf, s ->
                UUIDUtil.STREAM_CODEC.encode(buf, s.uuid)
                buf.writeDouble(s.x); buf.writeDouble(s.y); buf.writeDouble(s.z)
                buf.writeByte(s.bodyYaw.toInt()); buf.writeByte(s.headYaw.toInt()); buf.writeByte(s.pitch.toInt())
                Pose.STREAM_CODEC.encode(buf, s.pose)
                buf.writeBoolean(s.invisible)
                buf.writeByte(s.light.toInt())
                buf.writeByte(s.intervalTicks.toInt())
                OPTIONAL_GEAR.encode(buf, java.util.Optional.ofNullable(s.gear))
                OPTIONAL_VEHICLE.encode(buf, java.util.Optional.ofNullable(s.vehicle))
            },
            { buf ->
                FarPlayerSnapshot(
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readByte(), buf.readByte(), buf.readByte(),
                    Pose.STREAM_CODEC.decode(buf),
                    buf.readBoolean(),
                    buf.readByte(),
                    buf.readByte(),
                    OPTIONAL_GEAR.decode(buf).orElse(null),
                    OPTIONAL_VEHICLE.decode(buf).orElse(null),
                )
            },
        )
    }
}

object FarEntityNetworking {
    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToServer(FarEntityRangePayload.TYPE, FarEntityRangePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                FarEntityServer.setRange(player, payload.radiusBlocks)
            }
        })
        // the handler body is only run on a client, so FarEntityClient is never loaded on a dedicated server
        registrar.playToClient(FarPlayersPayload.TYPE, FarPlayersPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork { FarEntityClient.receive(payload) }
        })
    }
}
