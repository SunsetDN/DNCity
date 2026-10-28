// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.farentity

import io.github.jwyoon1220.dncity.network.FarPlayerSnapshot
import io.github.jwyoon1220.dncity.network.FarPlayersPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.LightLayer
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

/**
 * Sends every viewer the players it cannot see through vanilla entity tracking, out to the radius its client asked for
 * ([FarEntityRangePayload][io.github.jwyoon1220.dncity.network.FarEntityRangePayload]: its Distant Horizons LOD radius),
 * so the client can draw them as real player models over LOD terrain (`client/farentity/FarEntityClient`).
 *
 * Vanilla never sends an entity whose chunk the viewer does not have: `ChunkMap.TrackedEntity.updatePlayer` requires
 * horizontal distance <= min(tracking range, view distance x 16) **and** `isChunkTracked`. [vanillaTracks] repeats that
 * test, so a player is in exactly one of the two systems (the client also prefers a real entity when it has one).
 *
 * Anti-wallhack: a far player is only sent while [FarEntityVisibility] (server-side line of sight over the server's own
 * Distant Horizons terrain) says the viewer could see them; when that stops, the viewer gets a removal, so a modified
 * client never holds the position of someone hidden behind terrain out there.
 */
object FarEntityServer {
    /**
     * Upper bound on any client's radius, blocks. `-Ddncity.farEntities.maxBlocks=<n>` changes it, 0 turns the feature off.
     * Default 4096 = DH's LOD radius at 256 chunks; DNCity's own DH default is 128 chunks (2048 blocks).
     */
    val maxRadiusBlocks: Int = Integer.getInteger("dncity.farEntities.maxBlocks", 4096).coerceAtLeast(0)

    private class ViewerState(var radiusBlocks: Int) {
        /** Far players this viewer currently has a proxy for, with the gear hash it last got. */
        val sent = HashMap<UUID, Int>()

        /** Line-of-sight state for every far player in the radius, visible or not. */
        val visibility = HashMap<UUID, FarEntityVisibility.PairState>()
    }

    private val viewers = HashMap<UUID, ViewerState>()

    fun setRange(player: ServerPlayer, radiusBlocks: Int) {
        val radius = radiusBlocks.coerceIn(0, maxRadiusBlocks)
        if (radius == 0) {
            // tell the client to drop what it has, then forget it
            viewers.remove(player.uuid)?.let { state ->
                if (state.sent.isNotEmpty()) PacketDistributor.sendToPlayer(player, FarPlayersPayload(emptyList(), state.sent.keys.toList()))
            }
            return
        }
        viewers.getOrPut(player.uuid) { ViewerState(radius) }.radiusBlocks = radius
    }

