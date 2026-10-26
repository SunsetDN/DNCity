// AGENT-DONE(claude): armor-wear-suppression
package io.github.jwyoon1220.dncity.client.render

import com.mojang.blaze3d.systems.RenderSystem
import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.item.component.ModDataComponents
import net.minecraft.ChatFormatting
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.LayeredDraw
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent

/**
 * Red armor row drawn right above vanilla's armor bar: the ballistic protection still left in the worn body armor
 * ([io.github.jwyoon1220.dncity.item.component.BallisticArmorState]). 10 full icons = a full set of undamaged ballistic armor;
 * each piece counts its [SLOT_POINTS] share times its integrity, so a piece hit `max_hits` times counts nothing. Hidden when no
 * ballistic piece is worn. Registered above [net.neoforged.neoforge.client.gui.VanillaGuiLayers.ARMOR_LEVEL] and stacks on the
 * left HUD column through `Gui.leftHeight`, like vanilla's own rows.
 */
object BallisticArmorBar : LayeredDraw.Layer {
    val ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(Dncity.ID, "ballistic_armor")

    private val FULL = ResourceLocation.withDefaultNamespace("hud/armor_full")
    private val HALF = ResourceLocation.withDefaultNamespace("hud/armor_half")
    private val EMPTY = ResourceLocation.withDefaultNamespace("hud/armor_empty")

    /** Half icons each slot is worth, 20 (10 icons) for a full set, chestplate > leggings > helmet > boots like vanilla (design). */
    private val SLOT_POINTS = mapOf(EquipmentSlot.HEAD to 4, EquipmentSlot.CHEST to 8, EquipmentSlot.LEGS to 6, EquipmentSlot.FEET to 2)

    override fun render(graphics: GuiGraphics, delta: DeltaTracker) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.gameMode?.canHurtPlayer() != true) return
        val player = mc.cameraEntity as? Player ?: return
        var worn = false
        var points = 0f
        SLOT_POINTS.forEach { (slot, weight) ->
            val state = player.getItemBySlot(slot).get(ModDataComponents.BALLISTIC_ARMOR) ?: return@forEach
            worn = true
            points += weight * state.integrity
        }
        if (!worn) return
        // Rounded up: a piece that still stops anything shows at least half an icon
        val value = Mth.ceil(points - 1e-4f)

        val gui = mc.gui
        val x = graphics.guiWidth() / 2 - 91
        val y = graphics.guiHeight() - gui.leftHeight
        RenderSystem.enableBlend()
        graphics.setColor(1f, 0.25f, 0.25f, 1f)
        for (k in 0 until 10) {
            val sprite = when {
                k * 2 + 1 < value -> FULL
                k * 2 + 1 == value -> HALF
                else -> EMPTY
            }
            graphics.blitSprite(sprite, x + k * 8, y, 9, 9)
        }
        graphics.setColor(1f, 1f, 1f, 1f)
        RenderSystem.disableBlend()
        gui.leftHeight += 10
    }

    /** Remaining ballistic protection on the armor piece's tooltip. */
    fun onTooltip(event: ItemTooltipEvent) {
        val state = event.itemStack.get(ModDataComponents.BALLISTIC_ARMOR) ?: return
        event.toolTip.add(
            if (state.spent) Component.translatable("tooltip.dncity.ballistic_armor.spent").withStyle(ChatFormatting.DARK_RED)
            else Component.translatable("tooltip.dncity.ballistic_armor", Mth.ceil(state.integrity * 100f - 1e-3f), state.hits).withStyle(ChatFormatting.RED)
        )
    }
}
