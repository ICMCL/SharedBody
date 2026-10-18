package dev.sharedbody.paper;

import dev.sharedbody.common.Control;
import dev.sharedbody.common.Frame;
import dev.sharedbody.common.Key;
import dev.sharedbody.common.Motion;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SessionsTest {
    private final Sessions sessions = new Sessions();
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();

    @Test
    void creatingAnotherBodyDoesNotReplaceAnExistingGroupOrItsControls() {
        var first = sessions.create(alice, 1);
        sessions.add(first, List.of(carol));
        first.owners.put(Control.LOOK, carol);
        first.active = true;
        var second = sessions.create(bob, 2);
        assertEquals(2, sessions.all().size());
        assertSame(first, sessions.body(alice));
        assertSame(first, sessions.member(carol));
        assertTrue(first.active);
        assertEquals(carol, first.owners.get(Control.LOOK));
        assertEquals(Control.ALL, second.controls(bob));
        assertNotEquals(first.generation, second.generation);
    }

    @Test
    void aPlayerCannotBelongToTwoGroupsOrBecomeAnotherBodyWhileAssigned() {
        var first = sessions.create(alice, 1);
        var second = sessions.create(bob, 2);
        sessions.add(first, List.of(carol));
        assertThrows(IllegalArgumentException.class, () -> sessions.add(second, List.of(carol)));
        assertThrows(IllegalArgumentException.class, () -> sessions.create(carol, 3));
        assertThrows(IllegalArgumentException.class, () -> sessions.create(alice, 3));
        assertSame(first, sessions.member(carol));
        assertEquals(1, second.members.size());
    }

    @Test
    void duplicateBulkAdditionsAreIdempotentAndDoNotConsumeCapacity() {
        var group = sessions.create(alice, 1);
        assertEquals(2, sessions.add(group, List.of(alice, bob, bob, carol, alice)));
        assertEquals(0, sessions.add(group, List.of(alice, bob, carol)));
        assertEquals(3, group.members.size());
    }

    @Test
    void aConflictingBatchDoesNotPartiallyAddAnyPlayers() {
        var first = sessions.create(alice, 1);
        sessions.create(bob, 2);
        assertThrows(IllegalArgumentException.class, () -> sessions.add(first, List.of(carol, bob)));
        assertFalse(first.members.contains(carol));
        assertNull(sessions.member(carol));
    }

    @Test
    void capacityIsPerGroupAndCheckedBeforeChangingTheMembershipIndex() {
        var first = sessions.create(alice, 1);
        var second = sessions.create(bob, 2);
        List<UUID> crowd = IntStream.range(0, 127).mapToObj(ignored -> UUID.randomUUID()).toList();
        assertEquals(127, sessions.add(first, crowd));
        assertEquals(128, first.members.size());
        assertThrows(IllegalArgumentException.class, () -> sessions.add(first, List.of(carol)));
        assertNull(sessions.member(carol));
        assertEquals(1, sessions.add(second, List.of(carol)));
    }

    @Test
    void removingAnOwnerOnlyReassignsControlsInThatOwnersGroup() {
        var first = sessions.create(alice, 1);
        var second = sessions.create(bob, 2);
        sessions.add(first, List.of(carol));
        first.owners.put(Control.MOVEMENT, carol);
        sessions.remove(first, carol);
        assertEquals(alice, first.owners.get(Control.MOVEMENT));
        assertEquals(bob, second.owners.get(Control.MOVEMENT));
        assertNull(sessions.member(carol));
        assertEquals(1, sessions.add(second, List.of(carol)));
        assertThrows(IllegalArgumentException.class, () -> sessions.remove(second, bob));
    }

    @Test
    void dissolvingOneBodyDoesNotTouchAnotherAndInvalidatesStaleMenus() {
        var first = sessions.create(alice, 1);
        var second = sessions.create(bob, 2);
        sessions.add(first, List.of(carol));
        second.active = true;
        sessions.dissolve(first);
        assertNull(sessions.member(carol));
        assertNull(sessions.body(alice));
        assertTrue(second.active);
        var recreated = sessions.create(alice, 3);
        assertTrue(sessions.contains(recreated));
        assertFalse(sessions.contains(first));
        assertThrows(IllegalArgumentException.class, () -> sessions.add(first, List.of(carol)));
    }

    @Test
    void editsAndDissolveAreRejectedForARunningGroupButOtherGroupsStayEditable() {
        var first = sessions.create(alice, 1);
        var second = sessions.create(bob, 2);
        first.active = true;
        assertThrows(IllegalArgumentException.class, () -> sessions.dissolve(first));
        assertThrows(IllegalArgumentException.class, () -> sessions.add(first, List.of(carol)));
        assertEquals(1, sessions.add(second, List.of(carol)));
    }

    @Test
    void implicitCommandsChooseOwnGroupOrTheSoleGroupAndRejectAmbiguity() {
        assertThrows(IllegalArgumentException.class, () -> sessions.select(null));
        var first = sessions.create(alice, 1);
        assertSame(first, sessions.select(null));
        var second = sessions.create(bob, 2);
        sessions.add(second, List.of(carol));
        assertSame(first, sessions.select(alice));
        assertSame(second, sessions.select(carol));
        assertThrows(IllegalArgumentException.class, () -> sessions.select(null));
        assertThrows(IllegalArgumentException.class, () -> sessions.select(UUID.randomUUID()));
    }

    @Test
    void motionInputsActionsAndFollowStateAreIndependentBetweenBodies() {
        var first = sessions.create(alice, 1);
        var second = sessions.create(bob, 2);
        first.motionInputs.accept(alice, new Motion(5, Key.FORWARD.bit()), Control.ALL, 10);
        first.inputs.accept(alice, new Frame(0, Key.ATTACK.bit(), 0, 0, -1, 0), Control.ALL, 10);
        first.followConfirmed.put(alice, 3L);
        first.lastSeen.put(alice, 10L);
        first.followRevision = 3;
        assertEquals(0, second.motionInputs.held(10));
        assertEquals(Frame.EMPTY, second.inputs.drain(10));
        assertTrue(second.followConfirmed.isEmpty());
        assertTrue(second.lastSeen.isEmpty());
        assertEquals(0, second.followRevision);
        assertEquals(Key.FORWARD.bit(), first.motionInputs.held(10));
        assertEquals(Key.ATTACK.bit(), first.inputs.drain(10).clicks());
    }

    @Test
    void legacySingleBodyConfigurationMigratesWithoutLosingOwnersOrRecovery() throws Exception {
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("body", alice.toString());
        legacy.set("members", List.of(alice.toString(), bob.toString()));
        for (Control control : Control.values()) legacy.set("owners." + control.name(), bob.toString());
        legacy.set("recovery." + bob + ".mode", "SURVIVAL");
        sessions.load(legacy, () -> 10);
        var group = sessions.body(alice);
        assertEquals(Control.ALL, group.controls(bob));
        assertFalse(group.active);
        sessions.save(legacy);
        assertNull(legacy.getString("body"));
        assertEquals("SURVIVAL", legacy.getString("recovery." + bob + ".mode"));
        YamlConfiguration copy = new YamlConfiguration();
        copy.loadFromString(legacy.saveToString());
        Sessions reloaded = new Sessions();
        reloaded.load(copy, () -> 11);
        assertEquals(group.members, reloaded.body(alice).members);
        assertEquals(group.owners, reloaded.body(alice).owners);
    }

    @Test
    void allGroupsRoundTripAndNeverAutomaticallyResumeAfterRestart() throws Exception {
        var first = sessions.create(alice, 1);
        var second = sessions.create(bob, 2);
        sessions.add(second, List.of(carol));
        second.owners.put(Control.INVENTORY, carol);
        first.active = second.active = true;
        YamlConfiguration saved = new YamlConfiguration();
        sessions.save(saved);
        YamlConfiguration copy = new YamlConfiguration();
        copy.loadFromString(saved.saveToString());
        Sessions reloaded = new Sessions();
        reloaded.load(copy, new AtomicLong(20)::incrementAndGet);
        assertEquals(2, reloaded.all().size());
        assertFalse(reloaded.body(alice).active);
        assertFalse(reloaded.body(bob).active);
        assertSame(reloaded.body(bob), reloaded.member(carol));
        assertEquals(carol, reloaded.body(bob).owners.get(Control.INVENTORY));
        assertNotEquals(reloaded.body(alice).generation, reloaded.body(bob).generation);
    }

    @Test
    void invalidStateCannotAssignOnePlayerToTwoBodiesOrAnOwnerOutsideItsGroup() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("groups." + alice + ".members", List.of(alice.toString(), carol.toString()));
        yaml.set("groups." + bob + ".members", List.of(bob.toString(), carol.toString()));
        assertThrows(IllegalArgumentException.class, () -> sessions.load(yaml, () -> 1));
        YamlConfiguration badOwner = new YamlConfiguration();
        badOwner.set("body", alice.toString());
        badOwner.set("owners.LOOK", bob.toString());
        assertThrows(IllegalArgumentException.class, () -> new Sessions().load(badOwner, () -> 2));
        YamlConfiguration badGroups = new YamlConfiguration();
        badGroups.set("groups", "not a section");
        assertThrows(IllegalArgumentException.class, () -> new Sessions().load(badGroups, () -> 3));
    }
}
