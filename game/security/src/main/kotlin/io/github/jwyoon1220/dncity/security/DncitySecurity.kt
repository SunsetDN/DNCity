// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.security

import io.github.jwyoon1220.dncity.security.command.PgpCommand
import io.github.jwyoon1220.dncity.security.network.PgpNetworking
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/** `dncity_security`: PGP challenge-response login (see [PgpAuthManager]); `/pgp` to manage keys. */
@Mod(DncitySecurity.ID)
object DncitySecurity {
    const val ID = "dncity_security"

    init {
        NeoForge.EVENT_BUS.addListener(PgpCommand::onRegisterCommands)
        MOD_BUS.addListener(PgpNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(PgpAuthServerEvents::onRegisterConfigurationTasks)
    }
}
