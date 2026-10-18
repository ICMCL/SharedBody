package dev.sharedbody.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ControlsTest {
    @Test
    void cachedAuthorizationMatchesEveryControlCombinationAndIgnoresUnknownBits() {
        Frame raw = new Frame(Key.ALL, Key.ALL, 20, -10, 8, -1);
        for (int assigned = 0; assigned <= Control.ALL; assigned++) {
            int expected = 0;
            for (Control control : Control.values()) {
                if ((assigned & control.bit()) != 0) expected |= control.mask;
            }
            assertEquals(expected, Control.keyMask(assigned));
            assertEquals(expected, Control.keyMask(assigned | ~Control.ALL));
            Frame filtered = raw.authorized(assigned);
            assertEquals(expected | Key.PICK.bit(), filtered.held());
            assertEquals(filtered.held(), filtered.clicks());
            assertEquals((assigned & Control.LOOK.bit()) != 0 ? 20 : 0, filtered.yaw());
            assertEquals((assigned & Control.LOOK.bit()) != 0 ? -10 : 0, filtered.pitch());
            assertEquals((assigned & Control.INVENTORY.bit()) != 0 ? 8 : -1, filtered.hotbar());
            assertEquals((assigned & Control.INVENTORY.bit()) != 0 ? -1 : 0, filtered.scroll());
        }
    }

    @Test
    void allocationFreeMotionAuthorizationAndOverlayMatchAllHeldCombinations() {
        for (int assigned = 0; assigned <= Control.ALL; assigned++) {
            int owned = 0;
            for (Control control : Control.values()) {
                if ((assigned & control.bit()) != 0) owned |= control.mask & Motion.MASK;
            }
            for (int held = 0; held <= Motion.MASK; held++) {
                assertEquals(held & owned, new Motion(0, held).authorized(assigned));
                for (int remote : new int[]{0, Motion.MASK, held, held ^ Motion.MASK}) {
                    assertEquals((remote & ~owned) | (held & owned), Motion.overlay(remote, held, assigned));
                }
            }
        }
    }

    @Test
    void singlePassMergingExpiresOnlyStaleParticipantsAndPreservesOneShotActions() {
        UUID attacker = UUID.randomUUID(), user = UUID.randomUUID();
        Inputs inputs = new Inputs();
        MotionInputs motion = new MotionInputs();
        inputs.accept(attacker, new Frame(Key.ATTACK.bit(), Key.ATTACK.bit(), 0, 0, -1, 0), Control.ALL, 0);
        inputs.accept(user, new Frame(Key.USE.bit(), Key.USE.bit(), 0, 0, -1, 0), Control.ALL, 5);
        motion.accept(attacker, new Motion(1, Key.FORWARD.bit()), Control.ALL, 0);
        motion.accept(user, new Motion(1, Key.JUMP.bit()), Control.ALL, 5);
        assertEquals(Key.ATTACK.bit() | Key.USE.bit(), inputs.drain(5).held());
        Frame remaining = inputs.drain(6);
        assertEquals(Key.USE.bit(), remaining.held());
        assertEquals(0, remaining.clicks());
        assertEquals(Key.FORWARD.bit() | Key.JUMP.bit(), motion.held(5));
        assertEquals(Key.JUMP.bit(), motion.held(6));
        assertEquals(Frame.EMPTY, inputs.drain(11));
        assertEquals(0, motion.held(11));
        assertFalse(motion.accept(attacker, new Motion(1, Key.FORWARD.bit()), Control.ALL, 11));
    }

    @Test
    void nineIndependentExclusiveGroupsLeavePickUnrestricted() {
        assertEquals(9, Control.values().length);
        int seen = Key.PICK.bit();
        for (Control control : Control.values()) {
            assertEquals(0, seen & control.mask, control.name());
            seen |= control.mask;
        }
        assertEquals(Key.ALL, seen);
        assertEquals(Key.PICK.bit(), new Frame(Key.ALL, Key.ALL, 1, 2, 4, 1).authorized(0).held());
    }

    @Test
    void authorizationFiltersEverySurface() {
        Frame raw = new Frame(Key.ALL, Key.ALL, 20, 10, 3, -1);
        for (Control control : Control.values()) {
            Frame filtered = raw.authorized(control.bit());
            assertEquals(control.mask | Key.PICK.bit(), filtered.held());
            assertEquals(filtered.held(), filtered.clicks());
            assertEquals(control == Control.LOOK ? 20 : 0, filtered.yaw());
            assertEquals(control == Control.LOOK ? 10 : 0, filtered.pitch());
            assertEquals(control == Control.INVENTORY ? 3 : -1, filtered.hotbar());
            assertEquals(control == Control.INVENTORY ? -1 : 0, filtered.scroll());
        }
    }

    @Test
    void ownersCanCombineMovingJumpingSneakingAndLookingWithoutStealing() {
        Inputs inputs = new Inputs();
        inputs.accept(UUID.randomUUID(), new Frame(Key.FORWARD.bit() | Key.JUMP.bit(), 0, 99, 0, 8, 0),
                Control.MOVEMENT.bit(), 100);
        inputs.accept(UUID.randomUUID(), new Frame(Key.JUMP.bit(), 0, 0, 0, -1, 0), Control.JUMP.bit(), 100);
        inputs.accept(UUID.randomUUID(), new Frame(Key.SNEAK.bit(), 0, 0, 0, -1, 0), Control.SNEAK.bit(), 100);
        inputs.accept(UUID.randomUUID(), new Frame(0, 0, 6, -3, -1, 0), Control.LOOK.bit(), 100);
        Frame merged = inputs.drain(100);
        assertEquals(Key.FORWARD.bit() | Key.JUMP.bit() | Key.SNEAK.bit(), merged.held());
        assertEquals(6, merged.yaw());
        assertEquals(-3, merged.pitch());
        assertEquals(-1, merged.hotbar());
    }

    @Test
    void heldInputsExpireAndActionsOnlyFireOnce() {
        Inputs inputs = new Inputs();
        UUID id = UUID.randomUUID();
        inputs.accept(id, new Frame(Key.USE.bit(), Key.SWAP.bit(), 2, 1, 4, 1), Control.ALL, 0);
        Frame initial = inputs.drain(0);
        assertEquals(Key.USE.bit(), initial.held());
        assertEquals(Key.SWAP.bit(), initial.clicks());
        Frame next = inputs.drain(1);
        assertEquals(Key.USE.bit(), next.held());
        assertEquals(0, next.clicks());
        assertEquals(0, next.yaw());
        assertEquals(-1, next.hotbar());
        assertEquals(0, next.scroll());
        assertEquals(0, inputs.drain(6).held());
        inputs.accept(id, initial, Control.ALL, 7);
        inputs.clear();
        assertEquals(Frame.EMPTY, inputs.drain(7));
    }

    @Test
    void payloadMatchesRawPaperPluginMessageWithoutAnExtraLengthPrefix() throws IOException {
        Frame frame = new Frame(3, 1, -2.5f, 4.5f, 8, -1);
        byte[] bytes = Wire.packet(Wire.INPUT, 123, out -> Wire.writeFrame(out, frame));
        assertEquals(27, bytes.length);
        assertEquals(Wire.INPUT, bytes[0]);
        try (var in = Wire.reader(bytes)) {
            assertEquals(Wire.INPUT, in.readUnsignedByte());
            assertEquals(123, in.readLong());
            assertEquals(frame, Wire.readFrame(in));
            Wire.end(in);
        }
    }

    @Test
    void remoteContainerActionsRoundTripIncludingChineseCharacters() throws IOException {
        Wire.ScreenAction action = new Wire.ScreenAction(4, Wire.CHARACTER, 0, 0, '中', 0, false, 0, 0);
        byte[] bytes = Wire.packet(Wire.SCREEN_ACTION, 10, action::write);
        try (var in = Wire.reader(bytes)) {
            in.readUnsignedByte(); in.readLong();
            assertEquals(action, Wire.ScreenAction.read(in));
            Wire.end(in);
        }
    }

    @Test
    void keyboardScancodeAndShortcutKeycodeRemainIndependent() throws IOException {
        Wire.ScreenAction action = new Wire.ScreenAction(1, Wire.KEY_PRESS, 50, 60, 4, 192, false, 0, 0, 97);
        byte[] bytes = Wire.packet(Wire.SCREEN_ACTION, 10, action::write);
        try (var in = Wire.reader(bytes)) {
            in.readUnsignedByte(); in.readLong();
            assertEquals(action, Wire.ScreenAction.read(in));
            Wire.end(in);
        }
    }

    @Test
    void invalidValuesAndOversizedPacketsAreExplicitlyRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Frame(0, 0, Float.NaN, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Frame(1 << 20, 0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Frame(0, 0, 0, 0, 9, 0));
        assertThrows(IllegalArgumentException.class, () -> new Frame(0, 0, 0, 0, -1, 10));
        assertThrows(IOException.class, () -> Wire.reader(new byte[8]));
        assertThrows(IOException.class, () -> Wire.reader(new byte[Wire.MAX_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> Wire.packet(Wire.SCREEN, 1, out -> out.write(new byte[Wire.MAX_BYTES])));
        assertThrows(IllegalArgumentException.class, () -> new Wire.ScreenAction(0, Wire.DRAG, Double.NaN, 0, 0, 0, false, 0, 0));
    }

    @Test
    void sustainedUseCanBeReleasedWithoutNewClicks() {
        UUID id = UUID.randomUUID();
        Inputs inputs = new Inputs();
        inputs.accept(id, new Frame(Key.USE.bit(), Key.USE.bit(), 0, 0, -1, 0), Control.USE.bit(), 0);
        assertEquals(Key.USE.bit(), inputs.drain(0).held());
        inputs.accept(id, Frame.EMPTY, Control.USE.bit(), 1);
        assertEquals(Frame.EMPTY, inputs.drain(1));
    }
}
