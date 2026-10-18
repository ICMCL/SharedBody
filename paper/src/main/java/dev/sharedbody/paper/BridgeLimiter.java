package dev.sharedbody.paper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class BridgeLimiter {
    static final int PER_TICK = 40;
    static final int WARNING_INTERVAL = 100;
    enum Decision { ACCEPT, DROP, WARN }

    private static final class Window {
        long tick = -1;
        long nextWarning;
        int count;
    }

    private final Map<UUID, Window> players = new HashMap<>();

    Decision check(UUID player, long tick) {
        Window window = players.computeIfAbsent(player, ignored -> new Window());
        if (window.tick != tick) {
            window.tick = tick;
            window.count = 0;
        }
        if (window.count < PER_TICK) {
            window.count++;
            return Decision.ACCEPT;
        }
        if (tick >= window.nextWarning) {
            window.nextWarning = tick + WARNING_INTERVAL;
            return Decision.WARN;
        }
        return Decision.DROP;
    }

    void remove(UUID player) { players.remove(player); }
}
