package io.github.jwyoon1220.dncity.block

import io.github.jwyoon1220.dncity.radio.RadioMode
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * A "라디오 방송국" (radio station) -- a fixed, high-power transmitter any player can walk up to,
 * open, and join broadcasting through (see [io.github.jwyoon1220.dncity.radio.RadioStationRegistry]
 * for the join/leave bookkeeping and [io.github.jwyoon1220.dncity.voice.RadioRelay.relayFromStation]
 * for how a joined member's PTT audio actually goes out). Unlike a handheld
 * [io.github.jwyoon1220.dncity.item.RadioItem], propagation for a station transmission originates
 * from *this block's position*, not whichever member happens to be talking -- a real broadcast
 * host isn't standing next to the antenna, and their signal quality shouldn't depend on where they
 * wander off to after joining.
 *
 * [broadcasters] is purely informational (for [io.github.jwyoon1220.dncity.client.RadioStationScreen]'s
 * "지금 방송 중" list) -- the actual source of truth for whether a given player's PTT audio routes
 * through this station is [io.github.jwyoon1220.dncity.radio.RadioStationRegistry], kept in sync
 * with this set by every call in this class.
 */
class RadioStationBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.RADIO_STATION, pos, state) {
    var stationName: String = DEFAULT_NAME
        set(value) {
            field = value
            setChanged()
        }

    var frequencyKhz: Double = DEFAULT_FREQUENCY_KHZ
        set(value) {
            field = value
            setChanged()
        }

    var mode: RadioMode = RadioMode.FM
        set(value) {
            field = value
            setChanged()
        }

    private val broadcasters: MutableSet<UUID> = linkedSetOf()

    fun broadcasterIds(): Set<UUID> = broadcasters

    fun addBroadcaster(id: UUID) {
        broadcasters.add(id)
        setChanged()
    }

    fun removeBroadcaster(id: UUID) {
        broadcasters.remove(id)
        setChanged()
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putString(KEY_NAME, stationName)
        tag.putDouble(KEY_FREQUENCY, frequencyKhz)
        tag.putString(KEY_MODE, mode.name)
        val list = ListTag()
        broadcasters.forEach { list.add(StringTag.valueOf(it.toString())) }
        tag.put(KEY_BROADCASTERS, list)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        stationName = tag.getString(KEY_NAME).takeIf { it.isNotEmpty() } ?: DEFAULT_NAME
        if (tag.contains(KEY_FREQUENCY)) frequencyKhz = tag.getDouble(KEY_FREQUENCY)
        mode = RadioMode.entries.firstOrNull { it.name == tag.getString(KEY_MODE) } ?: RadioMode.FM
        broadcasters.clear()
        (tag.get(KEY_BROADCASTERS) as? ListTag)?.forEach { element ->
            runCatching { UUID.fromString(element.asString) }.getOrNull()?.let(broadcasters::add)
        }
    }

    companion object {
        const val DEFAULT_NAME = "Station"

        /** 100.0MHz, a normal-looking FM broadcast frequency -- see [RadioBand.VHF]'s range. */
        const val DEFAULT_FREQUENCY_KHZ = 100000.0

        private const val KEY_NAME = "station_name"
        private const val KEY_FREQUENCY = "frequency_khz"
        private const val KEY_MODE = "mode"
        private const val KEY_BROADCASTERS = "broadcasters"
    }
}
