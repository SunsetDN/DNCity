package io.github.jwyoon1220.dncity.client.window

/**
 * Per-overlay visibility rules, evaluated each client tick by [WindowOverlayManager] against
 * `Minecraft.getInstance().screen`'s type. In-memory only, passed at creation time -- this mod
 * has no persisted config system (see `VoiceSettingsScreen`'s own documented precedent), so this
 * is deliberately not a `ModConfigSpec`/toml-backed setting.
 */
data class OverlayOptions(
    /** Hide while the ESC/pause menu ([net.minecraft.client.gui.screens.PauseScreen]) is open. */
    val hideOnPauseMenu: Boolean = true,
    /** Hide while the inventory ([net.minecraft.client.gui.screens.inventory.InventoryScreen]) is open. */
    val hideOnInventoryScreen: Boolean = true,
    /** Hide while any other screen (chat, a mod GUI, etc.) is open. */
    val hideOnAnyOtherScreen: Boolean = false,
)
