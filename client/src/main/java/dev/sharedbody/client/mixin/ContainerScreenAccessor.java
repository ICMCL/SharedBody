package dev.sharedbody.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos") int sharedbody$left();
    @Accessor("topPos") int sharedbody$top();
    @Accessor("imageWidth") int sharedbody$width();
    @Accessor("imageHeight") int sharedbody$height();
    @Accessor("hoveredSlot") void sharedbody$hovered(Slot slot);
}
