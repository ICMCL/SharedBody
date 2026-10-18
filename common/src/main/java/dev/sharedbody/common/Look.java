package dev.sharedbody.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Look(long sequence, float yaw, float pitch) {
    public Look {
        if (sequence < 0 || !Float.isFinite(yaw) || yaw < -180 || yaw >= 180
                || !Float.isFinite(pitch) || pitch < -90 || pitch > 90) {
            throw new IllegalArgumentException("Invalid look update");
        }
    }

    public static float wrap(float degrees) {
        float wrapped = degrees % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeLong(sequence);
        out.writeFloat(yaw);
        out.writeFloat(pitch);
    }

    public static Look read(DataInputStream in) throws IOException {
        return new Look(in.readLong(), in.readFloat(), in.readFloat());
    }
}
