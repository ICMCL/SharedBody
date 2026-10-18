package dev.sharedbody.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sharedbody.client.mixin.ContainerScreenAccessor;
import dev.sharedbody.client.mixin.KeyMappingAccessor;
import dev.sharedbody.common.Control;
import dev.sharedbody.common.CameraMotion;
import dev.sharedbody.common.Frame;
import dev.sharedbody.common.Key;
import dev.sharedbody.common.Look;
import dev.sharedbody.common.Motion;
import dev.sharedbody.common.Follow;
import dev.sharedbody.common.Wire;
import dev.sharedbody.common.Vitals;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SharedBodyClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("SharedBody");
    private static final Map<InputConstants.Key, Boolean> PHYSICAL = new HashMap<>();
    private static final CameraMotion LOOK = new CameraMotion();
    private static final MenuSnapshot.Encoder SCREEN_ENCODER = new MenuSnapshot.Encoder();
    private static Options mappedOptions;
    private static KeyMapping[] mappedKeys;
    private static UUID bodyId;
    private static boolean active;
    private static boolean body;
    private static boolean replaying;
    private static int controls;
    private static long generation;
    private static long ticks;
    private static long lastMerged;
    private static int previous;
    private static int edges;
    private static int toggles;
    private static int previousRaw;
    private static int localMotion;
    private static int sentMotion = -1;
    private static long motionSequence;
    private static long sentMotionAt;
    private static int remoteMotion;
    private static long remoteMotionSequence = -1;
    private static long lastMotionAt;
    private static boolean inventoryConsumed;
    private static Follow followTarget;
    private static Object clientWorld;
    private static int previousHotbar;
    private static int localSlot = -1;
    private static int scroll;
    private static Frame merged = Frame.EMPTY;
    private static int mergedClicks;
    private static int pendingSlot = -1;
    private static int pendingScroll;
    private static byte[] lastScreen;
    private static boolean screenOpen;
    private static Object nativeScreen;
    private static int screenToken;
    private static int lastCamera = -1;
    private static CameraType originalCamera;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(BridgePayload.TYPE, BridgePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BridgePayload.TYPE, BridgePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE, (payload, context) ->
                context.client().execute(() -> receive(payload.bytes())));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> hello());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.START_CLIENT_TICK.register(SharedBodyClient::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(SharedBodyClient::endTick);
    }

    private static void hello() {
        if (ClientPlayNetworking.canSend(BridgePayload.TYPE)) {
            send(Wire.packet(Wire.HELLO, 0, out -> out.writeInt(Wire.VERSION)));
        }
    }

    private static void reset() {
        Minecraft mc = Minecraft.getInstance();
        active = false;
        body = false;
        replaying = false;
        controls = previous = edges = mergedClicks = toggles = previousRaw = previousHotbar = 0;
        localSlot = -1;
        LOOK.clear();
        SharedInventory.clear();
        SharedHud.clear();
        RemoteInventoryScreen.reset();
        localMotion = remoteMotion = 0;
        sentMotion = -1;
        motionSequence = 0;
        remoteMotionSequence = -1;
        inventoryConsumed = false;
        followTarget = null;
        clientWorld = null;
        bodyId = null;
        scroll = pendingScroll = 0;
        pendingSlot = -1;
        merged = Frame.EMPTY;
        PHYSICAL.clear();
        lastScreen = null;
        SCREEN_ENCODER.clear();
        screenOpen = false;
        nativeScreen = null;
        lastCamera = -1;
        if (originalCamera != null) mc.options.setCameraType(originalCamera);
        originalCamera = null;
        KeyMapping.releaseAll();
    }

    private static void receive(byte[] bytes) {
        try (DataInputStream in = Wire.reader(bytes)) {
            int kind = in.readUnsignedByte();
            long epoch = in.readLong();
            if (kind == Wire.STATE) {
                boolean enabled = in.readBoolean();
                boolean host = in.readBoolean();
                int assigned = in.readInt();
                UUID hostId = enabled ? new UUID(in.readLong(), in.readLong()) : null;
                Look initialLook = enabled ? Look.read(in) : null;
                Wire.end(in);
                if ((assigned & ~Control.ALL) != 0) throw new IOException("Invalid control mask");
                if (!enabled && !active) {
                    generation = epoch;
                    return;
                }
                if (enabled && active && epoch == generation && body == host && controls == assigned) return;
                reset();
                generation = epoch;
                if (enabled) {
                    originalCamera = Minecraft.getInstance().options.getCameraType();
                    active = true;
                    body = host;
                    controls = assigned;
                    bodyId = hostId;
                    LOOK.reset(initialLook, System.nanoTime());
                    lastMerged = ticks;
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) mc.player.sendSystemMessage(Component.literal("共享身体已连接；/sb stop 退出"));
                }
                return;
            }
            if (!active || epoch != generation) return;
            switch (kind) {
                case Wire.MERGED -> {
                    if (!body) throw new IOException("Merged input delivered to a controller");
                    merged = Wire.readFrame(in);
                    Wire.end(in);
                    mergedClicks |= merged.clicks();
                    if (merged.hotbar() >= 0) pendingSlot = merged.hotbar();
                    pendingScroll = Math.clamp(pendingScroll + merged.scroll(), -9, 9);
                    lastMerged = ticks;
                }
                case Wire.MOTION -> {
                    Motion motion = Motion.read(in);
                    Wire.end(in);
                    if (motion.sequence() > remoteMotionSequence) {
                        remoteMotionSequence = motion.sequence();
                        remoteMotion = motion.held();
                        lastMotionAt = ticks;
                        applyMotion();
                    }
                }
                case Wire.FOLLOW -> {
                    Follow target = Follow.read(in);
                    Wire.end(in);
                    if (!body && (followTarget == null || target.revision() >= followTarget.revision())) {
                        if (!target.equals(followTarget)) {
                            followTarget = target;
                            RemoteInventoryScreen.reset();
                            SharedHud.clear();
                        }
                        repairFollow(true);
                    }
                }
                case Wire.VISUAL -> {
                    String world = in.readUTF();
                    if (!body && visibleBody() != null && world.equals(Minecraft.getInstance().level.dimension().identifier().toString())) {
                        SharedInventory.accept(in);
                    }
                }
                case Wire.VITALS -> {
                    String world = in.readUTF();
                    Vitals vitals = Vitals.read(in);
                    Wire.end(in);
                    if (!body && visibleBody() != null
                            && world.equals(Minecraft.getInstance().level.dimension().identifier().toString())) {
                        SharedHud.accept(vitals);
                    }
                }
                case Wire.LOOK -> {
                    Look look = Look.read(in);
                    Wire.end(in);
                    if (!owns(Control.LOOK)) LOOK.accept(look, System.nanoTime());
                }
                case Wire.CAMERA -> {
                    int camera = in.readUnsignedByte();
                    Wire.end(in);
                    if (camera > 2) throw new IOException("Invalid perspective");
                    Minecraft.getInstance().options.setCameraType(CameraType.values()[camera]);
                }
                case Wire.SCREEN -> {
                    String world = in.readUTF();
                    if (!body && owns(Control.INVENTORY) && visibleBody() != null
                            && world.equals(Minecraft.getInstance().level.dimension().identifier().toString())) {
                        RemoteInventoryScreen.accept(in.readAllBytes());
                    }
                }
                case Wire.SCREEN_ACTION -> {
                    Wire.ScreenAction action = Wire.ScreenAction.read(in);
                    Wire.end(in);
                    if (body) replayScreen(action);
                }
                default -> throw new IOException("Unexpected server message " + kind);
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException | io.netty.handler.codec.DecoderException error) {
            fail("客户端无法读取共享身体消息", error);
        }
    }

    public static boolean owns(Control control) { return (controls & control.bit()) != 0; }
    public static boolean blockBodyInventory() { return active && body && !owns(Control.INVENTORY) && !replaying; }

    private static KeyMapping[] mappings(Options options) {
        if (mappedOptions != options) {
            mappedKeys = new KeyMapping[]{options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                    options.keySprint, options.keyJump, options.keyShift, options.keyAttack, options.keyUse,
                    options.keyInventory, options.keyDrop, null, options.keySwapOffhand,
                    options.keyTogglePerspective, options.keyPickItem};
            mappedOptions = options;
        }
        return mappedKeys;
    }

    private static InputConstants.Key bound(KeyMapping mapping) {
        return ((KeyMappingAccessor) mapping).sharedbody$key();
    }

    private static boolean controlled(InputConstants.Key key) {
        Options options = Minecraft.getInstance().options;
        for (KeyMapping mapping : mappings(options)) {
            if (mapping != null && mapping.matches(key)) return true;
        }
        for (KeyMapping mapping : options.keyHotbarSlots) if (mapping.matches(key)) return true;
        return false;
    }

    public static boolean captureKey(InputConstants.Key key, boolean down) {
        if (!active || replaying || !controlled(key)) return false;
        boolean wasDown = PHYSICAL.getOrDefault(key, false);
        PHYSICAL.put(key, down);
        if (down && !wasDown && Minecraft.getInstance().gui.screen() == null) {
            KeyMapping[] mappings = mappings(Minecraft.getInstance().options);
            for (int i = 0; i < mappings.length; i++) {
                if (mappings[i] != null && mappings[i].matches(key)
                        && !(i == Key.INVENTORY.ordinal() && inventoryConsumed)) edges |= 1 << i;
            }
            KeyMapping[] hotbar = Minecraft.getInstance().options.keyHotbarSlots;
            for (int i = 0; i < hotbar.length; i++) {
                if (hotbar[i].matches(key)) localSlot = i;
            }
        }
        updateMotion();
        return true;
    }

    public static boolean suppressClick(InputConstants.Key key) {
        return active && !replaying && controlled(key);
    }

    public static boolean captureMouseButton(int button, boolean down) {
        if (!active) return false;
        captureKey(InputConstants.Type.MOUSE.getOrCreate(button), down);
        return blockBodyInventory() && Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>;
    }

    public static boolean captureLook(double dx, double dy) {
        if (!active || replaying) return false;
        if (owns(Control.LOOK)) {
            LOOK.turn((float) (dx * 0.15), (float) (dy * 0.15));
        }
        return true;
    }

    public static void updateLook() {
        if (!active || !LOOK.ready()) return;
        repairFollow(false);
        updateMotion();
        long now = System.nanoTime();
        if (owns(Control.LOOK)) {
            Look update = LOOK.poll(now);
            if (update != null) send(Wire.packet(Wire.LOOK, generation, update::write));
        } else {
            LOOK.advance(now);
        }
        Minecraft mc = Minecraft.getInstance();
        if (body && mc.player != null && !mc.player.isSleeping()) {
            replaying = true;
            try {
                float deltaYaw = Look.wrap(LOOK.yaw() - mc.player.getYRot());
                float deltaPitch = LOOK.pitch() - mc.player.getXRot();
                mc.player.turn(deltaYaw / 0.15, deltaPitch / 0.15);
            } finally {
                replaying = false;
            }
        }
    }

    public static AbstractClientPlayer visibleBody() {
        Minecraft mc = Minecraft.getInstance();
        if (!active || body || mc.level == null || bodyId == null) return null;
        Entity entity = followTarget == null ? null : mc.level.getEntity(followTarget.entityId());
        return entity instanceof AbstractClientPlayer player && bodyId.equals(player.getUUID())
                && followTarget.world().equals(mc.level.dimension().identifier().toString()) ? player : null;
    }

    private static void repairFollow(boolean acknowledge) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || body || mc.level == null || mc.player == null) return;
        if (clientWorld != mc.level) {
            clientWorld = mc.level;
            SharedInventory.clear();
            SharedHud.clear();
            RemoteInventoryScreen.reset();
        }
        AbstractClientPlayer host = visibleBody();
        if (host == null) return;
        boolean detached = mc.getCameraEntity() != host;
        if (detached) mc.setCameraEntity(host);
        if (detached || acknowledge) send(Wire.packet(Wire.FOLLOW_ACK, generation, followTarget::write));
    }

    private static int effectiveMotion() {
        int network = ticks - lastMotionAt <= 5 ? remoteMotion : 0;
        return Motion.overlay(network, localMotion, controls);
    }

    private static void applyMotion() {
        if (!active || !body) return;
        int held = effectiveMotion();
        KeyMapping[] keys = mappings(Minecraft.getInstance().options);
        for (int i = 0; i <= Key.SNEAK.ordinal(); i++) {
            ((KeyMappingAccessor) keys[i]).sharedbody$down((held & (1 << i)) != 0);
        }
    }

    private static void updateMotion() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        boolean gameplay = mc.player != null && mc.level != null && mc.gui.screen() == null && mc.isWindowActive();
        int raw = 0;
        if (gameplay) {
            KeyMapping[] keys = mappings(mc.options);
            for (int i = 0; i <= Key.SNEAK.ordinal(); i++) if (physical(keys[i])) raw |= 1 << i;
        }
        int pressed = raw & ~previousRaw;
        previousRaw = raw;
        if (gameplay) {
            if (mc.options.toggleCrouch().get() && (pressed & Key.SNEAK.bit()) != 0) toggles ^= Key.SNEAK.bit();
            if (mc.options.toggleSprint().get() && (pressed & Key.SPRINT.bit()) != 0) toggles ^= Key.SPRINT.bit();
            if (mc.options.toggleCrouch().get()) raw = (raw & ~Key.SNEAK.bit()) | (toggles & Key.SNEAK.bit());
            if (mc.options.toggleSprint().get()) raw = (raw & ~Key.SPRINT.bit()) | (toggles & Key.SPRINT.bit());
        }
        localMotion = raw & Control.keyMask(controls);
        if (localMotion != sentMotion || ticks - sentMotionAt >= 2) {
            Motion motion = new Motion(++motionSequence, localMotion);
            send(Wire.packet(Wire.MOTION, generation, motion::write));
            sentMotion = localMotion;
            sentMotionAt = ticks;
        }
        applyMotion();
    }

    public static boolean inventoryKey(int action, KeyEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || !mc.options.keyInventory.matches(event)) return false;
        if (action == 0) {
            inventoryConsumed = false;
            return false;
        }
        if (action != 1) return inventoryConsumed;
        if (mc.gui.screen() instanceof RemoteInventoryScreen screen) {
            inventoryConsumed = true;
            screen.onClose();
            return true;
        }
        if (body && owns(Control.INVENTORY) && mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            inventoryConsumed = true;
            edges &= ~Key.INVENTORY.bit();
            screen.onClose();
            return true;
        }
        if (!body && !owns(Control.INVENTORY) && mc.gui.screen() == null) {
            inventoryConsumed = true;
            SharedInventory.openReadOnly();
            return true;
        }
        return false;
    }

    private static boolean sharedCamera(Entity entity) {
        return active && LOOK.ready() && bodyId != null && bodyId.equals(entity.getUUID())
                && !(entity instanceof net.minecraft.world.entity.LivingEntity living && living.isSleeping());
    }

    public static float cameraYaw(Entity entity, float partialTick) {
        return sharedCamera(entity) ? LOOK.yaw() : entity.getViewYRot(partialTick);
    }

    public static float cameraPitch(Entity entity, float partialTick) {
        return sharedCamera(entity) ? LOOK.pitch() : entity.getViewXRot(partialTick);
    }

    public static boolean captureScroll(double value) {
        if (!active) return false;
        if (Minecraft.getInstance().gui.screen() != null) {
            return blockBodyInventory() && Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>;
        }
        if (owns(Control.INVENTORY)) scroll = Math.clamp(scroll + (int) Math.signum(value), -9, 9);
        return true;
    }

    private static boolean physical(KeyMapping mapping) {
        InputConstants.Key key = bound(mapping);
        return key.getType() == InputConstants.Type.KEYBOARD && key.getValue() >= 0
                ? InputConstants.isKeyDown(key.getValue()) : PHYSICAL.getOrDefault(key, false);
    }

    private static void startTick(Minecraft mc) {
        ticks++;
        if (active && ticks % 20 == 0) send(Wire.packet(Wire.HEARTBEAT, generation, out -> {}));
        if (mc.player == null || mc.level == null) {
            if (active) updateMotion();
            return;
        }
        if (!active) {
            if (ticks % 40 == 0) hello();
            return;
        }
        updateLook();
        boolean gameplay = mc.gui.screen() == null && mc.isWindowActive();
        int held = 0;
        int hotbar = gameplay && owns(Control.INVENTORY) ? localSlot : -1;
        int hotbarHeld = 0;
        KeyMapping[] keys = mappings(mc.options);
        if (gameplay) {
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != null && physical(keys[i])) held |= 1 << i;
            }
            if (InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL)) {
                held |= Key.DROP_ALL.bit();
            }
            if (owns(Control.INVENTORY)) {
                for (int i = 0; i < mc.options.keyHotbarSlots.length; i++) {
                    if (physical(mc.options.keyHotbarSlots[i])) {
                        hotbarHeld |= 1 << i;
                        if ((previousHotbar & (1 << i)) == 0) hotbar = i;
                    }
                }
            }
        }
        if (!physical(mc.options.keyInventory)) inventoryConsumed = false;
        if (inventoryConsumed) held &= ~Key.INVENTORY.bit();
        previousHotbar = hotbarHeld;
        localSlot = -1;
        int clicks = gameplay ? (held & ~previous) | edges : 0;
        Frame local = new Frame(held & ~Motion.MASK, clicks & ~Motion.MASK, 0, 0,
                hotbar, gameplay ? scroll : 0);
        previous = held;
        edges = 0;
        scroll = 0;
        send(Wire.packet(Wire.INPUT, generation, out -> Wire.writeFrame(out, local)));
        replaying = true;
        try {
            Frame applied = body && ticks - lastMerged <= 5 ? merged : Frame.EMPTY;
            int appliedHeld = (applied.held() & ~Motion.MASK) | effectiveMotion();
            for (int i = 0; i < keys.length; i++) {
                KeyMapping mapping = keys[i];
                if (mapping == null) continue;
                ((KeyMappingAccessor) mapping).sharedbody$down(body && (appliedHeld & (1 << i)) != 0);
                if (body && (mergedClicks & (1 << i)) != 0 && i != Key.DROP.ordinal()) KeyMapping.click(bound(mapping));
            }
            for (KeyMapping mapping : mc.options.keyHotbarSlots) ((KeyMappingAccessor) mapping).sharedbody$down(false);
            if (body) {
                if (pendingSlot >= 0) mc.player.getInventory().setSelectedSlot(pendingSlot);
                if (pendingScroll != 0) {
                    int slot = Math.floorMod(mc.player.getInventory().getSelectedSlot() - pendingScroll, 9);
                    mc.player.getInventory().setSelectedSlot(slot);
                }
                if ((mergedClicks & Key.DROP.bit()) != 0 && mc.gui.screen() == null && mc.gameMode != null) {
                    mc.gameMode.dropItem(mc.player, (applied.held() & Key.DROP_ALL.bit()) != 0);
                }
                if (ticks - lastMerged > 60) fail("服务端输入桥接超时", null);
            }
            mergedClicks = 0;
            pendingSlot = -1;
            pendingScroll = 0;
        } finally {
            replaying = false;
        }
    }

    private static void endTick(Minecraft mc) {
        if (!active || !body || mc.player == null || mc.level == null) return;
        try {
            int camera = mc.options.getCameraType().ordinal();
            if (camera != lastCamera) {
                send(Wire.packet(Wire.CAMERA, generation, out -> out.writeByte(camera)));
                lastCamera = camera;
            }
            if (ticks % 2 != 0) return;
            SharedHud.publish(mc, generation, ticks);
            SharedInventory.publish(mc, generation, ticks);
            boolean open = mc.gui.screen() instanceof AbstractContainerScreen<?>;
            if (mc.gui.screen() != nativeScreen) {
                nativeScreen = mc.gui.screen();
                screenToken++;
            }
            if (open || screenOpen) {
                byte[] snapshot = MenuSnapshot.capture(mc, screenToken, SCREEN_ENCODER);
                if (lastScreen != snapshot || ticks % 10 == 0) {
                    send(Wire.packet(Wire.SCREEN, generation, out -> {
                        out.writeUTF(mc.level.dimension().identifier().toString());
                        out.write(snapshot);
                    }));
                    lastScreen = snapshot;
                }
            }
            screenOpen = open;
        } catch (IOException | IllegalArgumentException error) {
            fail("无法同步身体界面（状态、物品数据或屏幕无效）", error);
        }
    }

    static void screenAction(Wire.ScreenAction action) {
        if (active && owns(Control.INVENTORY)) {
            send(Wire.packet(Wire.SCREEN_ACTION, generation, action::write));
        }
    }

    private static void replayScreen(Wire.ScreenAction action) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
                || screen != nativeScreen || screenToken != action.menu()) return;
        ContainerScreenAccessor geometry = (ContainerScreenAccessor) screen;
        var hovered = screen.getMenu().slots.stream().filter(slot -> slot.isActive()
                && action.x() >= slot.x && action.x() < slot.x + 16
                && action.y() >= slot.y && action.y() < slot.y + 16).findFirst().orElse(null);
        geometry.sharedbody$hovered(hovered);
        MouseButtonEvent mouse = new MouseButtonEvent(action.x() + geometry.sharedbody$left(),
                action.y() + geometry.sharedbody$top(), new MouseButtonInfo(action.button(), action.modifiers()));
        replaying = true;
        try {
            switch (action.operation()) {
                case Wire.PRESS -> screen.mouseClicked(mouse, action.doubleClick());
                case Wire.RELEASE -> screen.mouseReleased(mouse);
                case Wire.DRAG -> screen.mouseDragged(mouse, action.dx(), action.dy());
                case Wire.KEY_PRESS -> screen.keyPressed(new KeyEvent(action.button(), action.keycode(), action.modifiers()));
                case Wire.CHARACTER -> screen.charTyped(new CharacterEvent(action.button()));
                case Wire.SCROLL -> screen.mouseScrolled(mouse.x(), mouse.y(), action.dx(), action.dy());
                case Wire.CLOSE -> screen.onClose();
                default -> throw new IllegalArgumentException("Invalid screen operation");
            }
        } finally {
            replaying = false;
        }
    }

    static void send(byte[] packet) {
        if (ClientPlayNetworking.canSend(BridgePayload.TYPE)) ClientPlayNetworking.send(new BridgePayload(packet));
    }

    private static void fail(String message, Exception error) {
        LOGGER.error(message, error);
        if (active) send(Wire.packet(Wire.ERROR, generation, out -> out.writeUTF(message)));
        Minecraft mc = Minecraft.getInstance();
        reset();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("SharedBody：" + message));
    }
}
