package dev.sharedbody.client.mixin;

import dev.sharedbody.client.SharedBodyClient;
import dev.sharedbody.client.SharedInventory;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Shadow private EntityRenderState extractEntity(Entity entity, float partialTick) { throw new AssertionError(); }

    @Inject(method = "extractPlayerState", at = @At("TAIL"))
    private void sharedbody$hands(Camera camera, DeltaTracker delta, float partialTick,
                                  PlayerRenderState state, CallbackInfo ci) {
        var host = SharedBodyClient.visibleBody();
        if (host == null) return;
        if (!(extractEntity(host, partialTick) instanceof AvatarRenderState avatar)) {
            throw new IllegalStateException("SharedBody expected the body's AvatarRenderState");
        }
        state.hasPlayer = true;
        state.avatarRenderState = avatar;
        SharedInventory.extractHands(state.firstPersonHandsAndItems, host, partialTick);
    }
}
