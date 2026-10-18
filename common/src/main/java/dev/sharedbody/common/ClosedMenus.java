package dev.sharedbody.common;

public final class ClosedMenus {
    private int closedThrough;

    public void close(int token) { closedThrough = Math.max(closedThrough, token); }
    public boolean accepts(int token) { return token > closedThrough; }
    public void reset() { closedThrough = 0; }
}
