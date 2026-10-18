package dev.sharedbody.client;

import dev.sharedbody.common.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.renderer.state.MapRenderState;

import java.io.DataInputStream;
import java.io.IOException;

public final class SharedInventory {
    private static final MenuSnapshot.Encoder ENCODER = new MenuSnapshot.Encoder();
    private static MenuSnapshot inventory;
    private static int selected;
    private static byte[] lastSnapshot;
    private static byte[] lastSent;

    private SharedInventory() {}

    static void clear() {
        inventory = null;
        selected = 0;
        ENCODER.clear();
        lastSnapshot = null;
        lastSent = null;
    }

    static void publish(Minecraft mc, long generation, long tick) throws IOException {
        byte[] snapshot = MenuSnapshot.captureInventory(mc, ENCODER);
        int slot = mc.player.getInventory().getSelectedSlot();
        boolean changed = snapshot != lastSnapshot || slot != selected;
        if (changed) {
            lastSent = Wire.packet(Wire.VISUAL, generation, out -> {
                out.writeUTF(mc.level.dimension().identifier().toString());
                out.writeByte(slot);
                out.write(snapshot);
            });
            lastSnapshot = snapshot;
            selected = slot;
        }
        if (changed || tick % 10 == 0) SharedBodyClient.send(lastSent);
    }

    static void accept(DataInputStream in) throws IOException {
        int slot = in.readUnsignedByte();
        if (slot > 8) throw new IOException("Invalid selected hotbar slot");
        MenuSnapshot snapshot = MenuSnapshot.decode(in.readAllBytes());
        if (snapshot == null || snapshot.id() != 0 || snapshot.slots().size() != 46) {
            throw new IOException("Invalid shared player inventory");
        }
        selected = slot;
        inventory = snapshot;
        RemoteInventoryScreen.updateReadOnly(snapshot);
    }

    static void openReadOnly() {
        Minecraft mc = Minecraft.getInstance();
        if (inventory == null) {
            if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("共享物品栏正在同步，请稍候再按 E。"));
            return;
        }
        RemoteInventoryScreen.openReadOnly(inventory);
    }

    private static ItemStack item(int slot) {
        return inventory == null ? ItemStack.EMPTY : inventory.slots().get(slot).stack();
    }

    public static boolean renderHotbar(GuiGraphicsExtractor graphics) {
        if (SharedBodyClient.visibleBody() == null) return false;
        Minecraft mc = Minecraft.getInstance();
        int left = graphics.guiWidth() / 2 - 90, top = graphics.guiHeight() - 22;
        if (inventory == null) {
            graphics.text(mc.font, "共享物品栏同步中…", 10, 10, 0xffeeeeee);
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            int x = left + slot * 20;
            graphics.fill(x, top, x + 20, top + 20, slot == selected ? 0xffd4ae42 : 0xc0444444);
            graphics.fill(x + 1, top + 1, x + 19, top + 19, 0xdd111111);
            ItemStack stack = item(36 + slot);
            graphics.item(stack, x + 2, top + 2);
            graphics.itemDecorations(mc.font, stack, x + 2, top + 2);
        }
        graphics.fill(left - 26, top, left - 6, top + 20, 0xc0444444);
        graphics.item(item(45), left - 24, top + 2);
        graphics.itemDecorations(mc.font, item(45), left - 24, top + 2);
        return true;
    }

    public static void extractHands(FirstPersonHandsAndItemsRenderState state, AbstractClientPlayer host, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        var swing = host.getCurrentSwing();
        state.attackHand = swing == null ? InteractionHand.MAIN_HAND : swing.hand();
        state.viewXRot = SharedBodyClient.cameraPitch(host, partialTick);
        state.viewYRot = SharedBodyClient.cameraYaw(host, partialTick);
        state.xBob = state.viewXRot;
        state.yBob = state.viewYRot;
        state.isScoping = host.isScoping();
        state.useItemRemainingTicks = host.getUseItemRemainingTicks();
        state.handRenderSelection = FirstPersonHandsAndItemsRenderState.HandRenderSelection.RENDER_BOTH_HANDS;
        state.mainHandItem = inventory == null ? host.getMainHandItem() : item(36 + selected);
        state.offHandItem = inventory == null ? host.getOffhandItem() : item(45);
        if (host.isUsingItem() && (host.getUseItem().is(net.minecraft.world.item.Items.BOW)
                || host.getUseItem().is(net.minecraft.world.item.Items.CROSSBOW))) {
            state.handRenderSelection = host.getUsedItemHand() == InteractionHand.MAIN_HAND
                    ? FirstPersonHandsAndItemsRenderState.HandRenderSelection.RENDER_MAIN_HAND_ONLY
                    : FirstPersonHandsAndItemsRenderState.HandRenderSelection.RENDER_OFF_HAND_ONLY;
        }
        state.mainHandHeight = state.oldMainHandHeight = 1;
        state.offHandHeight = state.oldOffHandHeight = 1;
        state.mainHandUseDuration = state.mainHandItem.getUseDuration(host);
        state.offHandUseDuration = state.offHandItem.getUseDuration(host);
        state.mainHandChargeDuration = CrossbowItem.getChargeDuration(state.mainHandItem, host);
        state.offHandChargeDuration = CrossbowItem.getChargeDuration(state.offHandItem, host);
        state.mainHandSwapScale = mc.getItemModelResolver().swapAnimationScale(state.mainHandItem);
        state.offHandSwapScale = mc.getItemModelResolver().swapAnimationScale(state.offHandItem);
        boolean right = host.getMainArm() == HumanoidArm.RIGHT;
        mc.getItemModelResolver().updateForLiving(state.mainHandRenderState, state.mainHandItem,
                right ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND, host);
        mc.getItemModelResolver().updateForLiving(state.offHandRenderState, state.offHandItem,
                right ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, host);
        state.hasMainHandMapData = map(state.mainHandItem, state.mainHandMapRenderState, host);
        state.hasOffHandMapData = map(state.offHandItem, state.offHandMapRenderState, host);
    }

    private static boolean map(ItemStack stack, MapRenderState state, AbstractClientPlayer host) {
        var id = stack.get(DataComponents.MAP_ID);
        if (id == null) return false;
        var data = MapItem.getSavedData(stack, host.level());
        if (data == null) return false;
        Minecraft.getInstance().getMapRenderer().extractRenderState(id, data, state);
        return true;
    }
}
