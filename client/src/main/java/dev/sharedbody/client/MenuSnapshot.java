package dev.sharedbody.client;

import dev.sharedbody.client.mixin.ContainerScreenAccessor;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

record MenuSnapshot(int id, int width, int height, String title, List<Slot> slots,
                    ItemStack carried, List<Widget> widgets) {
    record Slot(int x, int y, boolean active, ItemStack stack) {}
    record Widget(int x, int y, int width, int height, boolean active, String label) {}

    static final class Encoder {
        private WeakReference<Object> context;
        private byte[] previous;
        private byte[] compressed;

        byte[] encode(Object context, byte[] raw) throws IOException {
            if (this.context == null || this.context.get() != context || !Arrays.equals(previous, raw)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
                    out.write(raw);
                }
                compressed = bytes.toByteArray();
                previous = raw;
                this.context = new WeakReference<>(context);
            }
            return compressed;
        }

        void clear() {
            context = null;
            previous = compressed = null;
        }
    }

    static byte[] capture(Minecraft mc, int token, Encoder encoder) throws IOException {
        return capture(mc, token, false, encoder);
    }

    static byte[] captureInventory(Minecraft mc, Encoder encoder) throws IOException {
        return capture(mc, 0, true, encoder);
    }

    private static byte[] capture(Minecraft mc, int token, boolean inventory, Encoder encoder) throws IOException {
        if (mc.level == null) throw new IOException("No client world");
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), mc.level.registryAccess());
        try {
            AbstractContainerScreen<?> screen = mc.gui.screen() instanceof AbstractContainerScreen<?> current ? current : null;
            if (!inventory && screen == null) {
                buf.writeBoolean(false);
            } else {
                if (inventory && mc.player == null) throw new IOException("No inventory player");
                ContainerScreenAccessor geometry = screen == null ? null : (ContainerScreenAccessor) screen;
                AbstractContainerMenu menu = inventory ? mc.player.inventoryMenu : screen.getMenu();
                buf.writeBoolean(true);
                buf.writeInt(token);
                buf.writeInt(inventory ? 176 : geometry.sharedbody$width());
                buf.writeInt(inventory ? 166 : geometry.sharedbody$height());
                buf.writeUtf(inventory ? "共享物品栏（只读）" : screen.getTitle().getString(), 1024);
                buf.writeInt(menu.slots.size());
                for (var slot : menu.slots) {
                    buf.writeInt(slot.x);
                    buf.writeInt(slot.y);
                    buf.writeBoolean(slot.isActive());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, slot.getItem());
                }
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, inventory ? ItemStack.EMPTY : menu.getCarried());
                List<AbstractWidget> widgets = inventory ? List.of() : screen.children().stream()
                        .filter(child -> child instanceof AbstractWidget widget && widget.visible)
                        .map(child -> (AbstractWidget) child).toList();
                buf.writeInt(widgets.size());
                for (AbstractWidget widget : widgets) {
                    buf.writeInt(widget.getX() - geometry.sharedbody$left());
                    buf.writeInt(widget.getY() - geometry.sharedbody$top());
                    buf.writeInt(widget.getWidth()); buf.writeInt(widget.getHeight());
                    buf.writeBoolean(widget.active);
                    String label = widget instanceof EditBox edit ? edit.getValue() : widget.getMessage().getString();
                    buf.writeUtf(label, 1024);
                }
            }
            if (buf.readableBytes() > 1_000_000) throw new IOException("Inventory exceeds 1 MB uncompressed");
            byte[] raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
            return encoder.encode(mc.level, raw);
        } finally {
            buf.release();
        }
    }

    static MenuSnapshot decode(byte[] compressed) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) throw new IOException("No client world");
        byte[] raw;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            raw = in.readNBytes(1_000_001);
            if (raw.length > 1_000_000) throw new IOException("Oversized screen snapshot");
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(raw), mc.level.registryAccess());
        try {
            if (!buf.readBoolean()) {
                if (buf.isReadable()) throw new IOException("Trailing closed-screen data");
                return null;
            }
            int id = buf.readInt(), width = buf.readInt(), height = buf.readInt();
            if (id < 0 || width < 1 || height < 1 || width > 1024 || height > 1024) throw new IOException("Invalid screen dimensions");
            String title = buf.readUtf(1024);
            int count = buf.readInt();
            if (count < 0 || count > 256) throw new IOException("Invalid slot count");
            List<Slot> slots = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                slots.add(new Slot(buf.readInt(), buf.readInt(), buf.readBoolean(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)));
            }
            ItemStack carried = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            count = buf.readInt();
            if (count < 0 || count > 256) throw new IOException("Invalid widget count");
            List<Widget> widgets = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                widgets.add(new Widget(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                        buf.readBoolean(), buf.readUtf(1024)));
            }
            if (buf.isReadable()) throw new IOException("Trailing screen data");
            return new MenuSnapshot(id, width, height, title, slots, carried, widgets);
        } finally {
            buf.release();
        }
    }
}
