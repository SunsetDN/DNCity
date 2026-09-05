package io.github.jwyoon1220.dncity.security

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.network.ConfigurationTask
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask
import java.util.function.Consumer

/**
 * Kicks a connection during the configuration phase (no registered PGP channel, or no registered
 * key) via a registered [ICustomConfigurationTask] instead of calling
 * [ServerConfigurationPacketListenerImpl.disconnect] synchronously from inside
 * [net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent]'s handler
 * ([PgpAuthServerEvents]).
 *
 * Why: calling `disconnect()` synchronously from that event handler closes the connection while
 * NeoForge's own core per-connection handshake (channel/mod-list negotiation, driving
 * [ServerConfigurationPacketListenerImpl.hasChannel]'s answer in the first place) can still have a
 * packet in flight back from the client -- confirmed by hand: that packet's send then hits a
 * `ClosedChannelException` on the client thread, which cascades into a NeoForge crash-report bug
 * ("Negative index in crash report handler") and a native JVM crash reading GPU/GL info
 * (`com.mojang.blaze3d.platform.GLX`) well after the render thread has already started tearing
 * down its GL context. NeoForge only starts running a connection's registered configuration tasks
 * once [net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent] has finished firing
 * for every listener, so deferring the kick into a task (same mechanism [PgpAuthConfigurationTask]
 * already uses for the success path) runs it after that in-flight handshake traffic has settled,
 * instead of racing it.
 */
class PgpKickConfigurationTask(
    private val listener: ServerConfigurationPacketListenerImpl,
    private val reason: Component,
) : ICustomConfigurationTask {
    override fun run(sender: Consumer<CustomPacketPayload>) {
        this.listener.disconnect(this.reason)
    }

    override fun type(): ConfigurationTask.Type = TYPE

    companion object {
        val TYPE: ConfigurationTask.Type = ConfigurationTask.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "pgp_kick"))
    }
}
