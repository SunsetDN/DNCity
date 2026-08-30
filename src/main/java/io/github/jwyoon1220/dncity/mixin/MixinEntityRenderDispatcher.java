package io.github.jwyoon1220.dncity.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.jwyoon1220.dncity.client.render.OverlayCullingManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips rendering entities whose world position projects to a point currently covered by a
 * {@link io.github.jwyoon1220.dncity.client.window.WindowOverlay} (see
 * {@link OverlayCullingManager}) -- e.g. the phone-UI-style JCEF overlay
 * ({@code BrowserOverlay}), which is a real, opaque native window sitting on top of whatever
 * Minecraft renders underneath it.
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {

    @Inject(
        method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private <E extends Entity> void dncity$skipCulledEntity(
        E entity,
        double x,
        double y,
        double z,
        float rotationYaw,
        float partialTicks,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        CallbackInfo ci
    ) {
        if (OverlayCullingManager.INSTANCE.shouldCull(entity.getX(), entity.getY(), entity.getZ())) {
            ci.cancel();
        }
    }
}
