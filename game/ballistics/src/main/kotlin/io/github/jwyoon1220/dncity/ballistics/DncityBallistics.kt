// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.ballistics

import io.github.jwyoon1220.dncity.ballistics.command.AmmoCommand
import io.github.jwyoon1220.dncity.ballistics.item.BallisticsItems
import io.github.jwyoon1220.dncity.ballistics.network.VehicleHitNetworking
import io.github.jwyoon1220.dncity.ballistics.smallarms.SmallArmsData
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorEffectModels
import io.github.jwyoon1220.dncity.ballistics.terminal.TerminalBallistics
import io.github.jwyoon1220.dncity.ballistics.terminal.ammo.AmmoRegistry
import io.github.jwyoon1220.dncity.ballistics.terminal.ammo.AmmoSolvers
import io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber.FullCaliberApPresetRegistry
import io.github.jwyoon1220.dncity.ballistics.vehicle.VehicleArmorRegistry
import io.github.jwyoon1220.dncity.compat.FirstAidShellBridge
import io.github.jwyoon1220.dncity.compat.SbwRemovedContent
import io.github.jwyoon1220.dncity.compat.SbwVehicleArmorBridge
import io.github.jwyoon1220.dncity.compat.TaczCartridges
import io.github.jwyoon1220.dncity.compat.TaczFirstAidBridge
import io.github.jwyoon1220.dncity.compat.TaczShellKeys
import io.github.jwyoon1220.dncity.compat.TaczSmallArmsBridge
import io.github.jwyoon1220.dncity.compat.TaczVehicleArmorBridge
import io.github.jwyoon1220.dncity.item.component.ModDataComponents
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/**
 * `dncity_ballistics`: shells, terminal/small-arms ballistics, vehicle armor and damage, cartridges and tank rounds, the
 * TACZ / SuperbWarfare / First Aid bridges, and SuperbWarfare's removed sci-fi content. Client-only setup is in
 * [io.github.jwyoon1220.dncity.client.render.BallisticsClientEvents].
 */
@Mod(DncityBallistics.ID)
object DncityBallistics {
    const val ID = "dncity_ballistics"

    init {
        BallisticsItems.REGISTRY.register(MOD_BUS)
        ModDataComponents.REGISTRY.register(MOD_BUS)
        ModAttachments.REGISTRY.register(MOD_BUS)

        NeoForge.EVENT_BUS.addListener(ShellRegistry::onAddReloadListeners)
        NeoForge.EVENT_BUS.addListener(SmallArmsData::onAddReloadListeners)
        NeoForge.EVENT_BUS.addListener(TerminalBallistics::onAddReloadListeners)
        AmmoSolvers.registerBuiltins { FullCaliberApPresetRegistry.asMap() }
        ArmorEffectModels.registerBuiltins()
        NeoForge.EVENT_BUS.addListener(AmmoRegistry::onAddReloadListeners)
        NeoForge.EVENT_BUS.addListener(AmmoCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(VehicleArmorRegistry::onAddReloadListeners)
        MOD_BUS.addListener(VehicleHitNetworking::onRegisterPayloadHandlers)
        // Loaded lazily: these reference TACZ and First Aid classes
        if (ModList.get().isLoaded("tacz")) {
            TaczShellKeys.register()
            TaczSmallArmsBridge.register()
            // One item per round in dncity/cartridges.json, plus the "Ammunition" creative tab
            TaczCartridges.register(MOD_BUS)
        }
        if (ModList.get().isLoaded("firstaid")) {
            FirstAidShellBridge.register()
        }
        if (ModList.get().isLoaded("tacz") && ModList.get().isLoaded("firstaid")) {
            TaczFirstAidBridge.register()
        }
        // Armor penetration on SuperbWarfare vehicles (+ the shooter's hit camera)
        if (ModList.get().isLoaded("superbwarfare")) {
            SbwVehicleArmorBridge.register()
            if (ModList.get().isLoaded("tacz")) TaczVehicleArmorBridge.register()
        }
        // SuperbWarfare's energy / sci-fi weapons: no recipes, loot, creative entries or entities. No SuperbWarfare classes
        // involved, and the loot modifier type must exist for data/neoforge/loot_modifiers either way
        SbwRemovedContent.register(MOD_BUS)
    }
}
