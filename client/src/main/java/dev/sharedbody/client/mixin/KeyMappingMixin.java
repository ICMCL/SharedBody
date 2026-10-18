package dev.sharedbody.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sharedbody.client.SharedBodyClient;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private static void sharedbody$set(InputConstants.Key key, boolean down, CallbackInfo ci) {
        if (SharedBodyClient.captureKey(key, down)) ci.cancel();
    }

    @Inject(method = "click", at = @At("HEAD"), cancellable = true)
    private static void sharedbody$click(InputConstants.Key key, CallbackInfo ci) {
        if (SharedBodyClient.suppressClick(key)) ci.cancel();
    }
}
