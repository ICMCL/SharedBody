package dev.sharedbody.client.mixin;

import dev.sharedbody.client.SharedInventory;
import dev.sharedbody.client.SharedBodyClient;
import dev.sharedbody.client.SharedHud;
import dev.sharedbody.common.Vitals;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Hud.class)
public abstract class HudMixin {
    @Shadow private void extractPlayerHealth(GuiGraphicsExtractor graphics) { throw new AssertionError(); }
    @Shadow private void extractVehicleHealth(GuiGraphicsExtractor graphics) { throw new AssertionError(); }
    @Shadow private Player getCameraPlayer() { throw new AssertionError(); }
    @Shadow private int lastHealth;
    @Shadow private int displayHealth;
    @Shadow private long lastHealthTime;
    @Shadow private long healthBlinkTime;
    @Shadow private int lastBubblePopSoundPlayed;
    @Unique private long sharedbody$revision;

    @Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
    private void sharedbody$hotbar(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        if (sharedbody$revision != SharedHud.revision()) {
            Player player = getCameraPlayer();
            Vitals state = SharedHud.state(player);
            lastHealth = displayHealth = Mth.ceil(state != null ? state.health() : player == null ? 0 : player.getHealth());
            lastHealthTime = Util.getMillis();
            healthBlinkTime = 0;
            lastBubblePopSoundPlayed = 0;
            sharedbody$revision = SharedHud.revision();
        }
        if (!SharedInventory.renderHotbar(graphics)) return;
        Vitals state = SharedHud.state(SharedBodyClient.visibleBody());
        if (state != null) {
            if (state.has(Vitals.SURVIVAL)) extractPlayerHealth(graphics);
            extractVehicleHealth(graphics);
        }
        ci.cancel();
    }

    @Inject(method = "getCameraPlayer", at = @At("HEAD"), cancellable = true)
    private void sharedbody$cameraPlayer(CallbackInfoReturnable<Player> cir) {
        Player host = SharedBodyClient.visibleBody();
        if (host != null) cir.setReturnValue(host);
    }

    @Redirect(method = "extractPlayerHealth", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getHealth()F"))
    private float sharedbody$health(Player player) {
        Vitals state = SharedHud.state(player);
        return state == null ? player.getHealth() : state.health();
    }

    @Redirect(method = "extractPlayerHealth", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private double sharedbody$maxHealth(Player player, Holder<Attribute> attribute) {
        Vitals state = SharedHud.state(player);
        return state == null ? player.getAttributeValue(attribute) : state.maxHealth();
    }

    @Redirect(method = "extractPlayerHealth", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getAbsorptionAmount()F"))
    private float sharedbody$absorption(Player player) {
        Vitals state = SharedHud.state(player);
        return state == null ? player.getAbsorptionAmount() : state.absorption();
    }

    @Redirect(method = "extractPlayerHealth", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/player/Player;damageCooldownTime:I"))
    private int sharedbody$hurt(Player player) {
        Vitals state = SharedHud.state(player);
        return state == null ? player.damageCooldownTime : state.hurt();
    }

    @Redirect(method = "extractArmor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getArmorValue()I"))
    private static int sharedbody$armor(Player player) {
        Vitals state = SharedHud.state(player);
        return state == null ? player.getArmorValue() : state.armor();
    }

    @Redirect(method = "extractFood", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getFoodData()Lnet/minecraft/world/food/FoodData;"))
    private FoodData sharedbody$food(Player player) {
        return SharedHud.food(player);
    }

    @Redirect(method = {"extractFood", "extractPlayerHealth"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean sharedbody$effect(Player player, Holder<MobEffect> effect) {
        return SharedHud.effect(player, effect);
    }

    @Redirect(method = "extractAirBubbles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getAirSupply()I"))
    private int sharedbody$air(Player player) {
        Vitals state = SharedHud.state(player);
        return state == null ? player.getAirSupply() : state.air();
    }

    @Redirect(method = "extractAirBubbles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getMaxAirSupply()I"))
    private int sharedbody$maxAir(Player player) {
        Vitals state = SharedHud.state(player);
        return state == null ? player.getMaxAirSupply() : state.maxAir();
    }

    @Redirect(method = "extractAirBubbles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean sharedbody$underwater(Player player, TagKey<Fluid> fluid) {
        Vitals state = SharedHud.state(player);
        return state == null ? player.isEyeInFluid(fluid) : state.has(Vitals.UNDERWATER);
    }
}
