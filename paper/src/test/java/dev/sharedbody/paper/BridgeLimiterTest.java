package dev.sharedbody.paper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.sharedbody.paper.BridgeLimiter.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

class BridgeLimiterTest {
    private final BridgeLimiter limiter = new BridgeLimiter();
    private final UUID player = UUID.randomUUID();

    private BridgeLimiter.Decision exceed(UUID id, long tick) {
        for (int i = 0; i < BridgeLimiter.PER_TICK; i++) assertEquals(ACCEPT, limiter.check(id, tick));
        return limiter.check(id, tick);
    }

    @Test
    void firstFortyPacketsAreAcceptedAndExcessPacketsAreDroppedNotFatal() {
        assertEquals(WARN, exceed(player, 10));
        for (int i = 0; i < 10000; i++) assertEquals(DROP, limiter.check(player, 10));
        assertEquals(ACCEPT, limiter.check(player, 11));
    }

    @Test
    void warningsAreAtMostOncePerHundredTicksWhileBudgetsResetEveryTick() {
        assertEquals(WARN, exceed(player, 10));
        assertEquals(DROP, exceed(player, 11));
        assertEquals(DROP, exceed(player, 109));
        assertEquals(WARN, exceed(player, 110));
    }

    @Test
    void onePlayersFloodDoesNotConsumeAnotherPlayersBudget() {
        assertEquals(WARN, exceed(player, 1));
        assertEquals(WARN, exceed(UUID.randomUUID(), 1));
    }

    @Test
    void quittingRemovesTheWindowAndWarningCooldownForTheNextConnection() {
        assertEquals(WARN, exceed(player, 1));
        limiter.remove(player);
        assertEquals(WARN, exceed(player, 1));
    }
}
