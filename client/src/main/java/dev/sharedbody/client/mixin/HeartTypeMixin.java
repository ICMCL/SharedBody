package dev.sharedbody.client.mixin;

import dev.sharedbody.client.SharedHud;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.client.gui.Hud$HeartType")
public abstract class HeartTypeMixin {
    @Redirect(method = "forPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private static boolean sharedbody$effect(Player player, Holder<MobEffect> effect) {
        return SharedHud.effect(player, effect);
    }

    @Redirect(method = "forPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isFullyFrozen()Z"))
    private static boolean sharedbody$frozen(Player player) {
        return SharedHud.frozen(player);
    }
}
