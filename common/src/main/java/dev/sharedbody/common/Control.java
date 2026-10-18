package dev.sharedbody.common;

public enum Control {
    LOOK("视角", 0),
    ATTACK("左键", Key.ATTACK.bit()),
    USE("右键", Key.USE.bit()),
    INVENTORY("背包 / 丢弃 / 快捷栏", Key.INVENTORY.bit() | Key.DROP.bit() | Key.DROP_ALL.bit()),
    PERSPECTIVE("切换视角（F5）", Key.PERSPECTIVE.bit()),
    OFFHAND("副手（F）", Key.SWAP.bit()),
    MOVEMENT("移动 / 疾跑", Key.FORWARD.bit() | Key.BACK.bit() | Key.LEFT.bit() | Key.RIGHT.bit() | Key.SPRINT.bit()),
    JUMP("跳跃", Key.JUMP.bit()),
    SNEAK("蹲下", Key.SNEAK.bit());

    public final String label;
    public final int mask;

    Control(String label, int mask) {
        this.label = label;
        this.mask = mask;
    }

    public static final int ALL = (1 << values().length) - 1;
    private static final int[] KEY_MASKS = keyMasks();

    private static int[] keyMasks() {
        int[] masks = new int[ALL + 1];
        for (Control control : values()) {
            for (int assigned = 0; assigned < masks.length; assigned++) {
                if ((assigned & control.bit()) != 0) masks[assigned] |= control.mask;
            }
        }
        return masks;
    }

    public static int keyMask(int controls) { return KEY_MASKS[controls & ALL]; }
    public int bit() { return 1 << ordinal(); }
}
