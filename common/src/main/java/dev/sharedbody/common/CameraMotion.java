package dev.sharedbody.common;

public final class CameraMotion {
    public static final long SEND_INTERVAL_NANOS = 16_666_667L;
    private static final double SMOOTHING_NANOS = 25_000_000.0;
    private Look target;
    private float yaw;
    private float pitch;
    private long advancedAt;
    private long sentAt;
    private long sentSequence;

    public void reset(Look initial, long now) {
        target = initial;
        yaw = initial.yaw();
        pitch = initial.pitch();
        advancedAt = now;
        sentAt = now - SEND_INTERVAL_NANOS;
        sentSequence = initial.sequence();
    }

    public boolean ready() { return target != null; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    public void clear() { target = null; }

    public void turn(float deltaYaw, float deltaPitch) {
        if (!Float.isFinite(deltaYaw) || !Float.isFinite(deltaPitch)) {
            throw new IllegalArgumentException("Invalid camera movement");
        }
        if (deltaYaw == 0 && deltaPitch == 0) return;
        yaw = Look.wrap(yaw + deltaYaw);
        pitch = Math.clamp(pitch + deltaPitch, -90, 90);
        target = new Look(target.sequence() + 1, yaw, pitch);
    }

    public boolean accept(Look update, long now) {
        if (target != null && update.sequence() <= target.sequence()) return false;
        if (target == null) {
            reset(update, now);
        } else {
            advance(now);
            target = update;
        }
        return true;
    }

    public void advance(long now) {
        if (target == null || now <= advancedAt) return;
        double alpha = -Math.expm1(-(now - advancedAt) / SMOOTHING_NANOS);
        yaw = Look.wrap(yaw + (float) (Look.wrap(target.yaw() - yaw) * alpha));
        pitch += (float) ((target.pitch() - pitch) * alpha);
        advancedAt = now;
    }

    public Look poll(long now) {
        if (target == null || target.sequence() == sentSequence || now - sentAt < SEND_INTERVAL_NANOS) return null;
        sentSequence = target.sequence();
        sentAt = now;
        return target;
    }
}
