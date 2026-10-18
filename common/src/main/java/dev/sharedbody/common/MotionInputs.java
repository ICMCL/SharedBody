package dev.sharedbody.common;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MotionInputs {
    private record Entry(long sequence, int held, long tick) {}
    private final Map<UUID, Entry> entries = new HashMap<>();

    public boolean accept(UUID id, Motion motion, int controls, long tick) {
        Entry previous = entries.get(id);
        if (previous != null && motion.sequence() <= previous.sequence()) return false;
        entries.put(id, new Entry(motion.sequence(), motion.authorized(controls), tick));
        return true;
    }

    public int held(long tick) {
        int held = 0;
        for (Entry entry : entries.values()) {
            if (tick - entry.tick() <= 5) held |= entry.held();
        }
        return held;
    }

    public void clear() { entries.clear(); }
}
