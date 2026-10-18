package dev.sharedbody.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotEncoderTest {
    private byte[] decode(byte[] compressed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }

    @Test
    void unchangedContentsReuseOneCompressedSnapshotAcrossOneHundredCaptures() throws IOException {
        var encoder = new MenuSnapshot.Encoder();
        Object world = new Object();
        byte[] raw = "inventory contents".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encoder.encode(world, raw);
        for (int i = 0; i < 100; i++) assertSame(compressed, encoder.encode(world, raw.clone()));
        assertArrayEquals(raw, decode(compressed));
    }

    @Test
    void changesAlwaysProduceAnUpdatedSnapshotEvenWithTheSameLength() throws IOException {
        var encoder = new MenuSnapshot.Encoder();
        Object world = new Object();
        byte[] before = {1, 2, 3, 4}, after = {1, 2, 9, 4};
        byte[] old = encoder.encode(world, before);
        byte[] current = encoder.encode(world, after);
        assertNotSame(old, current);
        assertArrayEquals(before, decode(old));
        assertArrayEquals(after, decode(current));
        assertSame(current, encoder.encode(world, after.clone()));
    }

    @Test
    void switchingWorldForcesFreshOutputEvenIfRegistryEncodedBytesAreIdentical() throws IOException {
        var encoder = new MenuSnapshot.Encoder();
        byte[] contents = {1, 2, 3};
        byte[] old = encoder.encode(new Object(), contents);
        byte[] current = encoder.encode(new Object(), contents.clone());
        assertNotSame(old, current);
        assertArrayEquals(contents, decode(current));
    }

    @Test
    void stoppingAndRestartingClearsCachedIdentityForTheNewSession() throws IOException {
        var encoder = new MenuSnapshot.Encoder();
        Object world = new Object();
        byte[] contents = {1, 2, 3};
        byte[] old = encoder.encode(world, contents);
        encoder.clear();
        byte[] current = encoder.encode(world, contents.clone());
        assertNotSame(old, current);
        assertArrayEquals(contents, decode(current));
    }

    @Test
    void editableAndReadOnlyStreamsDoNotReplaceEachOthersCache() throws IOException {
        var screen = new MenuSnapshot.Encoder();
        var inventory = new MenuSnapshot.Encoder();
        Object world = new Object();
        byte[] contents = {1, 2, 3};
        byte[] editable = screen.encode(world, contents);
        byte[] readOnly = inventory.encode(world, contents.clone());
        assertNotSame(editable, readOnly);
        inventory.encode(world, new byte[]{9});
        assertSame(editable, screen.encode(world, contents.clone()));
    }

    @Test
    void emptyAndLargeSnapshotsRemainValidGzipAndCanBeReused() throws IOException {
        var encoder = new MenuSnapshot.Encoder();
        Object world = new Object();
        for (int size : new int[]{0, 1, 4096, 1_000_000}) {
            byte[] raw = new byte[size];
            for (int i = 0; i < raw.length; i++) raw[i] = (byte) (i * 31);
            byte[] compressed = encoder.encode(world, raw);
            assertArrayEquals(raw, decode(compressed));
            assertSame(compressed, encoder.encode(world, raw.clone()));
        }
    }
}
