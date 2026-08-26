package io.github.jwyoon1220.dncity.radio

import io.github.jwyoon1220.dncity.item.RadioItem
import io.github.jwyoon1220.dncity.item.component.ModDataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Server-side mutations for whatever radio the player is holding in their main hand -- shared
 * between the "/radio" command and the [io.github.jwyoon1220.dncity.network] payload handlers
 * that back the tuning screen, so both paths behave identically.
 */
object RadioActions {

    private const val HOTBAR_SIZE = 9

    fun heldRadio(player: ServerPlayer): RadioItem? = player.mainHandItem.item as? RadioItem

    /**
     * Every radio stack in [player]'s hotbar (slots 0-8) -- receiving/transmitting only requires
     * carrying a radio in the hotbar, not holding it in hand (see
     * [io.github.jwyoon1220.dncity.voice.RadioRelay]/[io.github.jwyoon1220.dncity.voice.RadioTransmitter]).
     * Tuning/power still act on whichever one is actually held (see [heldRadio]), since that's
     * the one whose screen is open.
     */
    fun hotbarRadios(player: Player): List<Pair<RadioItem, ItemStack>> =
        (0 until HOTBAR_SIZE).mapNotNull { slot ->
            val stack = player.inventory.items[slot]
            (stack.item as? RadioItem)?.let { it to stack }
        }

    /** The first hotbar radio that's powered on with a valid, enabled active (transmit) slot. */
    fun transmittableHotbarRadio(player: Player): Pair<RadioItem, ItemStack>? =
        hotbarRadios(player).firstOrNull { (radio, stack) ->
            val data = radio.dataOf(stack)
            data.powered && data.slots.getOrNull(data.activeSlot)?.enabled == true
        }

    fun tune(player: ServerPlayer, slot: Int, frequencyKhz: Double, mode: RadioMode): Boolean {
        val radio = heldRadio(player) ?: return false
        if (slot !in 0 until radio.tier) return false
        val band = RadioBand.fromFrequencyKhz(frequencyKhz) ?: return false
        if (!radio.bandGroup.supports(band)) return false

        val stack = player.mainHandItem
        val data = radio.dataOf(stack)
        val slots = data.slots.toMutableList()
        slots[slot] = RadioSlot(frequencyKhz, mode, enabled = true)
        stack.set(ModDataComponents.RADIO_DATA, data.copy(slots = slots))
        return true
    }

    fun setActive(player: ServerPlayer, slot: Int): Boolean {
        val radio = heldRadio(player) ?: return false
        if (slot !in 0 until radio.tier) return false
        val stack = player.mainHandItem
        val data = radio.dataOf(stack)
        stack.set(ModDataComponents.RADIO_DATA, data.copy(activeSlot = slot))
        return true
    }

    fun setPowered(player: ServerPlayer, powered: Boolean): Boolean {
        val radio = heldRadio(player) ?: return false
        val stack = player.mainHandItem
        val data = radio.dataOf(stack)
        stack.set(ModDataComponents.RADIO_DATA, data.copy(powered = powered))
        return true
    }
}
