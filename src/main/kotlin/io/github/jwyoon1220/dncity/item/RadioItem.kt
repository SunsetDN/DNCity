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
 */
class RadioItem(val bandGroup: RadioBandGroup, val tier: Int, properties: Properties) : Item(properties) {

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
    }

    companion object {
        /** Set once from client setup; left null on a dedicated server. */
        var screenOpener: ((RadioItem, ItemStack, InteractionHand) -> Unit)? = null
    }
}
