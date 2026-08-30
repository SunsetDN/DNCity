package io.github.jwyoon1220.dncity.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import org.lwjgl.glfw.GLFW

/**
 * Client input bindings. [onRegisterKeyMappings] is wired up from
 * [io.github.jwyoon1220.dncity.client.ClientModEvents], not [io.github.jwyoon1220.dncity.Dncity]
 * itself -- see that class's doc comment for why a client-only class like this one (referencing
 * [KeyMapping]) must never be reachable from a class KotlinForForge's automatic
 * `@EventBusSubscriber` injection reflects over on a dedicated server.
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

    /** Opens/closes the phone screen (see [io.github.jwyoon1220.dncity.client.phone.PhoneController]). Bound to M by default -- configurable in Controls. */
    val PHONE_TOGGLE = KeyMapping(
        "key.dncity.phone_toggle",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_M,
        "key.categories.dncity",
    )

    fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(RADIO_PTT)
        event.register(PHONE_TOGGLE)
    }
}
