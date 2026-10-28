package io.github.jwyoon1220.dncity.radio

import io.github.jwyoon1220.dncity.item.RadioItem
import io.github.jwyoon1220.dncity.item.RadioComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Server-side mutations for the radio the player is holding -- shared between the "/radio" command
 * (main hand) and the payload handlers behind the tuning screen (the hand the screen was opened
 * with, which may be the offhand), so both paths behave identically.
 */
object RadioActions {

    private const val HOTBAR_SIZE = 9

    fun heldRadio(player: ServerPlayer, hand: InteractionHand = InteractionHand.MAIN_HAND): RadioItem? =
        player.getItemInHand(hand).item as? RadioItem

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

    /** The first hotbar radio that's [RadioItem.canTransmit], powered on, and has a valid,
     * enabled active (transmit) slot. */
    fun transmittableHotbarRadio(player: Player): Pair<RadioItem, ItemStack>? =
        hotbarRadios(player).firstOrNull { (radio, stack) ->
            val data = radio.dataOf(stack)
            radio.canTransmit && data.powered && data.slots.getOrNull(data.activeSlot)?.enabled == true
        }

    fun tune(
        player: ServerPlayer,
        slot: Int,
        frequencyKhz: Double,
        mode: RadioMode,
        hand: InteractionHand = InteractionHand.MAIN_HAND,
    ): Boolean {
        val radio = heldRadio(player, hand) ?: return false
        if (RadioTuning.problem(radio, slot, frequencyKhz, mode) != null) return false

        val stack = player.getItemInHand(hand)
        val data = radio.dataOf(stack)
        val slots = data.slots.toMutableList()
        slots[slot] = RadioSlot(frequencyKhz, mode, enabled = true)
        stack.set(RadioComponents.RADIO_DATA, data.copy(slots = slots))
        return true
    }

    fun setActive(player: ServerPlayer, slot: Int, hand: InteractionHand = InteractionHand.MAIN_HAND): Boolean {
        val radio = heldRadio(player, hand) ?: return false
        if (slot !in 0 until radio.tier) return false
        val stack = player.getItemInHand(hand)
        val data = radio.dataOf(stack)
        stack.set(RadioComponents.RADIO_DATA, data.copy(activeSlot = slot))
        return true
    }

    fun setPowered(player: ServerPlayer, powered: Boolean, hand: InteractionHand = InteractionHand.MAIN_HAND): Boolean {
        val radio = heldRadio(player, hand) ?: return false
        val stack = player.getItemInHand(hand)
        val data = radio.dataOf(stack)
        stack.set(RadioComponents.RADIO_DATA, data.copy(powered = powered))
        return true
    }
}
