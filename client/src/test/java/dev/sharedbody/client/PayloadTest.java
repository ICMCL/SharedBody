package dev.sharedbody.client;

import dev.sharedbody.common.Frame;
import dev.sharedbody.common.Look;
import dev.sharedbody.common.Motion;
import dev.sharedbody.common.Follow;
import dev.sharedbody.common.Key;
import dev.sharedbody.common.Wire;
import dev.sharedbody.common.Vitals;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PayloadTest {
    @Test
    void protocolFourStreamsUseTheSameRawFabricCodec() throws IOException {
        Motion motion = new Motion(1, Key.FORWARD.bit() | Key.SNEAK.bit());
        Follow follow = new Follow(2, "minecraft:the_nether", 123);
        byte[][] packets = {
                Wire.packet(Wire.MOTION, 8, motion::write),
                Wire.packet(Wire.FOLLOW, 8, follow::write),
                Wire.packet(Wire.FOLLOW_ACK, 8, follow::write),
                Wire.packet(Wire.HEARTBEAT, 8, out -> {}),
                Wire.packet(Wire.VISUAL, 8, out -> {
                    out.writeUTF("minecraft:the_nether");
                    out.writeByte(8);
                    out.write(new byte[]{31, -117, 8});
                }),
                Wire.packet(Wire.VITALS, 8, out -> {
                    out.writeUTF("minecraft:the_nether");
                    new Vitals(7, 20, 4, 8, 12, 0, 100, 300, 5, Vitals.SURVIVAL | Vitals.UNDERWATER).write(out);
                })
        };
        for (byte[] packet : packets) {
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                BridgePayload.CODEC.encode(buffer, new BridgePayload(packet));
                assertEquals(packet.length, buffer.readableBytes());
                assertArrayEquals(packet, BridgePayload.CODEC.decode(buffer).bytes());
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
        assertEquals(4, Wire.VERSION);
    }

    @Test
    void fabricCodecIsByteForByteCompatibleWithPaperPluginMessaging() throws IOException {
        byte[] packet = Wire.packet(Wire.INPUT, 99, out -> Wire.writeFrame(out, Frame.EMPTY));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BridgePayload.CODEC.encode(buffer, new BridgePayload(packet));
            assertEquals(packet.length, buffer.readableBytes());
            assertEquals(Wire.INPUT, buffer.getUnsignedByte(0));
            assertArrayEquals(packet, BridgePayload.CODEC.decode(buffer).bytes());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void oversizedIncomingPayloadIsRejectedBeforeAllocatingItsContents() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeZero(Wire.MAX_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> BridgePayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void independentLookStreamSurvivesFabricCodecWithoutAnExtraLengthPrefix() throws IOException {
        Look look = new Look(200, -179, 80);
        byte[] packet = Wire.packet(Wire.LOOK, 99, look::write);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BridgePayload.CODEC.encode(buffer, new BridgePayload(packet));
            assertEquals(25, buffer.readableBytes());
            try (var in = Wire.reader(BridgePayload.CODEC.decode(buffer).bytes())) {
                assertEquals(Wire.LOOK, in.readUnsignedByte());
                assertEquals(99, in.readLong());
                assertEquals(look, Look.read(in));
                Wire.end(in);
            }
        } finally {
            buffer.release();
        }
    }

    @Test
    void sessionSeedPreservesBodyIdentityAndInitialViewBeforeTheFirstMouseMovement() throws IOException {
        UUID body = UUID.randomUUID();
        Look initial = new Look(0, 123, -45);
        byte[] packet = Wire.packet(Wire.STATE, 99, out -> {
            out.writeBoolean(true);
            out.writeBoolean(false);
            out.writeInt(1);
            out.writeLong(body.getMostSignificantBits());
            out.writeLong(body.getLeastSignificantBits());
            initial.write(out);
        });
        try (var in = Wire.reader(packet)) {
            assertEquals(Wire.STATE, in.readUnsignedByte());
            assertEquals(99, in.readLong());
            assertTrue(in.readBoolean());
            assertFalse(in.readBoolean());
            assertEquals(1, in.readInt());
            assertEquals(body, new UUID(in.readLong(), in.readLong()));
            assertEquals(initial, Look.read(in));
            Wire.end(in);
        }
    }
}
