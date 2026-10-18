package dev.sharedbody.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Vitals(float health, float maxHealth, float absorption, int armor, int food,
                     float saturation, int air, int maxAir, int hurt, int flags) {
    public static final int SURVIVAL = 1, UNDERWATER = 2, POISON = 4, WITHER = 8,
            FROZEN = 16, HUNGER = 32, REGENERATION = 64;
    private static final int ALL_FLAGS = 127;

    public Vitals {
        if (!Float.isFinite(health) || health < 0 || !Float.isFinite(maxHealth) || maxHealth <= 0
                || !Float.isFinite(absorption) || absorption < 0 || armor < 0 || food < 0 || food > 20
                || !Float.isFinite(saturation) || saturation < 0 || maxAir <= 0 || hurt < 0
                || (flags & ~ALL_FLAGS) != 0) {
            throw new IllegalArgumentException("Invalid body vitals");
        }
    }

    public boolean has(int flag) { return (flags & flag) != 0; }

    public void write(DataOutputStream out) throws IOException {
        out.writeFloat(health);
        out.writeFloat(maxHealth);
        out.writeFloat(absorption);
        out.writeInt(armor);
        out.writeInt(food);
        out.writeFloat(saturation);
        out.writeInt(air);
        out.writeInt(maxAir);
        out.writeInt(hurt);
        out.writeByte(flags);
    }

    public static Vitals read(DataInputStream in) throws IOException {
        return new Vitals(in.readFloat(), in.readFloat(), in.readFloat(), in.readInt(), in.readInt(),
                in.readFloat(), in.readInt(), in.readInt(), in.readInt(), in.readUnsignedByte());
    }
}
