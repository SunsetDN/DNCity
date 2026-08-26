package io.github.jwyoon1220.dncity.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import org.lwjgl.glfw.GLFW

/**
 * Client input bindings. Registered unconditionally from [io.github.jwyoon1220.dncity.Dncity]'s
 * init block (like [io.github.jwyoon1220.dncity.network.RadioNetworking]'s listener) --
 * [RegisterKeyMappingsEvent] simply never fires on a dedicated server.
 */
object ModKeyMappings {
    /** Push-to-talk for the radio-voice tier (see [io.github.jwyoon1220.dncity.voice.RadioRelay]).
     * Unbound by default -- left to the player to bind in Controls, since every obvious key
     * (V, R, ...) risks colliding with another installed mod's own bindings (e.g. TACZ's reload). */
    val RADIO_PTT = KeyMapping(
        "key.dncity.radio_ptt",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_UNKNOWN,
        "key.categories.dncity",
    )

    fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(RADIO_PTT)
    }
}
