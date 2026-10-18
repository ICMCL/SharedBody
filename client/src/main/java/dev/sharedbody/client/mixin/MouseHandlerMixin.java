package dev.sharedbody.client.mixin;

import dev.sharedbody.client.SharedBodyClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
    private void sharedbody$renderLook(CallbackInfo ci) {
        SharedBodyClient.updateLook();
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void sharedbody$button(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (SharedBodyClient.captureMouseButton(button.button(), action != 0)) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void sharedbody$scroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (SharedBodyClient.captureScroll(vertical)) ci.cancel();
    }
}
