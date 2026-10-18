package dev.sharedbody.client;

import dev.sharedbody.common.Wire;
import dev.sharedbody.common.ClosedMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;

final class RemoteInventoryScreen extends Screen {
    private static final ClosedMenus CLOSED = new ClosedMenus();
    private MenuSnapshot snapshot;
    private final boolean readOnly;
    private int left;
    private int top;
    private int mouseX;
    private int mouseY;

    private RemoteInventoryScreen(MenuSnapshot snapshot) {
        this(snapshot, false);
    }

    private RemoteInventoryScreen(MenuSnapshot snapshot, boolean readOnly) {
        super(Component.literal("共享背包 · " + snapshot.title()));
        this.snapshot = snapshot;
        this.readOnly = readOnly;
    }

    static void reset() {
        CLOSED.reset();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof RemoteInventoryScreen) mc.gui.setScreen(null);
    }

    static void openReadOnly(MenuSnapshot snapshot) {
        Minecraft.getInstance().gui.setScreen(new RemoteInventoryScreen(snapshot, true));
    }

    static void updateReadOnly(MenuSnapshot snapshot) {
        if (Minecraft.getInstance().gui.screen() instanceof RemoteInventoryScreen screen && screen.readOnly) {
            screen.snapshot = snapshot;
        }
    }

    static void accept(byte[] bytes) throws IOException {
        MenuSnapshot snapshot = MenuSnapshot.decode(bytes);
        Minecraft mc = Minecraft.getInstance();
        if (snapshot == null) {
            if (mc.gui.screen() instanceof RemoteInventoryScreen screen && !screen.readOnly) mc.gui.setScreen(null);
        } else if (!CLOSED.accepts(snapshot.id())) {
            return;
        } else if (mc.gui.screen() instanceof RemoteInventoryScreen screen && screen.snapshot.id() == snapshot.id()) {
            screen.snapshot = snapshot;
            screen.init();
        } else if (mc.gui.screen() == null || mc.gui.screen() instanceof RemoteInventoryScreen) {
            mc.gui.setScreen(new RemoteInventoryScreen(snapshot));
        }
    }

    @Override
    protected void init() {
        left = (width - snapshot.width()) / 2;
        top = (height - snapshot.height()) / 2;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        graphics.fill(left - 8, top - 20, left + snapshot.width() + 8, top + snapshot.height() + 8, 0xff333333);
        graphics.text(font, getTitle(), left, top - 14, 0xffffffff);
        for (MenuSnapshot.Slot slot : snapshot.slots()) {
            if (!slot.active()) continue;
            int x = left + slot.x(), y = top + slot.y();
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xff777777);
            graphics.item(slot.stack(), x, y);
            graphics.itemDecorations(font, slot.stack(), x, y);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                graphics.fill(x, y, x + 16, y + 16, 0x60ffffff);
                graphics.setTooltipForNextFrame(font, slot.stack(), mouseX, mouseY);
            }
        }
        for (MenuSnapshot.Widget widget : snapshot.widgets()) {
            int x = left + widget.x(), y = top + widget.y();
            graphics.fill(x, y, x + widget.width(), y + widget.height(), widget.active() ? 0xff555555 : 0xff222222);
            graphics.text(font, widget.label().isEmpty() ? "…" : widget.label(), x + 2, y + 3, 0xffffffff);
        }
        if (!snapshot.carried().isEmpty()) {
            graphics.item(snapshot.carried(), mouseX - 8, mouseY - 8);
            graphics.itemDecorations(font, snapshot.carried(), mouseX - 8, mouseY - 8);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void mouse(int operation, MouseButtonEvent event, boolean doubleClick, double dx, double dy) {
        if (readOnly) return;
        SharedBodyClient.screenAction(new Wire.ScreenAction(snapshot.id(), operation,
                event.x() - left, event.y() - top, event.button(), event.modifiers(), doubleClick, dx, dy));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        mouse(Wire.PRESS, event, doubleClick, 0, 0);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        mouse(Wire.RELEASE, event, false, 0, 0);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        mouse(Wire.DRAG, event, false, dx, dy);
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (readOnly) return true;
        SharedBodyClient.screenAction(new Wire.ScreenAction(snapshot.id(), Wire.SCROLL,
                x - left, y - top, 0, 0, false, horizontal, vertical));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || minecraft.options.keyInventory.matches(event)) {
            onClose();
        } else if (!readOnly) {
            SharedBodyClient.screenAction(new Wire.ScreenAction(snapshot.id(), Wire.KEY_PRESS,
                    mouseX - left, mouseY - top, event.key(), event.modifiers(), false, 0, 0, event.keycode()));
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (readOnly) return true;
        SharedBodyClient.screenAction(new Wire.ScreenAction(snapshot.id(), Wire.CHARACTER,
                0, 0, event.codepoint(), 0, false, 0, 0));
        return true;
    }

    @Override
    public void onClose() {
        if (!readOnly) {
            CLOSED.close(snapshot.id());
            SharedBodyClient.screenAction(new Wire.ScreenAction(snapshot.id(), Wire.CLOSE, 0, 0, 0, 0, false, 0, 0));
        }
        minecraft.gui.setScreen(null);
    }

    @Override public boolean isPauseScreen() { return false; }
}
