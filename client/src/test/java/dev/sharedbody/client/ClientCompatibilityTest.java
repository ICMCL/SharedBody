package dev.sharedbody.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ClientCompatibilityTest {
    private ClassNode node(String name) throws IOException {
        try (var in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(name + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private void method(String owner, String name, String descriptor) throws IOException {
        assertTrue(node(owner).methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)),
                owner + "." + name + descriptor);
    }

    @Test
    void everyMixinInjectionExistsInTheExact26_3Client() throws IOException {
        String input = "net/minecraft/client/input/";
        method("net/minecraft/client/KeyMapping", "set", "(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V");
        method("net/minecraft/client/KeyMapping", "click", "(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V");
        method("net/minecraft/client/MouseHandler", "onScroll", "(JDD)V");
        method("net/minecraft/client/MouseHandler", "onButton", "(JL" + input + "MouseButtonInfo;I)V");
        method("net/minecraft/client/MouseHandler", "handleAccumulatedMovement", "()V");
        method("net/minecraft/client/Camera", "alignWithEntity", "(F)V");
        method("net/minecraft/client/gui/Hud", "extractHotbarAndDecorations",
                "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V");
        method("net/minecraft/client/gui/Hud", "getCameraPlayer", "()Lnet/minecraft/world/entity/player/Player;");
        method("net/minecraft/client/renderer/extract/LevelExtractor", "extractPlayerState",
                "(Lnet/minecraft/client/Camera;Lnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/renderer/state/level/PlayerRenderState;)V");
        method("net/minecraft/client/renderer/extract/LevelExtractor", "extractEntity",
                "(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;");
        method("net/minecraft/client/renderer/GameRenderer", "renderItemInHand",
                "(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V");
        method("net/minecraft/client/KeyboardHandler", "keyPress", "(JIL" + input + "KeyEvent;)V");
        method("net/minecraft/client/KeyboardHandler", "charTyped", "(JL" + input + "CharacterEvent;)V");
        method("net/minecraft/world/entity/Entity", "turn", "(DD)V");
        String screen = "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";
        method(screen, "mouseClicked", "(L" + input + "MouseButtonEvent;Z)Z");
        method(screen, "mouseReleased", "(L" + input + "MouseButtonEvent;)Z");
        method(screen, "mouseDragged", "(L" + input + "MouseButtonEvent;DD)Z");
        method(screen, "keyPressed", "(L" + input + "KeyEvent;)Z");
    }

    private Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        throw new AssertionError("Missing annotation value " + key);
    }

    @Test
    void everySurvivalHudRedirectAndShadowMatchesTheExactNative26_3Method() throws IOException {
        int redirects = 0;
        for (String mixin : List.of("HudMixin", "HeartTypeMixin")) {
            var nativeClass = node("net/minecraft/client/gui/Hud" + (mixin.equals("HeartTypeMixin") ? "$HeartType" : ""));
            var mixinClass = node("dev/sharedbody/client/mixin/" + mixin);
            for (var field : mixinClass.fields) {
                if (field.visibleAnnotations != null && field.visibleAnnotations.stream()
                        .anyMatch(annotation -> annotation.desc.endsWith("/Shadow;"))) {
                    assertTrue(nativeClass.fields.stream().anyMatch(nativeField ->
                            nativeField.name.equals(field.name) && nativeField.desc.equals(field.desc)));
                }
            }
            for (var method : mixinClass.methods) {
                List<AnnotationNode> annotations = new ArrayList<>();
                if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
                if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
                for (var annotation : annotations) {
                    if (annotation.desc.endsWith("/Shadow;")) {
                        assertTrue(nativeClass.methods.stream().anyMatch(nativeMethod ->
                                nativeMethod.name.equals(method.name) && nativeMethod.desc.equals(method.desc)
                                        && (nativeMethod.access & 15) == (method.access & 15)));
                    }
                    if (!annotation.desc.endsWith("/Redirect;")) continue;
                    String target = (String) value((AnnotationNode) value(annotation, "at"), "target");
                    for (Object selector : (List<?>) value(annotation, "method")) {
                        var nativeMethod = nativeClass.methods.stream()
                                .filter(candidate -> candidate.name.equals(selector)).findFirst().orElseThrow();
                        boolean found = false;
                        for (var instruction : nativeMethod.instructions) {
                            if (instruction instanceof MethodInsnNode call) {
                                found |= target.equals("L" + call.owner + ";" + call.name + call.desc);
                            } else if (instruction instanceof FieldInsnNode field) {
                                found |= target.equals("L" + field.owner + ";" + field.name + ":" + field.desc);
                            }
                        }
                        assertTrue(found, selector + " must contain HUD redirect target " + target);
                        redirects++;
                    }
                }
            }
        }
        assertEquals(13, redirects);
    }

    @Test
    void sharedHudOnlyMutatesItsDetachedFoodDisplayAndNeverParticipantState() throws IOException {
        var hud = node("dev/sharedbody/client/SharedHud");
        int foodSetters = 0;
        for (var method : hud.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !call.name.startsWith("set")) continue;
                assertEquals("net/minecraft/world/food/FoodData", call.owner);
                assertTrue(call.name.equals("setFoodLevel") || call.name.equals("setSaturation"));
                foodSetters++;
            }
        }
        assertEquals(2, foodSetters);
        var initializer = hud.methods.stream().filter(method -> method.name.equals("<clinit>")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(initializer.instructions.spliterator(), false)
                .anyMatch(instruction -> instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/world/food/FoodData") && call.name.equals("<init>")));
        var reset = hud.methods.stream().filter(method -> method.name.equals("clear")).findFirst().orElseThrow();
        List<String> cleared = new ArrayList<>();
        for (var instruction : reset.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == org.objectweb.asm.Opcodes.PUTSTATIC) {
                cleared.add(field.name);
            }
        }
        assertTrue(cleared.containsAll(List.of("received", "receivedWorld", "sent", "sentWorld", "packet")));
    }

    @Test
    void spectatorHandBypassIsRestrictedToTheNativeHandRenderingGuard() throws IOException {
        var method = node("net/minecraft/client/renderer/GameRenderer").methods.stream()
                .filter(m -> m.name.equals("renderItemInHand")).findFirst().orElseThrow();
        int calls = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/multiplayer/MultiPlayerGameMode")
                    && call.name.equals("getPlayerMode")
                    && call.desc.equals("()Lnet/minecraft/world/level/GameType;")) calls++;
        }
        assertEquals(1, calls);
    }

    @Test
    void sharedCameraOnlyOverridesRotationAndNeverInjectsPredictedPosition() throws IOException {
        var mixin = node("dev/sharedbody/client/mixin/CameraMixin");
        int rotationCalls = 0;
        for (var method : mixin.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                assertNotEquals("setPosition", call.name);
                assertNotEquals("setPos", call.name);
                assertNotEquals("cameraLead", call.name);
                assertNotEquals("cameraPosition", call.name);
                if (call.owner.equals("dev/sharedbody/client/SharedBodyClient")) {
                    assertTrue(call.name.equals("cameraYaw") || call.name.equals("cameraPitch"));
                    rotationCalls++;
                }
            }
            if (method.visibleAnnotations != null) {
                assertTrue(method.visibleAnnotations.stream()
                        .noneMatch(annotation -> annotation.desc.endsWith("/Inject;")));
            }
            if (method.invisibleAnnotations != null) {
                assertTrue(method.invisibleAnnotations.stream()
                        .noneMatch(annotation -> annotation.desc.endsWith("/Inject;")));
            }
        }
        assertEquals(2, rotationCalls);
    }

    @Test
    void sharedInventoryUsesTheActualNative26_3HotbarAndOffhandIndices() throws IOException {
        var fields = node("net/minecraft/world/inventory/InventoryMenu").fields;
        for (var expected : java.util.Map.of("USE_ROW_SLOT_START", 36, "USE_ROW_SLOT_END", 45, "SHIELD_SLOT", 45).entrySet()) {
            assertTrue(fields.stream().anyMatch(field -> field.name.equals(expected.getKey()) && expected.getValue().equals(field.value)));
        }
    }

    @Test
    void renderCameraHooksCoverBothNormalAndMinecartRotationsWithoutReplacingPerspectiveLogic() throws IOException {
        var method = node("net/minecraft/client/Camera").methods.stream()
                .filter(m -> m.name.equals("alignWithEntity") && m.desc.equals("(F)V")).findFirst().orElseThrow();
        int yawCalls = 0, pitchCalls = 0, mirroredCalls = 0;
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("net/minecraft/world/entity/Entity") && call.desc.equals("(F)F")) {
                if (call.name.equals("getViewYRot")) yawCalls++;
                if (call.name.equals("getViewXRot")) pitchCalls++;
            }
            if (call.owner.equals("net/minecraft/client/CameraType") && call.name.equals("isMirrored")) mirroredCalls++;
        }
        assertEquals(2, yawCalls);
        assertEquals(2, pitchCalls);
        assertEquals(1, mirroredCalls);
    }

    @Test
    void allAccessorFieldsExistWithExpectedTypes() throws IOException {
        var keys = node("net/minecraft/client/KeyMapping").fields;
        assertTrue(keys.stream().anyMatch(field -> field.name.equals("key") && field.desc.equals("Lcom/mojang/blaze3d/platform/InputConstants$Key;")));
        assertTrue(keys.stream().anyMatch(field -> field.name.equals("isDown") && field.desc.equals("Z")));
        var fields = node("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen").fields;
        for (String name : new String[]{"leftPos", "topPos", "imageWidth", "imageHeight"}) {
            assertTrue(fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals("I")));
        }
        assertTrue(fields.stream().anyMatch(field -> field.name.equals("hoveredSlot")
                && field.desc.equals("Lnet/minecraft/world/inventory/Slot;")));
    }
}
