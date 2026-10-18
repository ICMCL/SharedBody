package dev.sharedbody.paper;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class GroupRoutingTest {
    private ClassNode plugin() throws IOException {
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/dev/sharedbody/paper/SharedBodyPlugin.class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void sessionStateAndAllBroadcastRecipientsAreScopedToTheSelectedGroup() throws IOException {
        var plugin = plugin();
        assertTrue(plugin.fields.stream().noneMatch(field -> List.of("body", "members", "owners", "active",
                "inputs", "motionInputs", "lastSeen", "followConfirmed").contains(field.name)));
        for (String name : List.of("broadcast", "stop", "tickSession", "beginTravel", "start", "state")) {
            var method = method(plugin, name);
            assertTrue(method.desc.startsWith("(Ldev/sharedbody/paper/Sessions$Group;"), name);
            boolean members = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.owner.equals("dev/sharedbody/paper/Sessions$Group") && field.name.equals("members")) members = true;
                if (instruction instanceof MethodInsnNode call) {
                    assertFalse(call.owner.equals("org/bukkit/Bukkit") && call.name.equals("getOnlinePlayers"), name);
                }
            }
            assertTrue(members, name);
        }
    }

    @Test
    void worldEventsDisconnectsAndDeathResolveOnlyTheAffectedPlayersGroup() throws IOException {
        var plugin = plugin();
        for (String name : List.of("beforeTeleport", "changedWorld", "worldLoaded", "quit", "death")) {
            boolean lookup = false;
            for (var instruction : method(plugin, name).instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals("dev/sharedbody/paper/Sessions")) {
                    assertNotEquals("all", call.name);
                    lookup |= call.name.equals("member") || call.name.equals("body");
                }
            }
            assertTrue(lookup, name);
        }
    }

    @Test
    void rateDropsRefreshMatchingSessionLivenessThenReturnWithoutStoppingOrDecodingActions() throws IOException {
        var packet = method(plugin(), "onPluginMessageReceived");
        boolean activity = false, checked = false;
        for (var instruction : packet.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.owner.equals("dev/sharedbody/paper/Sessions$Group") && field.name.equals("lastSeen")) activity = true;
            if (!activity || !(instruction instanceof FieldInsnNode field)
                    || !field.owner.equals("dev/sharedbody/paper/BridgeLimiter$Decision") || !field.name.equals("ACCEPT")) continue;
            assertInstanceOf(JumpInsnNode.class, instruction.getNext());
            var branch = (JumpInsnNode) instruction.getNext();
            assertEquals(Opcodes.IF_ACMPEQ, branch.getOpcode());
            boolean returns = false;
            for (var step = branch.getNext(); step != branch.label; step = step.getNext()) {
                assertNotNull(step);
                if (step instanceof MethodInsnNode call) {
                    assertFalse(List.of("stop", "readFrame", "read").contains(call.name));
                }
                returns |= step.getOpcode() == Opcodes.RETURN;
            }
            assertTrue(returns);
            checked = true;
            break;
        }
        assertTrue(checked);
    }
}
