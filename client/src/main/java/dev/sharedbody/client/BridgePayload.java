package dev.sharedbody.client;

import dev.sharedbody.common.Wire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BridgePayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<BridgePayload> TYPE = new Type<>(Identifier.parse(Wire.CHANNEL));
    public static final StreamCodec<RegistryFriendlyByteBuf, BridgePayload> CODEC =
            CustomPacketPayload.codec((payload, buf) -> buf.writeBytes(payload.bytes()), buf -> {
                if (buf.readableBytes() > Wire.MAX_BYTES) throw new IllegalArgumentException("Oversized SharedBody payload");
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new BridgePayload(bytes);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
