package dev.sharedbody.common;

public enum Key {
    FORWARD, BACK, LEFT, RIGHT, SPRINT, JUMP, SNEAK, ATTACK, USE,
    INVENTORY, DROP, DROP_ALL, SWAP, PERSPECTIVE, PICK;

    public int bit() { return 1 << ordinal(); }
    public static final int ALL = (1 << values().length) - 1;
}