    fun onLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        viewers.remove(event.entity.uuid)
    }

    fun onChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        // the client drops every proxy when its level changes (FarEntityClient.onClientTick); start this viewer over
        viewers[event.entity.uuid]?.let {
            it.sent.clear()
            it.visibility.clear()
        }
    }

    fun onServerStopping(event: ServerStoppingEvent) {
        viewers.clear()
        FarEntityVisibility.clear()
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        if (viewers.isEmpty()) return
        val server = event.server
        val tick = server.tickCount
        val serverViewDistance = server.playerList.viewDistance
        val playerTrackingRange = server.getScaledTrackingDistance(EntityType.PLAYER.clientTrackingRange() * 16)
        // one gear hash per player per tick, not one per (viewer, player) pair
        val gearHashes = HashMap<UUID, Int>()
        val now = System.nanoTime()

        for (viewer in server.playerList.players) {
            val state = viewers[viewer.uuid] ?: continue
            val radius = state.radiusBlocks.toDouble()
            val viewDistanceBlocks = Mth.clamp(viewer.requestedViewDistance(), 2, serverViewDistance) * 16.0
            val snapshots = ArrayList<FarPlayerSnapshot>()
            val stillFar = HashSet<UUID>()
            val inRadius = HashSet<UUID>()
            val dimension = viewer.serverLevel().dimension().location().toString()
            val eye = viewer.eyePosition.let { doubleArrayOf(it.x, it.y, it.z) }

            for (target in viewer.serverLevel().players()) {
                if (target === viewer || !target.broadcastToPlayer(viewer)) continue
                val distance = FarEntityMath.horizontalDistance(viewer.x, viewer.z, target.x, target.z)
                if (distance > radius) continue
                if (vanillaTracks(viewer, target, distance, viewDistanceBlocks, playerTrackingRange)) continue
                inRadius += target.uuid
                val sight = state.visibility.getOrPut(target.uuid) { FarEntityVisibility.PairState() }
                FarEntityVisibility.schedule(sight, dimension, eye, doubleArrayOf(target.x, target.y, target.z), distance, now)
                if (!FarEntityVisibility.isVisible(sight, now)) continue
                stillFar += target.uuid

                val interval = FarEntityMath.updateIntervalTicks(distance)
                val known = state.sent[target.uuid]
                val gearHash = gearHashes.getOrPut(target.uuid) { gearHash(target) }
                // stagger targets over the interval instead of sending all of them on the same tick
                if (known != null && known == gearHash && (tick + target.id) % interval != 0) continue
                if (snapshots.size >= FarPlayersPayload.MAX_PLAYERS) continue
                snapshots += snapshot(target, interval, gear = known != gearHash)
                state.sent[target.uuid] = gearHash
            }

            state.visibility.keys.retainAll(inRadius)
            val removed = state.sent.keys.filter { it !in stillFar }
            removed.forEach { state.sent.remove(it) }
            if (snapshots.isNotEmpty() || removed.isNotEmpty()) {
                PacketDistributor.sendToPlayer(viewer, FarPlayersPayload(snapshots, removed))
            }
        }
    }

    /** `ChunkMap.TrackedEntity.updatePlayer`'s condition (1.21.1), from public data. */
    private fun vanillaTracks(viewer: ServerPlayer, target: ServerPlayer, distance: Double, viewDistanceBlocks: Double, trackingRange: Int): Boolean {
        var range = trackingRange
        for (passenger in target.indirectPassengers) range = maxOf(range, viewer.server.getScaledTrackingDistance(passenger.type.clientTrackingRange() * 16))
        val chunk = target.chunkPosition()
        return distance <= minOf(range.toDouble(), viewDistanceBlocks) && viewer.chunkTrackingView.contains(chunk.x, chunk.z)
    }

    private fun gearHash(player: Player): Int {
        var hash = player.gameProfile.name.hashCode()
        for (slot in FarPlayerSnapshot.SLOTS) hash = hash * 31 + ItemStack.hashItemAndComponents(player.getItemBySlot(slot)) * 7 + player.getItemBySlot(slot).count
        return hash
    }

    private fun snapshot(player: ServerPlayer, interval: Int, gear: Boolean): FarPlayerSnapshot {
        val level = player.serverLevel()
        val feet = player.blockPosition()
        val light = (level.getBrightness(LightLayer.BLOCK, feet) shl 4) or level.getBrightness(LightLayer.SKY, feet)
        val root = player.rootVehicle
        val vehicle = if (root !== player) {
            FarPlayerSnapshot.Vehicle(
                root.type, root.uuid, root.x, root.y, root.z,
                FarEntityMath.packDegrees(root.yRot), FarEntityMath.packDegrees(root.xRot),
                FarEntityHooks.seatIndex(root, player),
            )
        } else null
        return FarPlayerSnapshot(
            player.uuid,
            player.x, player.y, player.z,
            FarEntityMath.packDegrees(player.yBodyRot), FarEntityMath.packDegrees(player.yHeadRot), FarEntityMath.packDegrees(player.xRot),
            player.pose,
            player.isInvisible,
            light.toByte(),
            interval.toByte(),
            if (gear) FarPlayerSnapshot.Gear(player.gameProfile.name, FarPlayerSnapshot.SLOTS.map { player.getItemBySlot(it).copy() }) else null,
            vehicle,
        )
    }
}
