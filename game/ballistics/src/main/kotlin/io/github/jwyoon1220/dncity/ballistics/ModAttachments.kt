// AGENT-DONE(claude): shell-ballistics
package io.github.jwyoon1220.dncity.ballistics

import com.mojang.serialization.Codec
import io.github.jwyoon1220.dncity.Dncity
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.function.Supplier

object ModAttachments {
    val REGISTRY: DeferredRegister<AttachmentType<*>> =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Dncity.ID)

    /** The id of the [Shell] a projectile entity is, saved with the entity. Empty = not decided yet. */
    val SHELL_ID: DeferredHolder<AttachmentType<*>, AttachmentType<String>> =
        REGISTRY.register<AttachmentType<String>>(
            "shell_id",
            Supplier { AttachmentType.builder(Supplier { "" }).serialize(Codec.STRING).build() },
        )
}
