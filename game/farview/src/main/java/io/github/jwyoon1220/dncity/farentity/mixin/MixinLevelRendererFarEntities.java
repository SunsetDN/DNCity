// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.farentity.mixin;

import io.github.jwyoon1220.dncity.client.farentity.FarEntityClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the far-player proxies ({@link FarEntityClient}) at the end of the level's entity loop, right before the loop's
 * {@code endLastBatch()} (the first call in {@code renderLevel}, 1.21.1), into the same buffer source vanilla entities
 * use -- so they are flushed with the same entity render types and, under Iris, shaded by the same gbuffer programs.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererFarEntities {
    @Shadow @Final private RenderBuffers renderBuffers;

    @Inject(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V", ordinal = 0)
    )
    private void dncity$renderFarEntities(
        DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
        Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci
    ) {
        // players are never tick-frozen, so vanilla draws them with the running partial tick
        FarEntityClient.INSTANCE.render(camera, frustumMatrix, projectionMatrix, deltaTracker.getGameTimeDeltaPartialTick(true), this.renderBuffers.bufferSource());
    }
}
