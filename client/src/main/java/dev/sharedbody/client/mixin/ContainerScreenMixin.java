package dev.sharedbody.client.mixin;

import dev.sharedbody.client.SharedBodyClient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void sharedbody$click(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> ci) {
        if (SharedBodyClient.blockBodyInventory()) ci.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void sharedbody$drag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> ci) {
        if (SharedBodyClient.blockBodyInventory()) ci.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void sharedbody$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> ci) {
        if (SharedBodyClient.blockBodyInventory()) ci.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void sharedbody$key(KeyEvent event, CallbackInfoReturnable<Boolean> ci) {
        if (!event.isEscape() && SharedBodyClient.blockBodyInventory()) ci.setReturnValue(true);
    }
}
