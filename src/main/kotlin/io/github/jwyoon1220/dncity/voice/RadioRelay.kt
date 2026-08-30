package io.github.jwyoon1220.dncity.voice

import io.github.jwyoon1220.dncity.block.RadioStationBlockEntity
import io.github.jwyoon1220.dncity.network.RadioAudioRelayPayload
import io.github.jwyoon1220.dncity.network.RadioRangeInfo
import io.github.jwyoon1220.dncity.network.RadioSenderPosition
import io.github.jwyoon1220.dncity.radio.RadioActions
import io.github.jwyoon1220.dncity.radio.RadioBand
import io.github.jwyoon1220.dncity.radio.RadioStationRegistry
import io.github.jwyoon1220.dncity.radio.RadioVoice
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.abs

/**
 * Server-side fan-out for radio-voice frames: authoritative on all three things a real receiver's
 * reception depends on -- who's transmitting (either some hotbar radio of the sender's that's
 * [io.github.jwyoon1220.dncity.item.RadioItem.canTransmit], powered, with an enabled active slot
 * -- see [RadioActions.transmittableHotbarRadio] -- or, taking priority over that, a
 * [io.github.jwyoon1220.dncity.block.RadioStationBlockEntity] the sender has joined -- see
 * [RadioStationRegistry] and [relayFromStation]), who's listening (every other player carrying a
 * powered hotbar radio with some slot tuned within the transmit band's tolerance, and within the
 * band's effective range for that specific listener -- see [effectiveRangeFor]), and *which* of
 * possibly several simultaneous same-channel transmitters that listener actually hears -- see
 * [recentTransmissions]/[strongestCompetitorPower]. Mirrors [VoiceRelay]'s "server decides who's
 * in range, client decides how it sounds" split, except the *range* half of that decision now
 * needs the server's own view of the world (terrain, time of day, weather, transmit power) rather
 * than being one flat per-band constant.
 */
object RadioRelay {

    /** A high-power station's fixed transmit power (see [RadioVoice.powerRangeMultiplier] -- 5x
     * this reaches 5x/sqrt as far as an ordinary handheld's `1.0`, and dominates (see
     * [RadioVoice.isDrownedOutBy]) any handheld transmitting on the same channel within reach). */
    private const val STATION_POWER = 25.0

    /** One transmitter's most recent frame, kept just long enough to let a *different* sender's
     * [relay]/[relayFromStation] call find out who else is currently talking on the same channel
     * (see [strongestCompetitorPower]) -- a real receiver doesn't know in advance who else might
     * key up, so this has to be derived from live traffic rather than radio state alone.
     * Snapshotting position/eye per frame (rather than re-reading the live entity) means a
     * transmitter who has since moved, retuned, or logged off still resolves correctly for the
     * short window before this entry goes stale -- close enough for a co-channel check with no
     * observable staleness in practice, since frames arrive every ~20ms while PTT is held. */
    private class RecentTransmission(
        val band: RadioBand,
        val frequencyKhz: Double,
        val power: Double,
        val position: Vec3,
        val eye: Vec3,
        var lastFrameTick: Long,
    )

    private val recentTransmissions = HashMap<Int, RecentTransmission>()

    /** Real VHF/UHF only actually needs an unobstructed path once (through the last bit of solid
     * ground/wall, not into it) to be judged line-of-sight -- clipping straight through non-solid
     * blocks (leaves, water, etc.) already works for free via [ClipContext.Block.COLLIDER]. Uses
     * an empty [CollisionContext] rather than a specific entity so this works uniformly for a live
     * sender, a fixed station position, and a [RecentTransmission] snapshot (none of which
     * necessarily have a live entity to hand it) alike -- the only practical effect is that a
     * transmitter standing exactly on the ray no longer excludes their own hitbox, a negligible
     * edge case for a voice-range check. */
    private fun hasLineOfSight(level: ServerLevel, from: Vec3, to: Vec3): Boolean {
        val clip = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
        return clip.type == HitResult.Type.MISS
    }

