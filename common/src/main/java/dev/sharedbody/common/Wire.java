package dev.sharedbody.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class Wire {
    public static final String CHANNEL = "sharedbody:bridge";
    public static final int VERSION = 4;
    public static final int MAX_BYTES = 30000;
    public static final int HELLO = 1, STATE = 2, INPUT = 3, MERGED = 4, SCREEN = 5,
            SCREEN_ACTION = 6, CAMERA = 7, ERROR = 8, LOOK = 9, MOTION = 10, VISUAL = 11,
            FOLLOW = 12, FOLLOW_ACK = 13, HEARTBEAT = 14, VITALS = 15;
    public static final int PRESS = 0, RELEASE = 1, DRAG = 2, KEY_PRESS = 3, CLOSE = 4, CHARACTER = 5, SCROLL = 6;

    private Wire() {}

    @FunctionalInterface
    public interface Writer { void write(DataOutputStream out) throws IOException; }

    public static byte[] packet(int kind, long generation, Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(kind);
            out.writeLong(generation);
            writer.write(out);
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Bridge packet exceeds 30 KB");
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static DataInputStream reader(byte[] data) throws IOException {
        if (data.length < 9 || data.length > MAX_BYTES) throw new IOException("Invalid bridge packet size");
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    public static void writeFrame(DataOutputStream out, Frame frame) throws IOException {
        out.writeInt(frame.held());
        out.writeInt(frame.clicks());
        out.writeFloat(frame.yaw());
        out.writeFloat(frame.pitch());
        out.writeByte(frame.hotbar());
        out.writeByte(frame.scroll());
    }

    public static Frame readFrame(DataInputStream in) throws IOException {
        return new Frame(in.readInt(), in.readInt(), in.readFloat(), in.readFloat(), in.readByte(), in.readByte());
    }

    public static void end(DataInputStream in) throws IOException {
        if (in.available() != 0) throw new IOException("Unexpected trailing bridge data");
    }

    public record ScreenAction(int menu, int operation, double x, double y, int button,
                               int modifiers, boolean doubleClick, double dx, double dy, int keycode) {
        public ScreenAction(int menu, int operation, double x, double y, int button,
                            int modifiers, boolean doubleClick, double dx, double dy) {
            this(menu, operation, x, y, button, modifiers, doubleClick, dx, dy, button);
        }

        public ScreenAction {
            if (menu < 0 || operation < PRESS || operation > SCROLL
                    || !Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 4096 || Math.abs(y) > 4096
                    || !Double.isFinite(dx) || !Double.isFinite(dy) || Math.abs(dx) > 4096 || Math.abs(dy) > 4096
                    || button < 0 || button > 0x10ffff || modifiers < 0 || modifiers > 65535 || keycode < 0) {
                throw new IllegalArgumentException("Invalid screen action");
            }
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeInt(menu); out.writeByte(operation); out.writeDouble(x); out.writeDouble(y);
            out.writeInt(button); out.writeInt(modifiers); out.writeBoolean(doubleClick);
            out.writeDouble(dx); out.writeDouble(dy);
            out.writeInt(keycode);
        }

        public static ScreenAction read(DataInputStream in) throws IOException {
            return new ScreenAction(in.readInt(), in.readUnsignedByte(), in.readDouble(), in.readDouble(),
                    in.readInt(), in.readInt(), in.readBoolean(), in.readDouble(), in.readDouble(), in.readInt());
        }
    }
}
