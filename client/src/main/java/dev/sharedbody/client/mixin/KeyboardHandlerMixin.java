package dev.sharedbody.client.mixin;

import dev.sharedbody.client.SharedBodyClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void sharedbody$key(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (SharedBodyClient.inventoryKey(action, event)) {
            ci.cancel();
            return;
        }
        boolean localFunction = event.key() >= InputConstants.KEY_F1 && event.key() <= InputConstants.KEY_F12;
        if (!event.isEscape() && !localFunction && SharedBodyClient.blockBodyInventory()
                && Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void sharedbody$character(long window, CharacterEvent event, CallbackInfo ci) {
        if (SharedBodyClient.blockBodyInventory()
                && Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>) ci.cancel();
    }
}
