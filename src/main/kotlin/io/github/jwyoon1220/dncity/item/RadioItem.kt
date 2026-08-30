package io.github.jwyoon1220.dncity.item

import io.github.jwyoon1220.dncity.item.component.ModDataComponents
import io.github.jwyoon1220.dncity.radio.RadioBandGroup
import io.github.jwyoon1220.dncity.radio.RadioData
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/**
 * A tiered, band-restricted radio: [bandGroup] is which real-world band family this physical
 * radio can actually be tuned to (a HF/MF set can't be tuned into VHF/UHF and vice versa --
 * enforced server-side in [io.github.jwyoon1220.dncity.radio.RadioActions.tune]), and [tier] is
 * how many frequency slots it can hold and *receive* on simultaneously (only one slot --
 * [RadioData.activeSlot] -- transmits at a time). Right-click opens the graphical tuning screen
 * (client-only, wired up via [screenOpener] from client setup so this common-code class never
 * references any client class directly).
 *
 * [power] is this radio's relative transmit power (`1.0` for an ordinary handheld) -- consumed by
 * [io.github.jwyoon1220.dncity.radio.RadioVoice.powerRangeMultiplier] to scale its effective range
 * (real range scales with the *square root* of power) and by
 * [io.github.jwyoon1220.dncity.voice.RadioRelay]'s co-channel interference check, where a
 * transmitter with much higher received power at a given listener drowns out a weaker one on the
 * same frequency instead of just mixing with it -- see [RadioVoice.isDrownedOutBy]. A dedicated
 * broadcast station (see [RadioBandGroup.BROADCAST]) is expected to use a much higher [power] than
 * a handheld.
 *
 * [canTransmit] gates whether this item is ever considered by
 * [io.github.jwyoon1220.dncity.radio.RadioActions.transmittableHotbarRadio] -- `false` for a
 * receive-only set (see [RadioBandGroup.SCANNER]) even if it's otherwise powered on with an
 * enabled active slot, same as a real scanner/broadcast receiver has no transmitter at all.
 */
class RadioItem(
    val bandGroup: RadioBandGroup,
    val tier: Int,
    val power: Double = 1.0,
    val canTransmit: Boolean = true,
    properties: Properties,
) : Item(properties) {

    fun dataOf(stack: ItemStack): RadioData = stack.getOrDefault(ModDataComponents.RADIO_DATA, RadioData.default(tier))

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) {
            screenOpener?.invoke(this, stack, hand)
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        super.appendHoverText(stack, context, tooltip, flag)
        tooltip.add(Component.translatable(bandGroup.translationKey).withStyle(ChatFormatting.GRAY))
        if (!canTransmit) {
            tooltip.add(Component.translatable("radio.dncity.receive_only").withStyle(ChatFormatting.DARK_GRAY))
        } else if (power > 1.0) {
            tooltip.add(Component.translatable("radio.dncity.high_power").withStyle(ChatFormatting.DARK_GRAY))
        }
    }

    companion object {
        /** Set once from client setup; left null on a dedicated server. */
        var screenOpener: ((RadioItem, ItemStack, InteractionHand) -> Unit)? = null
    }
}
