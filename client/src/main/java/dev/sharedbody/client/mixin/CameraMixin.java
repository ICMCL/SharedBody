package dev.sharedbody.client.mixin;

import dev.sharedbody.client.SharedBodyClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float sharedbody$yaw(Entity entity, float partialTick) {
        return SharedBodyClient.cameraYaw(entity, partialTick);
    }

    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float sharedbody$pitch(Entity entity, float partialTick) {
        return SharedBodyClient.cameraPitch(entity, partialTick);
    }
}
