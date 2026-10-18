package dev.sharedbody.common;

public record Frame(int held, int clicks, float yaw, float pitch, int hotbar, int scroll) {
    public static final Frame EMPTY = new Frame(0, 0, 0, 0, -1, 0);

    public Frame {
        if ((held & ~Key.ALL) != 0 || (clicks & ~Key.ALL) != 0
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(yaw) > 360 || Math.abs(pitch) > 360
                || hotbar < -1 || hotbar > 8 || Math.abs(scroll) > 9) {
            throw new IllegalArgumentException("Invalid input frame");
        }
    }

    public Frame authorized(int controls) {
        int allowed = Control.keyMask(controls) | Key.PICK.bit();
        boolean look = (controls & Control.LOOK.bit()) != 0;
        boolean inventory = (controls & Control.INVENTORY.bit()) != 0;
        return new Frame(held & allowed, clicks & allowed, look ? yaw : 0, look ? pitch : 0,
                inventory ? hotbar : -1, inventory ? scroll : 0);
    }
}