    /** Height of [pos] above the terrain surface directly below it -- what actually matters for a
     * radio-horizon-style elevation bonus, not raw Y (a radio at y=64 on a mountain plateau isn't
     * "elevated" the way one at y=64 above a ravine floor is). Never negative. */
    private fun heightAboveTerrain(level: ServerLevel, pos: Vec3): Double {
        val surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x.toInt(), pos.z.toInt())
        return (pos.y - surfaceY).coerceAtLeast(0.0)
    }

    /**
     * This transmission's effective range for a listener at [listenerPos]/[listenerEye], plus
     * whether it arrived despite terrain obstruction (static-only penalty, not a cutoff -- see
     * [RadioVoice.staticLevel]'s doc comment for why real NLOS VHF reception isn't clean even when
     * it's not silent). [power] scales the nominal range *before* the elevation bonus is added
     * (see [RadioVoice.powerRangeMultiplier]) -- a broadcast station's height still helps on top
     * of its power, it doesn't replace it.
     */
    private fun effectiveRangeFor(
        level: ServerLevel,
        band: RadioBand,
        power: Double,
        senderPos: Vec3,
        senderEye: Vec3,
        listenerPos: Vec3,
        listenerEye: Vec3,
    ): Pair<Double, Boolean> {
        val powerMultiplier = RadioVoice.powerRangeMultiplier(power)

        if (band.isGroundWaveFamily) {
            val time = level.dayTime % 24000L
            val isNight = time in 13000..23000
            val nominal = RadioVoice.groundWaveFamilyRangeBlocks(band, isNight) * powerMultiplier
            val bonus = RadioVoice.elevationBonusBlocks(nominal, heightAboveTerrain(level, senderPos), heightAboveTerrain(level, listenerPos))
            return (nominal + bonus) to false
        }

        val obstructed = !hasLineOfSight(level, senderEye, listenerEye)
        val nominal = (band.maxRangeBlocks ?: 0.0) * powerMultiplier
        val bonus = RadioVoice.elevationBonusBlocks(nominal, heightAboveTerrain(level, senderPos), heightAboveTerrain(level, listenerPos))
        val withBonus = nominal + bonus
        val range = if (obstructed) withBonus * NLOS_RANGE_FRACTION else withBonus
        return range to obstructed
    }

    /** Prunes anything that hasn't sent a frame in [STALE_TICKS], then finds the strongest *other*
     * currently-transmitting entry on [band]/[frequencyKhz]'s channel as received at
     * [listenerPos]/[listenerEye], if any -- used to decide whether a transmission should be
     * suppressed for this listener (see [RadioVoice.isDrownedOutBy]). */
    private fun strongestCompetitorPower(
        level: ServerLevel,
        band: RadioBand,
        frequencyKhz: Double,
        excludeEntityId: Int,
        nowTick: Long,
        listenerPos: Vec3,
        listenerEye: Vec3,
    ): Double {
        recentTransmissions.entries.removeIf { nowTick - it.value.lastFrameTick > STALE_TICKS }

        var strongest = 0.0
        for ((entityId, tx) in recentTransmissions) {
            if (entityId == excludeEntityId) continue
            if (tx.band != band || abs(tx.frequencyKhz - frequencyKhz) > band.tuningToleranceKhz) continue

            val (range, _) = effectiveRangeFor(level, tx.band, tx.power, tx.position, tx.eye, listenerPos, listenerEye)
            val distance = listenerPos.distanceTo(tx.position)
            if (distance > range) continue

            val received = tx.power * RadioVoice.gain(distance, range)
            if (received > strongest) strongest = received
        }
        return strongest
    }

    /** Shared per-listener fan-out for one transmission -- both a handheld's PTT frame ([relay])
     * and a station member's ([relayFromStation]) end up here once the transmission's own band,
     * frequency, mode, power, and origin position/eye are known; only the origin differs (the
     * sender's live position for a handheld, the station block's fixed position for a station). */
    private fun fanOut(
        level: ServerLevel,
        senderEntityId: Int,
        band: RadioBand,
        frequencyKhz: Double,
        modeName: String,
        power: Double,
        originPos: Vec3,
        originEye: Vec3,
        audioData: ByteArray,
    ) {
        val nowTick = level.gameTime
        val stormNoise = band.isGroundWaveFamily && level.isThundering
        recentTransmissions[senderEntityId] = RecentTransmission(band, frequencyKhz, power, originPos, originEye, nowTick)

        for (listener in level.players()) {
            if (listener.id == senderEntityId) continue

            val tunedIn = RadioActions.hotbarRadios(listener).any { (rxRadio, rxStack) ->
                val rxData = rxRadio.dataOf(rxStack)
                rxData.powered && rxData.slots.any {
                    it.enabled && abs(it.frequencyKhz - frequencyKhz) <= band.tuningToleranceKhz
                }
            }
            if (!tunedIn) continue

            val listenerPos = listener.position()
            val listenerEye = listener.getEyePosition(1f)
            val (effectiveRange, obstructed) = effectiveRangeFor(level, band, power, originPos, originEye, listenerPos, listenerEye)
            val distance = listenerPos.distanceTo(originPos)
            if (distance > effectiveRange) continue

            val ownReceived = power * RadioVoice.gain(distance, effectiveRange)
            val competitorReceived = strongestCompetitorPower(level, band, frequencyKhz, senderEntityId, nowTick, listenerPos, listenerEye)
            if (competitorReceived > 0.0 && RadioVoice.isDrownedOutBy(ownReceived, competitorReceived)) continue

            val payload = RadioAudioRelayPayload(
                senderEntityId, audioData, frequencyKhz, modeName,
                RadioSenderPosition(originPos.x, originPos.y, originPos.z),
                RadioRangeInfo.of(effectiveRange, obstructed, stormNoise),
            )
            PacketDistributor.sendToPlayer(listener, payload)
        }
    }

    fun relay(sender: ServerPlayer, audioData: ByteArray) {
        val level = sender.serverLevel()
        val stationPos = RadioStationRegistry.joinedStationPos(sender)
        if (stationPos != null) {
            relayFromStation(level, sender, stationPos, audioData)
            return
        }

        val (radio, stack) = RadioActions.transmittableHotbarRadio(sender) ?: return
        val data = radio.dataOf(stack)
        val txSlot = data.slots[data.activeSlot]
        val band = RadioBand.fromFrequencyKhz(txSlot.frequencyKhz) ?: return

        fanOut(level, sender.id, band, txSlot.frequencyKhz, txSlot.mode.name, radio.power, sender.position(), sender.getEyePosition(1f), audioData)
    }

    /** A member of a [RadioStationBlockEntity] pressing PTT: propagation originates from the
     * *block's* position, not the speaking player's -- a real broadcast host isn't standing next
     * to the antenna, and signal quality shouldn't depend on where they wander off to after
     * joining (see [RadioStationBlockEntity]'s doc comment). Silently does nothing if the station
     * block is gone (broken, unloaded chunk, etc.) -- [RadioStationRegistry] doesn't get an
     * eager callback for that, so this is where it's actually noticed. */
    private fun relayFromStation(level: ServerLevel, sender: ServerPlayer, stationPos: BlockPos, audioData: ByteArray) {
        val station = level.getBlockEntity(stationPos) as? RadioStationBlockEntity ?: return
        val band = RadioBand.fromFrequencyKhz(station.frequencyKhz) ?: return
        val origin = Vec3.atCenterOf(stationPos)

        fanOut(level, sender.id, band, station.frequencyKhz, station.mode.name, STATION_POWER, origin, origin, audioData)
    }

    /** A non-line-of-sight signal still has some reach in reality, via diffraction and reflection
     * off other terrain -- not a hard zero, just heavily reduced (kept well under the elevation
     * bonus so obstruction always dominates over height at the same nominal range). */
    private const val NLOS_RANGE_FRACTION = 0.15

    /** ~300ms (6 server ticks) of silence before a transmitter is no longer considered "currently
     * talking" for the co-channel check -- comfortably longer than the ~20ms gap between PTT
     * frames, short enough that releasing PTT stops counting against a would-be competitor almost
     * immediately. */
    private const val STALE_TICKS = 6L
}
