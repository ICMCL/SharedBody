package dev.sharedbody.paper;

import dev.sharedbody.common.Control;
import dev.sharedbody.common.Frame;
import dev.sharedbody.common.Look;
import dev.sharedbody.common.Motion;
import dev.sharedbody.common.Follow;
import dev.sharedbody.common.Wire;
import dev.sharedbody.common.Vitals;
import dev.sharedbody.paper.Sessions.Group;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class SharedBodyPlugin extends JavaPlugin implements Listener, TabExecutor, PluginMessageListener {
    private final Sessions sessions = new Sessions();
    private final BridgeLimiter limiter = new BridgeLimiter();
    private final Map<UUID, SavedPlayer> recovery = new HashMap<>();
    private final Set<UUID> ready = new LinkedHashSet<>();
    private long generation = System.currentTimeMillis();
    private long tick;
    private File stateFile;

    @Override
    public void onEnable() {
        stateFile = new File(getDataFolder(), "state.yml");
        try {
            loadState();
        } catch (IOException | IllegalArgumentException error) {
            getLogger().log(Level.SEVERE, "Cannot load SharedBody state; refusing to overwrite recovery data", error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getMessenger().registerIncomingPluginChannel(this, Wire.CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, Wire.CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        var command = Objects.requireNonNull(getCommand("sharedbody"));
        command.setExecutor(this);
        command.setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tick++;
            for (Group group : sessions.all()) tickSession(group);
        }, 1, 1);
        for (Player player : Bukkit.getOnlinePlayers()) restore(player);
        getLogger().info("SharedBody enabled for Paper 26.3. Every participant needs the matching Fabric client mod.");
    }

    @Override
    public void onDisable() {
        for (Group group : sessions.all()) if (group.active) stop(group, "插件关闭，已解除共享身体。");
    }

    private void loadState() throws IOException {
        if (!stateFile.exists()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(stateFile);
        } catch (org.bukkit.configuration.InvalidConfigurationException error) {
            throw new IOException("Invalid state.yml", error);
        }
        sessions.load(yaml, () -> ++generation);
        ConfigurationSection section = yaml.getConfigurationSection("recovery");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                Location location = section.getLocation(id + ".location");
                String mode = section.getString(id + ".mode");
                if (location == null || location.getWorld() == null || mode == null) {
                    throw new IOException("Missing recovery location/world/mode for " + id);
                }
                recovery.put(UUID.fromString(id), new SavedPlayer(location, GameMode.valueOf(mode)));
            }
        }
    }

    private void saveState() {
        YamlConfiguration yaml = new YamlConfiguration();
        sessions.save(yaml);
        recovery.forEach((id, saved) -> {
            yaml.set("recovery." + id + ".location", saved.location());
            yaml.set("recovery." + id + ".mode", saved.mode().name());
        });
        try {
            Files.createDirectories(stateFile.toPath().getParent());
            var temporary = stateFile.toPath().resolveSibling("state.yml.tmp");
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                getLogger().warning("Filesystem does not support atomic state replacement; using regular replacement.");
                Files.move(temporary, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            getLogger().log(Level.SEVERE, "Cannot persist SharedBody state", error);
            throw new IllegalStateException("共享身体状态保存失败，请检查插件目录写权限。", error);
        }
    }

    private void tickSession(Group group) {
        if (!group.active) return;
        Player host = Bukkit.getPlayer(group.body);
        if (host == null || host.isDead()) {
            stop(group, "身体死亡或离线，共享已停止。");
            return;
        }
        if (!host.getWorld().getUID().equals(group.bodyWorld)) beginTravel(group, host);
        for (UUID id : group.members) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || (tick > group.travelDeadline && tick - group.lastSeen.getOrDefault(id, tick) > 60)) {
                stop(group, "参与者离线或客户端桥接超时，共享已停止。");
                return;
            }
            if (id.equals(group.body)) continue;
            if (player.getWorld() != host.getWorld()) {
                player.setSpectatorTarget(null);
                if (!player.teleport(host.getLocation())) {
                    stop(group, "跨维度跟随被其他插件拒绝，共享已停止。");
                    return;
                }
                group.followConfirmed.remove(id);
            }
            if (player.getGameMode() != GameMode.SPECTATOR) {
                stop(group, "参与者游戏模式被修改，共享已停止。");
                return;
            }
            if (player.getSpectatorTarget() != host) player.setSpectatorTarget(host);
            if (tick % 10 == 0) follow(group, player, host);
            if (tick > group.travelDeadline && !Objects.equals(group.followConfirmed.get(id), group.followRevision)) {
                stop(group, "客户端未能在 30 秒内加载身体所在世界并恢复附身，共享已停止。");
                return;
            }
        }
        Frame merged = group.inputs.drain(tick);
        send(host, Wire.packet(Wire.MERGED, group.generation, out -> Wire.writeFrame(out, merged)));
        publishMotion(group, tick % 2 == 0);
    }

    private void publishMotion(Group group, boolean force) {
        int held = group.motionInputs.held(tick);
        if (!force && held == group.publishedMotion) return;
        group.publishedMotion = held;
        Motion motion = new Motion(++group.motionSequence, held);
        byte[] packet = Wire.packet(Wire.MOTION, group.generation, motion::write);
        broadcast(group, packet, null);
    }

    private void follow(Group group, Player player, Player host) {
        Follow target = new Follow(group.followRevision, host.getWorld().getKey().asString(), host.getEntityId());
        send(player, Wire.packet(Wire.FOLLOW, group.generation, target::write));
    }

    private void beginTravel(Group group, Player host) {
        group.bodyWorld = host.getWorld().getUID();
        group.followRevision++;
        group.travelDeadline = tick + 600;
        group.followConfirmed.clear();
        group.motionInputs.clear();
        group.inputs.clear();
        group.publishedMotion = -1;
        for (UUID id : group.members) {
            if (id.equals(group.body)) continue;
            Player member = Bukkit.getPlayer(id);
            if (member != null && member.getGameMode() == GameMode.SPECTATOR) member.setSpectatorTarget(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void beforeTeleport(PlayerTeleportEvent event) {
        Group group = sessions.body(event.getPlayer().getUniqueId());
        if (group == null || !group.active
                || event.getTo().getWorld().equals(event.getFrom().getWorld())) return;
        for (UUID id : group.members) {
            Player member = Bukkit.getPlayer(id);
            if (member != null && !id.equals(group.body) && member.getGameMode() == GameMode.SPECTATOR) {
                member.setSpectatorTarget(null);
            }
        }
        group.travelDeadline = tick + 600;
    }

    @EventHandler
    public void changedWorld(PlayerChangedWorldEvent event) {
        Group group = sessions.member(event.getPlayer().getUniqueId());
        if (group == null || !group.active) return;
        if (event.getPlayer().getUniqueId().equals(group.body)) beginTravel(group, event.getPlayer());
        else {
            group.followConfirmed.remove(event.getPlayer().getUniqueId());
            group.travelDeadline = tick + 600;
        }
    }

    @EventHandler
    public void worldLoaded(PlayerClientLoadedWorldEvent event) {
        Group group = sessions.member(event.getPlayer().getUniqueId());
        if (group == null || !group.active) return;
        Player player = event.getPlayer();
        group.lastSeen.put(player.getUniqueId(), tick);
        if (!player.getUniqueId().equals(group.body)) {
            Player host = Bukkit.getPlayer(group.body);
            if (host != null) follow(group, player, host);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!Wire.CHANNEL.equals(channel)) return;
        UUID id = player.getUniqueId();
        Group group = sessions.member(id);
        BridgeLimiter.Decision decision = limiter.check(id, tick);
        if (decision != BridgeLimiter.Decision.ACCEPT) {
            if (decision == BridgeLimiter.Decision.WARN) {
                getLogger().warning("Bridge rate limit exceeded by " + player.getName() + "; excess messages dropped, session continues.");
                player.sendActionBar(Component.text("共享输入过密，已丢弃超额消息；共享继续运行。", NamedTextColor.YELLOW));
            }
            if (data.length < 9 || data.length > Wire.MAX_BYTES) return;
        }
        try (DataInputStream in = Wire.reader(data)) {
            int kind = in.readUnsignedByte();
            long epoch = in.readLong();
            if (group != null && group.active && epoch == group.generation) group.lastSeen.put(id, tick);
            if (decision != BridgeLimiter.Decision.ACCEPT) return;
            if (kind == Wire.HELLO) {
                int version = in.readInt();
                Wire.end(in);
                if (version != Wire.VERSION) throw new IOException("Incompatible client bridge version: " + version);
                ready.add(id);
                state(group, player);
                return;
            }
            if (group == null || !group.active || epoch != group.generation) return;
            switch (kind) {
                case Wire.INPUT -> {
                    Frame frame = Wire.readFrame(in);
                    Wire.end(in);
                    Frame buttons = new Frame(frame.held() & ~Motion.MASK, frame.clicks() & ~Motion.MASK,
                            0, 0, frame.hotbar(), frame.scroll());
                    group.inputs.accept(id, buttons, group.controls(id), tick);
                }
                case Wire.MOTION -> {
                    Motion motion = Motion.read(in);
                    Wire.end(in);
                    if (group.motionInputs.accept(id, motion, group.controls(id), tick)) publishMotion(group, false);
                }
                case Wire.HEARTBEAT -> {
                    Wire.end(in);
                }
                case Wire.FOLLOW_ACK -> {
                    Follow target = Follow.read(in);
                    Wire.end(in);
                    Player host = Objects.requireNonNull(Bukkit.getPlayer(group.body));
                    if (!id.equals(group.body) && target.revision() == group.followRevision
                            && target.matches(host.getWorld().getKey().asString(), host.getEntityId())
                            && player.getWorld().equals(host.getWorld())) group.followConfirmed.put(id, group.followRevision);
                }
                case Wire.LOOK -> {
                    Look look = Look.read(in);
                    Wire.end(in);
                    if (!id.equals(group.owners.get(Control.LOOK))) throw new IOException("Only the look owner may rotate the body");
                    if (look.sequence() <= group.lookSequence) return;
                    group.lookSequence = look.sequence();
                    broadcast(group, data, id);
                }
                case Wire.SCREEN_ACTION -> {
                    Wire.ScreenAction action = Wire.ScreenAction.read(in);
                    Wire.end(in);
                    if (id.equals(group.owners.get(Control.INVENTORY))) {
                        send(Objects.requireNonNull(Bukkit.getPlayer(group.body)),
                                Wire.packet(Wire.SCREEN_ACTION, group.generation, action::write));
                    }
                }
                case Wire.SCREEN -> {
                    if (!id.equals(group.body)) throw new IOException("Only the body may publish its screen");
                    UUID owner = group.owners.get(Control.INVENTORY);
                    Player viewer = owner == null || owner.equals(group.body) ? null : Bukkit.getPlayer(owner);
                    if (viewer != null) send(viewer, data);
                }
                case Wire.VISUAL -> {
                    if (!id.equals(group.body)) throw new IOException("Only the body may publish its inventory");
                    broadcast(group, data, group.body);
                }
                case Wire.CAMERA -> {
                    int camera = in.readUnsignedByte();
                    Wire.end(in);
                    if (!id.equals(group.body) || camera > 2) throw new IOException("Invalid camera update");
                    broadcast(group, data, null);
                }
                case Wire.VITALS -> {
                    if (!id.equals(group.body)) throw new IOException("Only the body may publish its vitals");
                    in.readUTF();
                    Vitals.read(in);
                    Wire.end(in);
                    broadcast(group, data, group.body);
                }
                case Wire.ERROR -> {
                    String message = in.readUTF();
                    Wire.end(in);
                    getLogger().severe("Client bridge error from " + player.getName() + ": " + message);
                    stop(group, "客户端桥接报错，共享已停止；详情见服务端日志。");
                }
                default -> throw new IOException("Unexpected bridge message " + kind);
            }
        } catch (IOException | IllegalArgumentException error) {
            getLogger().log(Level.WARNING, "Invalid bridge packet from " + player.getName(), error);
            player.sendMessage(Component.text("SharedBody 客户端协议错误，请安装匹配版本。", NamedTextColor.RED));
            if (group != null && group.active) stop(group, "客户端协议异常，共享已停止。");
        }
    }

    private void send(Player player, byte[] data) {
        player.sendPluginMessage(this, Wire.CHANNEL, data);
    }

    private void broadcast(Group group, byte[] data, UUID excluded) {
        for (UUID id : group.members) {
            if (id.equals(excluded)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) send(player, data);
        }
    }

    private void state(Group group, Player player) {
        boolean participating = group != null && group.active && group.members.contains(player.getUniqueId());
        send(player, Wire.packet(Wire.STATE, group == null ? generation : group.generation, out -> {
            out.writeBoolean(participating);
            out.writeBoolean(participating && player.getUniqueId().equals(group.body));
            out.writeInt(participating ? group.controls(player.getUniqueId()) : 0);
            if (participating) {
                Player host = Objects.requireNonNull(Bukkit.getPlayer(group.body));
                out.writeLong(group.body.getMostSignificantBits());
                out.writeLong(group.body.getLeastSignificantBits());
                new Look(0, Look.wrap(host.getYaw()), Math.clamp(host.getPitch(), -90, 90)).write(out);
            }
        }));
    }

    private void start(Group group) {
        require(!group.active, "该组共享已经启动。");
        require(group.members.stream().noneMatch(recovery::containsKey), "本组还有玩家等待恢复，请先让他们上线。");
        Player host = Bukkit.getPlayer(group.body);
        require(host != null && !host.isDead() && host.getGameMode() != GameMode.SPECTATOR, "身体必须在线、存活，且不是旁观者。");
        for (Control control : Control.values()) {
            require(group.owners.containsKey(control), "未分配：" + control.label);
            require(group.members.contains(group.owners.get(control)), "功能负责人不是成员：" + control.label);
        }
        for (UUID id : group.members) {
            Player member = Bukkit.getPlayer(id);
            require(member != null && !member.isDead(), "所有成员必须在线且存活：" + name(id));
            require(ready.contains(id), name(id) + " 尚未安装/连接 SharedBody 客户端模组。");
            require(!member.isInsideVehicle(), name(id) + " 必须先离开载具。");
        }
        for (UUID id : group.members) {
            if (!id.equals(group.body)) recovery.put(id, SavedPlayer.capture(Objects.requireNonNull(Bukkit.getPlayer(id))));
        }
        saveState();
        group.generation = ++generation;
        group.lookSequence = 0;
        group.inputs.clear();
        group.motionInputs.clear();
        group.motionSequence = 0;
        group.publishedMotion = -1;
        beginTravel(group, Objects.requireNonNull(host));
        group.lastSeen.clear();
        group.active = true;
        for (UUID id : group.members) {
            Player player = Objects.requireNonNull(Bukkit.getPlayer(id));
            group.lastSeen.put(id, tick);
            state(group, player);
            if (!id.equals(group.body)) {
                player.setGameMode(GameMode.SPECTATOR);
                if (!player.teleport(Objects.requireNonNull(host).getLocation())) {
                    stop(group, "跟随传送被其他插件拒绝，共享已停止。");
                    throw new IllegalArgumentException("跟随传送失败。");
                }
                player.setSpectatorTarget(host);
                follow(group, player, host);
            }
        }
        announce(group, "共享身体已启动；/sb stop 可随时退出本组。");
    }

    private void stop(Group group, String reason) {
        group.active = false;
        group.generation = ++generation;
        group.inputs.clear();
        group.motionInputs.clear();
        group.followConfirmed.clear();
        for (UUID id : group.members) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                state(group, player);
                restore(player);
                player.updateInventory();
            }
        }
        saveState();
        announce(group, reason);
    }

    private void restore(Player player) {
        SavedPlayer saved = recovery.get(player.getUniqueId());
        if (saved == null) return;
        if (player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
        player.setGameMode(saved.mode());
        if (!player.teleport(saved.location())) {
            getLogger().severe("Recovery teleport rejected for " + player.getName());
            player.sendMessage(Component.text("恢复传送失败，请联系管理员；恢复记录已保留。", NamedTextColor.RED));
            return;
        }
        recovery.remove(player.getUniqueId());
        saveState();
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(this, () -> restore(event.getPlayer()));
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        Group group = sessions.member(event.getPlayer().getUniqueId());
        if (group != null && group.active) stop(group, "成员离线，共享已停止。");
        ready.remove(event.getPlayer().getUniqueId());
        limiter.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void death(PlayerDeathEvent event) {
        Group group = sessions.body(event.getEntity().getUniqueId());
        if (group != null && group.active) stop(group, "身体死亡，共享已停止。");
    }

    private void announce(Group group, String message) {
        message = "[身体 " + name(group.body) + "] " + message;
        getLogger().info(message);
        for (UUID id : group.members) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(Component.text(message, NamedTextColor.AQUA));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String name(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "settings" : args[0].toLowerCase(java.util.Locale.ROOT);
        try {
            if (action.equals("status")) {
                require(args.length <= 2, "用法：/sb status [主体]。");
                if (args.length < 2) {
                    if (sessions.all().isEmpty()) sender.sendMessage(Component.text("尚未创建共享身体。"));
                    else for (Group group : sessions.all()) status(sender, group, sessions.all().size() == 1);
                } else status(sender, selectGroup(sender, args[1]), true);
                return true;
            }
            if (action.equals("stop")) {
                require(args.length <= 2, "用法：/sb stop [主体]。");
                Group group = selectGroup(sender, args.length == 2 ? args[1] : null);
                require(sender.hasPermission("sharedbody.admin")
                        || sender instanceof Player player && group.members.contains(player.getUniqueId()),
                        "只能停止自己参与的组；管理其他组需要 sharedbody.admin 权限。");
                require(group.active, "该组共享尚未启动。");
                stop(group, sender.getName() + " 停止了共享身体。");
                return true;
            }
            require(sender.hasPermission("sharedbody.admin"), "需要 sharedbody.admin 权限。");
            switch (action) {
                case "create" -> {
                    require(args.length <= 2, "用法：/sb create [在线主体玩家]。");
                    Player host = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                    require(host != null, "用法：/sb create <在线玩家>。");
                    UUID body = Objects.requireNonNull(host).getUniqueId();
                    require(!recovery.containsKey(body), "该玩家尚有待恢复记录，请先完成恢复。");
                    sessions.create(body, ++generation);
                    saveState();
                    sender.sendMessage(Component.text("身体 " + host.getName() + " 已创建；/sb add "
                            + host.getName() + " <玩家或选择器>，然后 /sb settings " + host.getName() + " 分工。", NamedTextColor.GREEN));
                }
                case "add", "remove" -> {
                    require(args.length == 2 || args.length == 3, "用法：/sb " + action + " [主体] <玩家"
                            + (action.equals("add") ? "或选择器" : "") + ">。");
                    Group group = selectGroup(sender, args.length == 3 ? args[1] : null);
                    require(!group.active, "修改成员前必须停止该组共享。");
                    String target = args[args.length - 1];
                    if (action.equals("add")) {
                        addPlayers(sender, group, target);
                    } else {
                        UUID id = group.members.stream().filter(member -> name(member).equalsIgnoreCase(target)).findFirst().orElse(null);
                        require(id != null, "该玩家不属于此主体。");
                        sessions.remove(group, id);
                        saveState();
                        sender.sendMessage(Component.text("已从主体 " + name(group.body) + " 移除 " + name(id) + "。", NamedTextColor.GREEN));
                    }
                }
                case "settings", "setting", "gui" -> {
                    require(args.length <= 2, "用法：/sb settings [主体]。");
                    require(sender instanceof Player, "GUI 只能由玩家打开。");
                    Group group = selectGroup(sender, args.length == 2 ? args[1] : null);
                    require(!group.active, "修改分工前必须停止该组共享。");
                    openSettings((Player) sender, group);
                }
                case "start" -> {
                    require(args.length <= 2, "用法：/sb start [主体]。");
                    Group group = selectGroup(sender, args.length == 2 ? args[1] : null);
                    start(group);
                    if (!(sender instanceof Player player) || !group.members.contains(player.getUniqueId())) {
                        sender.sendMessage(Component.text("主体 " + name(group.body) + " 已启动。", NamedTextColor.GREEN));
                    }
                }
                case "dissolve" -> {
                    require(args.length <= 2, "用法：/sb dissolve [主体]。");
                    Group group = selectGroup(sender, args.length == 2 ? args[1] : null);
                    require(!group.active, "请先 /sb stop " + name(group.body) + "，再删除该组配置。");
                    require(group.members.stream().noneMatch(recovery::containsKey), "该组还有待恢复玩家，请先完成恢复。");
                    sessions.dissolve(group);
                    saveState();
                    sender.sendMessage(Component.text("主体 " + name(group.body) + " 的配置已删除；其他组不受影响。", NamedTextColor.GREEN));
                }
                default -> sender.sendMessage(Component.text("/sb create [主体] | add [主体] <玩家或选择器> | remove [主体] <玩家> | settings/start/stop/status/dissolve [主体]"));
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            sender.sendMessage(Component.text(error.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private Group selectGroup(CommandSender sender, String body) {
        if (body != null) {
            Player online = Bukkit.getPlayerExact(body);
            if (online != null) {
                Group group = sessions.body(online.getUniqueId());
                require(group != null, "该玩家不是主体：" + body + "；/sb status 查看所有主体。");
                return group;
            }
            for (Group group : sessions.all()) if (name(group.body).equalsIgnoreCase(body)) return group;
            throw new IllegalArgumentException("未找到主体：" + body + "；/sb status 查看所有主体。");
        }
        return sessions.select(sender instanceof Player player ? player.getUniqueId() : null);
    }

    private void status(CommandSender sender, Group group, boolean details) {
        sender.sendMessage(Component.text("身体：" + name(group.body) + "；状态：" + (group.active ? "运行" : "停止")
                + "；成员：" + group.members.size(), NamedTextColor.AQUA));
        if (details) for (Control control : Control.values()) {
            UUID owner = group.owners.get(control);
            sender.sendMessage(Component.text(control.label + " → " + (owner == null ? "未分配" : name(owner))));
        }
    }

    private void addPlayers(CommandSender sender, Group group, String target) {
        Set<UUID> selected = new LinkedHashSet<>();
        if (target.startsWith("@")) {
            var entities = Bukkit.selectEntities(sender, target);
            require(entities.stream().allMatch(entity -> entity instanceof Player), "请选择玩家，例如 @a、@p 或 @a[distance=..10]。");
            for (var entity : entities) selected.add(entity.getUniqueId());
        } else {
            Player player = Bukkit.getPlayerExact(target);
            require(player != null, "玩家不在线。");
            selected.add(Objects.requireNonNull(player).getUniqueId());
        }
        require(!selected.isEmpty(), "没有匹配到在线玩家。");
        List<UUID> available = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (UUID id : selected) {
            Group other = sessions.member(id);
            if (other != null && other != group) skipped.add(name(id) + "（主体 " + name(other.body) + "）");
            else {
                require(!recovery.containsKey(id), name(id) + " 尚有待恢复记录，请先完成恢复。");
                available.add(id);
            }
        }
        int added = sessions.add(group, available);
        if (added > 0) saveState();
        sender.sendMessage(Component.text("主体 " + name(group.body) + "：新增 " + added + " 人；已在本组 "
                + (available.size() - added) + " 人；跳过其他组 " + skipped.size() + " 人。", NamedTextColor.GREEN));
        if (!skipped.isEmpty()) sender.sendMessage(Component.text("未转移其他组成员：" + String.join("、", skipped), NamedTextColor.YELLOW));
    }

    private void openSettings(Player player, Group group) {
        Menu menu = new Menu(group, null, 0, 27, "共享身体 " + name(group.body) + " · 功能分工");
        Material[] icons = {Material.SPYGLASS, Material.IRON_SWORD, Material.FISHING_ROD, Material.CHEST,
                Material.ENDER_EYE, Material.SHIELD, Material.LEATHER_BOOTS, Material.RABBIT_FOOT, Material.LEATHER_LEGGINGS};
        for (Control control : Control.values()) {
            UUID owner = group.owners.get(control);
            menu.inventory.setItem(9 + control.ordinal(), item(icons[control.ordinal()], control.label,
                    "独占者：" + (owner == null ? "未分配" : name(owner)), "点击选择负责人；同一人可以负责多项"));
        }
        menu.inventory.setItem(22, item(Material.LIME_DYE, "启动共享", "要求所有成员安装客户端模组"));
        player.openInventory(menu.inventory);
    }

    private void openOwners(Player player, Group group, Control control, int page) {
        Menu menu = new Menu(group, control, page, 54, name(group.body) + " · " + control.label + " · " + (page + 1));
        List<UUID> ids = menu.members;
        for (int i = 0; i < 45 && page * 45 + i < ids.size(); i++) {
            UUID id = ids.get(page * 45 + i);
            ItemStack head = item(Material.PLAYER_HEAD, name(id), ready.contains(id) ? "客户端桥接已就绪" : "客户端桥接未连接");
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            head.setItemMeta(meta);
            menu.inventory.setItem(i, head);
        }
        menu.inventory.setItem(45, item(Material.ARROW, "上一页"));
        menu.inventory.setItem(49, item(Material.BARRIER, "返回"));
        menu.inventory.setItem(53, item(Material.ARROW, "下一页"));
        player.openInventory(menu.inventory);
    }

    private static ItemStack item(Material material, String title, String... lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.GOLD));
        meta.lore(java.util.Arrays.stream(lore).map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Group group = menu.group;
        if (!player.hasPermission("sharedbody.admin") || !sessions.contains(group) || group.active) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        try {
            if (menu.control == null) {
                if (slot >= 9 && slot < 18) openOwners(player, group, Control.values()[slot - 9], 0);
                if (slot == 22) {
                    player.closeInventory();
                    start(group);
                }
            } else {
                List<UUID> ids = menu.members;
                int index = menu.page * 45 + slot;
                if (slot >= 0 && slot < 45 && index < ids.size()) {
                    require(group.members.contains(ids.get(index)), "该成员已被移除，请重新打开分工界面。");
                    group.owners.put(menu.control, ids.get(index));
                    saveState();
                    openSettings(player, group);
                } else if (slot == 49) openSettings(player, group);
                else if (slot == 45 && menu.page > 0) openOwners(player, group, menu.control, menu.page - 1);
                else if (slot == 53 && (menu.page + 1) * 45 < group.members.size()) openOwners(player, group, menu.control, menu.page + 1);
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            player.sendMessage(Component.text(error.getMessage(), NamedTextColor.RED));
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> candidates = new ArrayList<>();
        String action = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        if (args.length == 1) candidates.addAll(List.of("create", "add", "remove", "settings", "start", "stop", "status", "dissolve"));
        else if (args.length == 2) {
            if (action.equals("create") || action.equals("add")) {
                candidates.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            }
            if (!action.equals("create")) candidates.addAll(sessions.all().stream().map(group -> name(group.body)).toList());
            if (action.equals("add")) candidates.addAll(List.of("@a", "@p", "@r", "@s"));
            if (action.equals("remove")) {
                Group own = sender instanceof Player player ? sessions.member(player.getUniqueId()) : null;
                if (own == null && sessions.all().size() == 1) own = sessions.all().iterator().next();
                if (own != null) candidates.addAll(own.members.stream().map(SharedBodyPlugin::name).toList());
            }
        } else if (args.length == 3 && (action.equals("add") || action.equals("remove"))) {
            if (action.equals("add")) {
                candidates.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                candidates.addAll(List.of("@a", "@p", "@r", "@s"));
            } else for (Group group : sessions.all()) {
                if (name(group.body).equalsIgnoreCase(args[1])) candidates.addAll(group.members.stream().map(SharedBodyPlugin::name).toList());
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        return candidates.stream().distinct().filter(value -> value.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).toList();
    }

    private record SavedPlayer(Location location, GameMode mode) {
        static SavedPlayer capture(Player player) { return new SavedPlayer(player.getLocation(), player.getGameMode()); }
    }

    private static final class Menu implements InventoryHolder {
        final Group group;
        final List<UUID> members;
        final Control control;
        final int page;
        final Inventory inventory;
        Menu(Group group, Control control, int page, int size, String title) {
            this.group = group;
            this.members = List.copyOf(group.members);
            this.control = control;
            this.page = page;
            inventory = Bukkit.createInventory(this, size, Component.text(title));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
