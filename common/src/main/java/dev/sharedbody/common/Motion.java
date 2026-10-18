package dev.sharedbody.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Motion(long sequence, int held) {
    public static final int MASK = Control.MOVEMENT.mask | Control.JUMP.mask | Control.SNEAK.mask;

    public Motion {
        if (sequence < 0 || (held & ~MASK) != 0) throw new IllegalArgumentException("Invalid motion input");
    }

    public int authorized(int controls) {
        return held & Control.keyMask(controls);
    }

    public static int overlay(int remote, int local, int controls) {
        int owned = Control.keyMask(controls) & MASK;
        return (remote & ~owned & MASK) | (local & owned);
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeLong(sequence);
        out.writeInt(held);
    }

    public static Motion read(DataInputStream in) throws IOException {
        return new Motion(in.readLong(), in.readInt());
    }
}
