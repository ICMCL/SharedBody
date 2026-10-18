package dev.sharedbody.common;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Inputs {
    private record Held(int mask, long tick) {}
    private final Map<UUID, Held> held = new HashMap<>();
    private int clicks;
    private float yaw;
    private float pitch;
    private int hotbar = -1;
    private int scroll;

    public void accept(UUID player, Frame raw, int controls, long tick) {
        Frame frame = raw.authorized(controls);
        held.put(player, new Held(frame.held(), tick));
        clicks |= frame.clicks();
        yaw = clamp(yaw + frame.yaw());
        pitch = clamp(pitch + frame.pitch());
        if (frame.hotbar() >= 0) hotbar = frame.hotbar();
        scroll = Math.clamp(scroll + frame.scroll(), -9, 9);
    }

    public Frame drain(long tick) {
        int mask = 0;
        for (var iterator = held.values().iterator(); iterator.hasNext();) {
            Held value = iterator.next();
            if (tick - value.tick() > 5) iterator.remove();
            else mask |= value.mask();
        }
        Frame frame = new Frame(mask, clicks, yaw, pitch, hotbar, scroll);
        clicks = 0;
        yaw = 0;
        pitch = 0;
        hotbar = -1;
        scroll = 0;
        return frame;
    }

    public void clear() {
        held.clear();
        clicks = 0;
        yaw = pitch = 0;
        hotbar = -1;
        scroll = 0;
    }

    private static float clamp(float value) { return Math.clamp(value, -360, 360); }
}
