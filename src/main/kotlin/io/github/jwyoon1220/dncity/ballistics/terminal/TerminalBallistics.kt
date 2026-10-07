// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import net.neoforged.neoforge.event.AddReloadListenerEvent

/** Entry point of the terminal ballistics data: registers every catalog as a server data reload listener. */
object TerminalBallistics {
    fun onAddReloadListeners(event: AddReloadListenerEvent) {
        event.addListener(ResistancePresetRegistry)
        event.addListener(ArmorMaterialRegistry)
        event.addListener(ProjectileDefinitionRegistry)
    }
}
