// AGENT-DONE(claude): cartridge-items
package io.github.jwyoon1220.dncity.compat

import com.tacz.guns.api.TimelessAPI
import com.tacz.guns.api.item.IAmmo
import com.tacz.guns.api.item.IAmmoRound
import com.tacz.guns.api.item.IGun
import io.github.jwyoon1220.dncity.item.cartridge.CartridgeCatalog
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * One round of one caliber (`dncity:cartridge_556x45_m855`). For TACZ it is ammo of the caliber's TACZ ammo id, so every
 * gun of that caliber loads it like TACZ's own ammo; [IAmmoRound] tells the gun which round it is (tracer or not), see
 * TACZ's `LoadedRounds`. Only registered when TACZ is loaded (this class references TACZ types).
 */
class TaczCartridgeItem(val round: CartridgeCatalog.Round, properties: Properties) : Item(properties), IAmmo, IAmmoRound {
    private val ammoId: ResourceLocation = ResourceLocation.parse(round.caliber.taczAmmo)

    override fun getAmmoId(ammo: ItemStack): ResourceLocation = ammoId

    /** The round is the item; its ammo id is fixed. */
    override fun setAmmoId(ammo: ItemStack, ammoId: ResourceLocation?) {}

    override fun isAmmoOfGun(gun: ItemStack, ammo: ItemStack): Boolean {
        val iGun = gun.item as? IGun ?: return false
        return TimelessAPI.getCommonGunIndex(iGun.getGunId(gun)).map { it.gunData.ammoId == ammoId }.orElse(false)
    }

    override fun isTracerRound(ammo: ItemStack): Boolean = round.tracer

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        // The English designation stays visible in every language ("5.56x45 M995 AP" under "5.56mm 철갑탄")
        tooltip += Component.literal(round.designationEn).withStyle(ChatFormatting.GRAY)
    }
}
