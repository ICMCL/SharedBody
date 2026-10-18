package dev.sharedbody.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SharedBodyRegressionTest {
    private static final int MOVE = 1 << Control.MOVEMENT.ordinal();
    private static final int JUMP = 1 << Control.JUMP.ordinal();
    private static final int SNEAK = 1 << Control.SNEAK.ordinal();

    @Test
    void motionCannotContainInventoryAttackOrUnlistedKeys() {
        assertThrows(IllegalArgumentException.class, () -> new Motion(0, Key.ATTACK.bit()));
        assertThrows(IllegalArgumentException.class, () -> new Motion(0, Key.INVENTORY.bit()));
        assertThrows(IllegalArgumentException.class, () -> new Motion(-1, 0));
        assertEquals(0, new Motion(0, Motion.MASK).authorized(0));
    }

    @Test
    void legsJumpAndSneakHaveSeparateExclusiveOwners() {
        Motion all = new Motion(1, Motion.MASK);
        assertEquals(Control.MOVEMENT.mask, all.authorized(MOVE));
        assertEquals(Key.JUMP.bit(), all.authorized(JUMP));
        assertEquals(Key.SNEAK.bit(), all.authorized(SNEAK));
    }

    @Test
    void bodyUsesLocalMotionBeforeReceivingItsOwnEcho() {
        assertEquals(Key.FORWARD.bit(), Motion.overlay(0, Key.FORWARD.bit(), MOVE));
        assertEquals(0, Motion.overlay(Key.FORWARD.bit(), 0, MOVE));
        assertEquals(Key.FORWARD.bit() | Key.JUMP.bit(),
                Motion.overlay(Key.JUMP.bit(), Key.FORWARD.bit(), MOVE));
    }

    @Test
    void localMotionDoesNotOverrideOtherOwnersOrAdmitUnownedKeys() {
        assertEquals(Key.FORWARD.bit() | Key.JUMP.bit() | Key.SNEAK.bit(),
                Motion.overlay(Key.FORWARD.bit() | Key.JUMP.bit(), Motion.MASK, SNEAK));
        assertEquals(Key.JUMP.bit(), Motion.overlay(Key.JUMP.bit(), Motion.MASK, 0));
    }

    @Test
    void motionIsAvailableImmediatelyAndReleaseDoesNotWaitForDrain() {
        MotionInputs inputs = new MotionInputs();
        UUID mover = UUID.randomUUID(), jumper = UUID.randomUUID();
        assertTrue(inputs.accept(mover, new Motion(1, Key.FORWARD.bit() | Key.JUMP.bit()), MOVE, 10));
        assertEquals(Key.FORWARD.bit(), inputs.held(10));
        assertTrue(inputs.accept(jumper, new Motion(1, Key.JUMP.bit()), JUMP, 10));
        assertEquals(Key.FORWARD.bit() | Key.JUMP.bit(), inputs.held(10));
        assertTrue(inputs.accept(mover, new Motion(2, 0), MOVE, 10));
        assertEquals(Key.JUMP.bit(), inputs.held(10));
    }

    @Test
    void motionExpiresAfterFiveTicksAndAnOldPacketCannotReactivateIt() {
        MotionInputs inputs = new MotionInputs();
        UUID mover = UUID.randomUUID();
        assertTrue(inputs.accept(mover, new Motion(10, Key.FORWARD.bit()), MOVE, 1));
        assertEquals(Key.FORWARD.bit(), inputs.held(6));
        assertEquals(0, inputs.held(7));
        assertFalse(inputs.accept(mover, new Motion(10, Key.FORWARD.bit()), MOVE, 7));
        assertFalse(inputs.accept(mover, new Motion(9, Key.FORWARD.bit()), MOVE, 7));
        assertEquals(0, inputs.held(7));
        assertTrue(inputs.accept(mover, new Motion(11, Key.BACK.bit()), MOVE, 7));
        assertEquals(Key.BACK.bit(), inputs.held(7));
    }

    @Test
    void dimensionOrSessionResetReleasesAllMotion() {
        MotionInputs inputs = new MotionInputs();
        UUID mover = UUID.randomUUID();
        inputs.accept(mover, new Motion(10, Motion.MASK), MOVE | JUMP | SNEAK, 10);
        inputs.clear();
        assertEquals(0, inputs.held(10));
        assertTrue(inputs.accept(mover, new Motion(1, 0), MOVE, 11));
    }

    @Test
    void motionAndFollowPacketsPreserveSequenceRevisionWorldAndEntity() throws IOException {
        Motion motion = new Motion(123, Key.SPRINT.bit() | Key.JUMP.bit());
        try (var in = Wire.reader(Wire.packet(Wire.MOTION, 42, motion::write))) {
            assertEquals(Wire.MOTION, in.readUnsignedByte());
            assertEquals(42, in.readLong());
            assertEquals(motion, Motion.read(in));
            Wire.end(in);
        }
        Follow follow = new Follow(9, "minecraft:the_nether", 789);
        try (var in = Wire.reader(Wire.packet(Wire.FOLLOW_ACK, 42, follow::write))) {
            assertEquals(Wire.FOLLOW_ACK, in.readUnsignedByte());
            assertEquals(42, in.readLong());
            assertEquals(follow, Follow.read(in));
            Wire.end(in);
        }
    }

    @Test
    void entityIdAloneCannotAttachToTheWrongDimensionOrConfirmAnOldTrip() {
        Follow nether = new Follow(4, "minecraft:the_nether", 123);
        assertFalse(nether.matches("minecraft:overworld", 123));
        assertFalse(nether.matches("minecraft:the_nether", 124));
        assertTrue(nether.matches("minecraft:the_nether", 123));
        assertNotEquals(nether, new Follow(3, "minecraft:the_nether", 123));
        assertNotEquals(nether, new Follow(5, "minecraft:the_nether", 123));
    }

    @Test
    void followRejectsInvalidMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new Follow(-1, "minecraft:overworld", 1));
        assertThrows(IllegalArgumentException.class, () -> new Follow(1, "", 1));
        assertThrows(IllegalArgumentException.class, () -> new Follow(1, "x".repeat(257), 1));
        assertThrows(IllegalArgumentException.class, () -> new Follow(1, "minecraft:overworld", -1));
    }

    @Test
    void closingWithECannotBeUndoneByDelayedSnapshots() {
        ClosedMenus menus = new ClosedMenus();
        assertTrue(menus.accepts(10));
        menus.close(10);
        assertFalse(menus.accepts(10));
        assertFalse(menus.accepts(9));
        assertTrue(menus.accepts(11));
        menus.close(9);
        assertFalse(menus.accepts(10));
        menus.reset();
        assertTrue(menus.accepts(1));
    }

    @Test
    void menuTokensRemainValidBeyondTheOldWrappingBoundary() throws IOException {
        ClosedMenus menus = new ClosedMenus();
        menus.close(100000);
        assertTrue(menus.accepts(100001));
        Wire.ScreenAction action = new Wire.ScreenAction(100001, Wire.CLOSE, 0, 0, 0, 0, false, 0, 0);
        try (var in = Wire.reader(Wire.packet(Wire.SCREEN_ACTION, 1, action::write))) {
            in.readUnsignedByte();
            in.readLong();
            assertEquals(action, Wire.ScreenAction.read(in));
            Wire.end(in);
        }
    }

}
