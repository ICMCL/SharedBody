package dev.sharedbody.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class CameraMotionTest {
    private CameraMotion camera(float yaw, float pitch) {
        CameraMotion camera = new CameraMotion();
        camera.reset(new Look(0, yaw, pitch), 0);
        return camera;
    }

    @Test
    void controllerTurnsImmediatelyWithoutTicksOrServerEcho() {
        CameraMotion camera = camera(20, 10);
        camera.turn(12, -5);
        assertEquals(32, camera.yaw());
        assertEquals(5, camera.pitch());
        Look update = camera.poll(0);
        assertEquals(new Look(1, 32, 5), update);
        camera.turn(3, 2);
        assertEquals(35, camera.yaw());
        assertNull(camera.poll(1));
        assertEquals(new Look(2, 35, 7), camera.poll(CameraMotion.SEND_INTERVAL_NANOS));
    }

    @Test
    void remoteCameraMovesBetweenTicksWithoutSnapping() {
        CameraMotion camera = camera(0, 0);
        camera.accept(new Look(1, 90, 45), 0);
        assertEquals(0, camera.yaw());
        camera.advance(5_000_000);
        float first = camera.yaw();
        assertTrue(first > 0 && first < 90);
        camera.advance(10_000_000);
        assertTrue(camera.yaw() > first && camera.yaw() < 90);
        camera.advance(100_000_000);
        assertTrue(Math.abs(90 - camera.yaw()) < 2);
        assertTrue(Math.abs(45 - camera.pitch()) < 1);
    }

    @Test
    void smoothingIsIndependentOfRenderFrameRate() {
        for (int fps : new int[]{30, 60, 144, 240}) {
            CameraMotion camera = camera(0, 0);
            camera.accept(new Look(1, 100, -50), 0);
            long end = 100_000_000;
            for (long time = 1_000_000_000L / fps; time < end; time += 1_000_000_000L / fps) camera.advance(time);
            camera.advance(end);
            assertEquals(100 * (1 - Math.exp(-4)), camera.yaw(), 0.0001, "FPS " + fps);
            assertEquals(-50 * (1 - Math.exp(-4)), camera.pitch(), 0.0001, "FPS " + fps);
        }
    }

    @Test
    void crossingNorthUsesShortestRotationInsteadOfSpinningAround() {
        CameraMotion camera = camera(179, 0);
        camera.accept(new Look(1, -179, 0), 0);
        camera.advance(10_000_000);
        assertTrue(Math.abs(Look.wrap(camera.yaw() - 179)) < 2);
        camera.advance(250_000_000);
        assertEquals(-179, camera.yaw(), 0.001);
        camera.turn(-3, 0);
        assertEquals(178, camera.yaw(), 0.001);
    }

    @Test
    void pitchIsClampedLocallyBeforeTransmission() {
        CameraMotion camera = camera(0, 80);
        camera.turn(0, 100);
        assertEquals(90, camera.pitch());
        camera.turn(0, -1);
        assertEquals(89, camera.pitch());
        camera.turn(0, -500);
        assertEquals(-90, camera.pitch());
    }

    @Test
    void stalePacketsCannotRewindAndMissingPacketsCannotLoseMovement() {
        CameraMotion camera = camera(0, 0);
        assertTrue(camera.accept(new Look(10, 80, 10), 0));
        assertFalse(camera.accept(new Look(9, -50, 0), 5_000_000));
        assertFalse(camera.accept(new Look(10, 0, 0), 5_000_000));
        camera.advance(250_000_000);
        assertEquals(80, camera.yaw(), 0.005);
        assertTrue(camera.accept(new Look(15, 100, 20), 250_000_000));
        camera.advance(500_000_000);
        assertEquals(100, camera.yaw(), 0.005);
    }

    @Test
    void incomingUpdatesAdvanceExistingMotionRatherThanRestartingIt() {
        CameraMotion camera = camera(0, 0);
        camera.accept(new Look(1, 40, 0), 0);
        camera.accept(new Look(2, 80, 0), 10_000_000);
        assertTrue(camera.yaw() > 10 && camera.yaw() < 40);
        float previous = camera.yaw();
        camera.advance(20_000_000);
        assertTrue(camera.yaw() > previous);
    }

    @Test
    void highFpsTrafficIsBoundedAndFinalPositionIsFlushedAfterMouseStops() {
        CameraMotion camera = camera(0, 0);
        int packets = 0;
        Look lastSent = null;
        for (int millisecond = 0; millisecond < 1000; millisecond++) {
            camera.turn(0.1f, 0);
            Look update = camera.poll(millisecond * 1_000_000L);
            if (update != null) {
                packets++;
                lastSent = update;
            }
        }
        assertTrue(packets <= 60, "Packets per second: " + packets);
        assertTrue(packets >= 55);
        Look finalPosition = camera.poll(1_100_000_000);
        assertNotNull(finalPosition);
        assertTrue(finalPosition.sequence() > lastSent.sequence());
        assertEquals(camera.yaw(), finalPosition.yaw());
        assertNull(camera.poll(2_000_000_000));
    }

    @Test
    void stationaryCameraSendsNothingAndSessionResetDiscardsOldState() {
        CameraMotion camera = camera(10, 20);
        assertNull(camera.poll(1_000_000_000));
        camera.turn(30, 10);
        camera.reset(new Look(0, -10, -20), 2_000_000_000);
        assertEquals(-10, camera.yaw());
        assertEquals(-20, camera.pitch());
        assertNull(camera.poll(2_000_000_000));
        camera.clear();
        assertFalse(camera.ready());
        assertNull(camera.poll(3_000_000_000L));
    }

    @Test
    void lookProtocolRoundTripsAndRejectsInvalidRotations() throws IOException {
        Look look = new Look(100, -179.5f, 89);
        byte[] packet = Wire.packet(Wire.LOOK, 42, look::write);
        assertEquals(25, packet.length);
        try (var in = Wire.reader(packet)) {
            assertEquals(Wire.LOOK, in.readUnsignedByte());
            assertEquals(42, in.readLong());
            assertEquals(look, Look.read(in));
            Wire.end(in);
        }
        assertThrows(IllegalArgumentException.class, () -> new Look(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Look(0, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Look(0, 180, 0));
        assertThrows(IllegalArgumentException.class, () -> new Look(0, 0, 91));
        assertThrows(IllegalArgumentException.class, () -> camera(0, 0).turn(Float.NaN, 0));
    }
}
