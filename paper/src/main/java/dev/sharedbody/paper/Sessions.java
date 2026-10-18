package dev.sharedbody.paper;

import dev.sharedbody.common.Control;
import dev.sharedbody.common.Inputs;
import dev.sharedbody.common.MotionInputs;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

final class Sessions {
    static final int MAX_MEMBERS = 128;
    private final Map<UUID, Group> bodies = new LinkedHashMap<>();
    private final Map<UUID, Group> membership = new HashMap<>();

    static final class Group {
        final UUID body;
        final Set<UUID> members = new LinkedHashSet<>();
        final EnumMap<Control, UUID> owners = new EnumMap<>(Control.class);
        final Map<UUID, Long> lastSeen = new HashMap<>();
        final Map<UUID, Long> followConfirmed = new HashMap<>();
        final Inputs inputs = new Inputs();
        final MotionInputs motionInputs = new MotionInputs();
        boolean active;
        long generation;
        long lookSequence;
        long motionSequence;
        int publishedMotion = -1;
        UUID bodyWorld;
        long followRevision;
        long travelDeadline;

        Group(UUID body, long generation) {
            this.body = body;
            this.generation = generation;
            members.add(body);
            for (Control control : Control.values()) owners.put(control, body);
        }

        int controls(UUID player) {
            int mask = 0;
            for (Control control : Control.values()) {
                if (player.equals(owners.get(control))) mask |= control.bit();
            }
            return mask;
        }
    }

    Collection<Group> all() { return bodies.values(); }
    Group body(UUID id) { return bodies.get(id); }
    Group member(UUID id) { return membership.get(id); }
    boolean contains(Group group) { return bodies.get(group.body) == group; }

    Group select(UUID player) {
        Group own = membership.get(player);
        if (own != null) return own;
        require(!bodies.isEmpty(), "请先 /sb create <在线主体玩家>。");
        require(bodies.size() == 1, "存在多个主体，请明确指定主体玩家；/sb status 查看列表。");
        return bodies.values().iterator().next();
    }

    Group create(UUID body, long generation) {
        require(!membership.containsKey(body), "该玩家已有主体或已加入其他组，请先 remove / dissolve。");
        Group group = new Group(body, generation);
        bodies.put(body, group);
        membership.put(body, group);
        return group;
    }

    int add(Group group, Collection<UUID> players) {
        editable(group);
        Set<UUID> additions = new LinkedHashSet<>(players);
        additions.removeAll(group.members);
        require(group.members.size() + additions.size() <= MAX_MEMBERS, "每组成员上限为 128（GUI 支持分页）。");
        for (UUID player : additions) require(!membership.containsKey(player), "玩家已经属于其他主体。");
        for (UUID player : additions) {
            group.members.add(player);
            membership.put(player, group);
        }
        return additions.size();
    }

    void remove(Group group, UUID player) {
        editable(group);
        require(!group.body.equals(player), "不能移除身体，请先 stop，再 dissolve 删除主体。");
        require(group.members.contains(player), "该玩家不属于此主体。");
        group.members.remove(player);
        membership.remove(player);
        group.owners.replaceAll((control, owner) -> owner.equals(player) ? group.body : owner);
    }

    void dissolve(Group group) {
        editable(group);
        for (UUID player : group.members) membership.remove(player);
        bodies.remove(group.body);
    }

    private void editable(Group group) {
        require(contains(group), "主体配置已删除，请重新选择。");
        require(!group.active, "修改配置前必须停止该组共享。");
    }

    void load(ConfigurationSection root, LongSupplier generation) {
        require(bodies.isEmpty(), "不能覆盖已加载的共享身体配置。");
        ConfigurationSection saved = root.getConfigurationSection("groups");
        if (saved != null) {
            for (String body : saved.getKeys(false)) {
                ConfigurationSection section = saved.getConfigurationSection(body);
                require(section != null, "主体配置必须是配置节：" + body);
                loadGroup(UUID.fromString(body), section, generation.getAsLong());
            }
        } else {
            require(!root.contains("groups"), "groups 必须是配置节。");
            String body = root.getString("body");
            if (body != null) loadGroup(UUID.fromString(body), root, generation.getAsLong());
        }
    }

    private void loadGroup(UUID body, ConfigurationSection section, long generation) {
        Group group = create(body, generation);
        add(group, section.getStringList("members").stream().map(UUID::fromString).toList());
        group.owners.clear();
        for (Control control : Control.values()) {
            String owner = section.getString("owners." + control.name());
            if (owner == null) continue;
            UUID id = UUID.fromString(owner);
            require(group.members.contains(id), "分工负责人不属于主体：" + body + " / " + control.name());
            group.owners.put(control, id);
        }
    }

    void save(ConfigurationSection root) {
        root.set("body", null);
        root.set("members", null);
        root.set("owners", null);
        ConfigurationSection saved = root.createSection("groups");
        for (Group group : bodies.values()) {
            ConfigurationSection section = saved.createSection(group.body.toString());
            section.set("members", group.members.stream().map(UUID::toString).toList());
            group.owners.forEach((control, owner) -> section.set("owners." + control.name(), owner.toString()));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
