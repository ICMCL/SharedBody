package dev.sharedbody.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Follow(long revision, String world, int entityId) {
    public Follow {
        if (revision < 0 || world.isEmpty() || world.length() > 256 || entityId < 0) {
            throw new IllegalArgumentException("Invalid follow target");
        }
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeLong(revision);
        out.writeUTF(world);
        out.writeInt(entityId);
    }

    public static Follow read(DataInputStream in) throws IOException {
        return new Follow(in.readLong(), in.readUTF(), in.readInt());
    }

    public boolean matches(String dimension, int id) { return world.equals(dimension) && entityId == id; }
}
