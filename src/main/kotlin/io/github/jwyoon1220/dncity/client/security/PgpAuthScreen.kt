package io.github.jwyoon1220.dncity.client.security

import io.github.jwyoon1220.dncity.network.PgpResponsePayload
import io.github.jwyoon1220.dncity.security.PgpCrypto
import io.github.jwyoon1220.dncity.security.PgpPaths
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Blocking login-gate GUI shown during the configuration phase, before the player ever reaches
 * the world -- see [io.github.jwyoon1220.dncity.security.PgpAuthConfigurationTask]. The player
 * types the passphrase to their own local secret key ([PgpPaths.secretKeyFile], exported from
 * Kleopatra ahead of time), which is used to sign [challenge] entirely client-side -- the
 * passphrase itself never leaves this machine, only the resulting signature does
 * ([PgpCrypto.sign]).
 *
 * A wrong passphrase, or a missing/unparsable key file, is caught here and shown inline so the
 * player can just retry. A signature the server doesn't recognize (signed with a key it has no
 * matching registered public key for) instead fails on the server side and disconnects the whole
 * connection -- there's no synchronous way for a configuration task to report an error back into
 * this same screen for a retry, so that failure mode surfaces as a normal disconnect-with-reason
 * screen instead.
 */
class PgpAuthScreen(private val challenge: String, private val context: IPayloadContext) :
    Screen(Component.literal("PGP Login Verification")) {

    private lateinit var passphraseBox: EditBox
    private lateinit var submitButton: Button
    private var statusMessage: String? = null
    private var statusIsError = false

    override fun init() {
        val boxWidth = 220
        val left = (width - boxWidth) / 2
        val top = height / 2 - 10

        passphraseBox = EditBox(font, left, top, boxWidth, 20, Component.literal("passphrase"))
        passphraseBox.setFormatter { text, _ -> FormattedCharSequence.forward("•".repeat(text.length), Style.EMPTY) }
        passphraseBox.setResponder { statusMessage = null }
        addRenderableWidget(passphraseBox)
        setInitialFocus(passphraseBox)

        submitButton = addRenderableWidget(
            Button.builder(Component.literal("Sign in")) { submit() }
                .bounds(left, top + 26, boxWidth, 20)
                .build(),
        )
    }

    private fun submit() {
        if (!PgpPaths.secretKeyFile.isFile) {
            statusMessage = "No secret key found at config/dncity/security/pgp/secret.asc"
            statusIsError = true
            return
        }

        try {
            val signature = PgpCrypto.sign(
                PgpPaths.secretKeyFile.readText(),
                passphraseBox.value.toCharArray(),
                challenge.toByteArray(Charsets.UTF_8),
            )
            context.reply(PgpResponsePayload(signature))
            statusMessage = "Verifying..."
            statusIsError = false
            submitButton.active = false
        } catch (e: Exception) {
            statusMessage = "Signing failed: ${e.message ?: e::class.simpleName}"
            statusIsError = true
        }
        passphraseBox.value = ""
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 40, 0xFFFFFF)
        graphics.drawCenteredString(
            font, Component.literal("Enter your PGP passphrase to continue"), width / 2, height / 2 - 26, 0xA0A0A0,
        )
        statusMessage?.let {
            graphics.drawCenteredString(font, Component.literal(it), width / 2, height / 2 + 52, if (statusIsError) 0xFF5555 else 0xA0A0A0)
        }
    }

    override fun isPauseScreen(): Boolean = false

    // Deliberately no ESC-to-close -- this screen is the login gate itself, not a normal menu,
    // so there's no "cancel" that still lets the player in.
    override fun shouldCloseOnEsc(): Boolean = false
}
