// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.farentity.mixin;

import io.github.jwyoon1220.dncity.client.farentity.EntityRenderDistance;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets people and vehicles render as far as the client has terrain chunks ({@link EntityRenderDistance}); vanilla stops a
 * player (bounding-box size 1) at 64 x view scale blocks. Client-only: only the renderer calls this method.
 */
@Mixin(Entity.class)
public abstract class MixinEntityRenderDistance {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void dncity$renderToViewDistance(double distanceSq, CallbackInfoReturnable<Boolean> cir) {
        if (EntityRenderDistance.rendersWithin((Entity) (Object) this, distanceSq)) {
            cir.setReturnValue(true);
        }
    }
}
