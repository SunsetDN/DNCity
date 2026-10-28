// AGENT-DONE(claude): vehicle-damage-model
package io.github.jwyoon1220.dncity.ballistics.network

import io.github.jwyoon1220.dncity.Dncity
import io.netty.buffer.ByteBuf
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

/**
 * Server -> shooter: one armor hit on a vehicle, for the War Thunder-style hit camera
 * ([io.github.jwyoon1220.dncity.client.render.VehicleHitCamOverlay]). All positions are in the hit vehicle's own frame
 * (+Z front, +Y up, +X left, blocks), so the client draws the vehicle the same way whatever it was facing.
 */
class VehicleHitReportPayload(
    val vehicleType: String,
    /** [io.github.jwyoon1220.dncity.ballistics.vehicle.HitOutcome] ordinal. */
    val outcome: Int,
    /** [io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorKind] ordinal. */
    val kind: Int,
    val zone: String,
    /** [io.github.jwyoon1220.dncity.ballistics.vehicle.Face] ordinal. */
    val face: Int,
    val armorMm: Float,
    val effectiveMm: Float,
    val penetrationMm: Float,
    val angleDeg: Float,
    val depthBlocks: Float,
    /** Modules damaged by this hit: module ids, or for a vehicle without modules engine, turret, track_left, track_right, crew. */
    val modules: List<String>,
    val boxes: List<Box>,
    val entry: FloatArray,
    val direction: FloatArray,
    /** Where the shell stopped (penetrated: end of its path inside; not penetrated: the plate; ricochet: where it glanced to). */
    val stop: FloatArray,
    /** [io.github.jwyoon1220.dncity.ballistics.vehicle.ReactiveType] ordinal of the layer this hit set off, -1 none. */
    val reactiveType: Int = -1,
    val reactiveMm: Float = 0f,
    val overmatch: Boolean = false,
    val ricochetChance: Float = 0f,
    /** Every internal module with its state after the hit. */
    val moduleBoxes: List<ModuleBox> = emptyList(),
    /** The shell or jet, spall and blast fragments behind the plate. */
    val rays: List<Ray> = emptyList(),
    /** `<event type>[:<module id>]`, see [io.github.jwyoon1220.dncity.ballistics.vehicle.damage.EventType]. */
    val events: List<String> = emptyList(),
    /** Blast centre x, y, z and radius (0 = no blast). */
    val blast: FloatArray = FloatArray(4),
    /** [io.github.jwyoon1220.dncity.ballistics.vehicle.damage.DestroyCause] ordinal, -1 still fighting. */
    val destroyed: Int = -1,
) : CustomPacketPayload {
    /** One OBB of the vehicle: its 8 corners in SuperbWarfare's `OBB.getVertices()` order. */
    class Box(val part: String, val hit: Boolean, val corners: FloatArray)

    /**
     * One internal module: [type] is a [io.github.jwyoon1220.dncity.ballistics.vehicle.damage.ModuleType] ordinal, [condition]
     * 0 intact / 1 damaged / 2 destroyed, [hit] whether this hit reached it; corners as [Box].
     */
    class ModuleBox(val id: String, val type: Int, val condition: Int, val hit: Boolean, val corners: FloatArray)

    /** [kind] is a [io.github.jwyoon1220.dncity.ballistics.vehicle.damage.RayKind] ordinal. */
    class Ray(val kind: Int, val hit: Boolean, val from: FloatArray, val to: FloatArray)

    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<VehicleHitReportPayload>(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "vehicle_hit_report"))

        private const val MAX_BOXES = 48
        private const val MAX_MODULES = 32
        private const val MAX_RAYS = 160
        private const val MAX_EVENTS = 24
        private const val MAX_STRING = 64

        private fun FriendlyByteBuf.writeVec(v: FloatArray) { writeFloat(v[0]); writeFloat(v[1]); writeFloat(v[2]) }
        private fun FriendlyByteBuf.readVec() = floatArrayOf(readFloat(), readFloat(), readFloat())

        val STREAM_CODEC: StreamCodec<ByteBuf, VehicleHitReportPayload> = StreamCodec.of(
            { raw, p ->
                val buf = FriendlyByteBuf(raw)
                buf.writeUtf(p.vehicleType, MAX_STRING)
                buf.writeVarInt(p.outcome); buf.writeVarInt(p.kind)
                buf.writeUtf(p.zone, MAX_STRING); buf.writeVarInt(p.face)
                buf.writeFloat(p.armorMm); buf.writeFloat(p.effectiveMm); buf.writeFloat(p.penetrationMm)
                buf.writeFloat(p.angleDeg); buf.writeFloat(p.depthBlocks)
                val modules = p.modules.take(MAX_MODULES)
                buf.writeVarInt(modules.size); modules.forEach { buf.writeUtf(it, MAX_STRING) }
                val boxes = p.boxes.take(MAX_BOXES)
                buf.writeVarInt(boxes.size)
                boxes.forEach { box ->
                    buf.writeUtf(box.part, MAX_STRING); buf.writeBoolean(box.hit)
                    for (i in 0 until 24) buf.writeFloat(box.corners[i])
                }
                buf.writeVec(p.entry); buf.writeVec(p.direction); buf.writeVec(p.stop)
                buf.writeVarInt(p.reactiveType + 1); buf.writeFloat(p.reactiveMm)
                buf.writeBoolean(p.overmatch); buf.writeFloat(p.ricochetChance)
                val moduleBoxes = p.moduleBoxes.take(MAX_MODULES)
                buf.writeVarInt(moduleBoxes.size)
                moduleBoxes.forEach { m ->
                    buf.writeUtf(m.id, MAX_STRING); buf.writeVarInt(m.type); buf.writeVarInt(m.condition); buf.writeBoolean(m.hit)
                    for (i in 0 until 24) buf.writeFloat(m.corners[i])
                }
                val rays = p.rays.take(MAX_RAYS)
                buf.writeVarInt(rays.size)
                rays.forEach { r -> buf.writeVarInt(r.kind); buf.writeBoolean(r.hit); buf.writeVec(r.from); buf.writeVec(r.to) }
                val events = p.events.take(MAX_EVENTS)
                buf.writeVarInt(events.size); events.forEach { buf.writeUtf(it, MAX_STRING * 2) }
                for (i in 0 until 4) buf.writeFloat(p.blast[i])
                buf.writeVarInt(p.destroyed + 1)
            },
            { raw ->
                val buf = FriendlyByteBuf(raw)
                val vehicleType = buf.readUtf(MAX_STRING)
                val outcome = buf.readVarInt(); val kind = buf.readVarInt()
                val zone = buf.readUtf(MAX_STRING); val face = buf.readVarInt()
                val armor = buf.readFloat(); val effective = buf.readFloat(); val pen = buf.readFloat()
                val angle = buf.readFloat(); val depth = buf.readFloat()
                val moduleCount = buf.readVarInt().coerceIn(0, MAX_MODULES)
                val modules = List(moduleCount) { buf.readUtf(MAX_STRING) }
                val boxCount = buf.readVarInt().coerceIn(0, MAX_BOXES)
                val boxes = List(boxCount) {
                    val part = buf.readUtf(MAX_STRING); val hit = buf.readBoolean()
                    Box(part, hit, FloatArray(24) { buf.readFloat() })
                }
                val entry = buf.readVec(); val direction = buf.readVec(); val stop = buf.readVec()
                val reactiveType = buf.readVarInt() - 1; val reactiveMm = buf.readFloat()
                val overmatch = buf.readBoolean(); val ricochetChance = buf.readFloat()
                val moduleBoxes = List(buf.readVarInt().coerceIn(0, MAX_MODULES)) {
                    ModuleBox(buf.readUtf(MAX_STRING), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), FloatArray(24) { buf.readFloat() })
                }
                val rays = List(buf.readVarInt().coerceIn(0, MAX_RAYS)) { Ray(buf.readVarInt(), buf.readBoolean(), buf.readVec(), buf.readVec()) }
                val events = List(buf.readVarInt().coerceIn(0, MAX_EVENTS)) { buf.readUtf(MAX_STRING * 2) }
                val blast = FloatArray(4) { buf.readFloat() }
                val destroyed = buf.readVarInt() - 1
                VehicleHitReportPayload(
                    vehicleType, outcome, kind, zone, face, armor, effective, pen, angle, depth, modules, boxes,
                    entry, direction, stop, reactiveType, reactiveMm, overmatch, ricochetChance, moduleBoxes, rays, events, blast, destroyed,
                )
            },
        )
    }
}

/** The last hit report the local player received; read by the hit-camera HUD. No client-only types here. */
object VehicleHitCamState {
    @Volatile
    var latest: VehicleHitReportPayload? = null
        private set

    @Volatile
    var receivedAtMs: Long = 0
        private set

    fun show(report: VehicleHitReportPayload) {
        latest = report
        receivedAtMs = System.currentTimeMillis()
    }

    fun clear() {
        latest = null
    }
}

object VehicleHitNetworking {
    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        event.registrar("2").playToClient(VehicleHitReportPayload.TYPE, VehicleHitReportPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork { VehicleHitCamState.show(payload) }
        })
    }
}
