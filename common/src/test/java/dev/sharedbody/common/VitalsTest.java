package dev.sharedbody.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class VitalsTest {
    private final Vitals sample = new Vitals(18.5f, 40, 4, 12, 9, 0, -19, 300, 17, 127);

    @Test
    void bodyVitalsPreserveWorldAndEveryValueInTheFixedSizePayload() throws IOException {
        byte[] packet = Wire.packet(Wire.VITALS, 42, out -> {
            out.writeUTF("minecraft:the_nether");
            sample.write(out);
        });
        assertEquals(68, packet.length);
        try (var in = Wire.reader(packet)) {
            assertEquals(Wire.VITALS, in.readUnsignedByte());
            assertEquals(42, in.readLong());
            assertEquals("minecraft:the_nether", in.readUTF());
            assertEquals(sample, Vitals.read(in));
            Wire.end(in);
        }
    }

    @Test
    void deathAndDrowningDoNotProduceInvalidSnapshots() {
        Vitals dead = new Vitals(0, 20, 0, 0, 0, 0, -20, 300, 0, Vitals.SURVIVAL);
        assertEquals(0, dead.health());
        assertEquals(-20, dead.air());
        assertTrue(dead.has(Vitals.SURVIVAL));
        assertFalse(dead.has(Vitals.UNDERWATER));
    }

    @Test
    void customHealthAndOxygenLimitsAreNotReplacedWithVanillaDefaults() throws IOException {
        Vitals custom = new Vitals(60, 80, 10, 25, 20, 12, 500, 600, 0, Vitals.UNDERWATER);
        try (var in = Wire.reader(Wire.packet(Wire.VITALS, 1, custom::write))) {
            in.readUnsignedByte();
            in.readLong();
            assertEquals(custom, Vitals.read(in));
            Wire.end(in);
        }
    }

    @Test
    void healthFoodAndAirChangesInvalidateEqualSnapshotCaching() {
        Vitals full = new Vitals(20, 20, 0, 0, 20, 5, 300, 300, 0, 1);
        assertNotEquals(full, new Vitals(19, 20, 0, 0, 20, 5, 300, 300, 0, 1));
        assertNotEquals(full, new Vitals(20, 20, 0, 0, 19, 5, 300, 300, 0, 1));
        assertNotEquals(full, new Vitals(20, 20, 0, 0, 20, 5, 299, 300, 0, 1));
        for (int flag = 1; flag <= Vitals.REGENERATION; flag <<= 1) assertTrue(sample.has(flag));
    }

    @Test
    void nonFiniteAndNegativeStatusValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Vitals(Float.NaN, 20, 0, 0, 20, 5, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, Float.POSITIVE_INFINITY, 0, 0, 20, 5, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, 20, Float.NaN, 0, 20, 5, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, 20, 0, 0, 20, Float.NaN, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(-1, 20, 0, 0, 20, 5, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, 0, 0, 0, 20, 5, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, 20, -1, 0, 20, 5, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, 20, 0, -1, 20, 5, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, 20, 0, 0, 21, 5, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, 20, 0, 0, 20, -1, 300, 300, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, 20, 0, 0, 20, 5, 300, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(20, 20, 0, 0, 20, 5, 300, 300, -1, 1));
    }

    @Test
    void truncatedTrailingAndUnknownFlagDataAreRejected() throws IOException {
        byte[] packet = Wire.packet(Wire.VITALS, 1, sample::write);
        try (var in = Wire.reader(Arrays.copyOf(packet, packet.length - 1))) {
            in.readUnsignedByte();
            in.readLong();
            assertThrows(IOException.class, () -> Vitals.read(in));
        }
        try (var in = Wire.reader(Arrays.copyOf(packet, packet.length + 1))) {
            in.readUnsignedByte();
            in.readLong();
            Vitals.read(in);
            assertThrows(IOException.class, () -> Wire.end(in));
        }
        packet[packet.length - 1] = (byte) 128;
        try (var in = Wire.reader(packet)) {
            in.readUnsignedByte();
            in.readLong();
            assertThrows(IllegalArgumentException.class, () -> Vitals.read(in));
        }
    }
}
