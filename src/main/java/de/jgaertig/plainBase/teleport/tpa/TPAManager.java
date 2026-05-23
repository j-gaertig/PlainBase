package de.jgaertig.plainBase.teleport.tpa;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class TPAManager {

    private final PlainBase plugin;


    private final Map<UUID, TpaSession> activeSessions = new HashMap<>();
    private final Set<UUID> tpAutoPlayers = new HashSet<>();
    private final Map<UUID, ScheduledTask> activeWarmups = new HashMap<>();

    public enum RequestType { TPA, TPAHERE }

    private record TpaSession(UUID requesterId, RequestType type, ScheduledTask timeoutTask) {}

    public TPAManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public void sendRequest(Player requester, Player target, RequestType type) {

        if (activeSessions.containsKey(target.getUniqueId())) {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>This player already has a pending teleport request. Try again later."));
            return;
        }

        requester.sendMessage(plugin.getMiniMessage().deserialize("<gray>Teleport request sent to <yellow>" + target.getName() + "<gray>."));
        target.sendMessage(plugin.getMiniMessage().deserialize("<yellow>" + requester.getName() + " <gray>has sent you a teleport request."));

        if (tpAutoPlayers.contains(target.getUniqueId())) {
            startTeleportProcedure(requester, target, type);
            return;
        }

        String typeAction = (type == RequestType.TPA) ? "teleport to you" : "you teleport to them";
        target.sendMessage(plugin.getMiniMessage().deserialize(
                "<gray>They want to " + typeAction + ". You have <yellow>" +
                        plugin.getTeleportConfig().getLong("tpa.request_timeout", 300) + " <gray>seconds to respond.\n" +
                        "<gray>Use <green>/tpaccept <gray>or <red>/tpdeny<gray>."
        ));

        long seconds = plugin.getTeleportConfig().getLong("tpa.request_timeout", 300);
        ScheduledTask timeoutTask = Bukkit.getAsyncScheduler().runDelayed(plugin, (t) -> {
            if (activeSessions.containsKey(target.getUniqueId())) {
                expireRequest(target.getUniqueId());
            }
        }, seconds, TimeUnit.SECONDS);

        activeSessions.put(target.getUniqueId(), new TpaSession(requester.getUniqueId(), type, timeoutTask));
    }

    public void acceptRequest(Player target) {
        TpaSession session = activeSessions.get(target.getUniqueId());
        if (session == null) {
            target.sendMessage(plugin.getMiniMessage().deserialize("<red>You don't have any pending requests!"));
            return;
        }

        Player requester = Bukkit.getPlayer(session.requesterId());
        if (requester != null) {
            startTeleportProcedure(requester, target, session.type());
        }

        clearSession(target.getUniqueId());
    }

    public void denyRequest(Player target) {
        TpaSession session = activeSessions.get(target.getUniqueId());
        if (session == null) {
            target.sendMessage(plugin.getMiniMessage().deserialize("<red>You don't have any pending requests!"));
            return;
        }

        Player requester = Bukkit.getPlayer(session.requesterId());
        if (requester != null) {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>" + target.getName() + " denied your teleport request."));
        }
        target.sendMessage(plugin.getMiniMessage().deserialize("<red>Request denied."));

        clearSession(target.getUniqueId());
    }

    public void toggleTpAuto(Player player) {
        UUID uuid = player.getUniqueId();
        boolean newStatus;
        if (tpAutoPlayers.contains(uuid)) {
            tpAutoPlayers.remove(uuid);
            player.sendMessage(plugin.getMiniMessage().deserialize("<gray>TP-Auto <red>disabled<gray>."));
            newStatus = false;
        } else {
            tpAutoPlayers.add(uuid);
            player.sendMessage(plugin.getMiniMessage().deserialize("<gray>TP-Auto <green>enabled<gray>."));
            newStatus = true;
            if (activeSessions.containsKey(uuid)) acceptRequest(player);
        }
        savePlayerData(uuid, newStatus);
    }

    private void expireRequest(UUID targetId) {
        TpaSession session = activeSessions.remove(targetId);
        if (session == null) return;

        Player target = Bukkit.getPlayer(targetId);
        Player requester = Bukkit.getPlayer(session.requesterId());

        if (target != null) target.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request expired."));
        if (requester != null) requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request to " + (target != null ? target.getName() : "player") + " expired."));
    }

    private void clearSession(UUID targetId) {
        TpaSession session = activeSessions.remove(targetId);
        if (session != null && session.timeoutTask() != null) {
            session.timeoutTask().cancel();
        }
    }

    private void startTeleportProcedure(Player requester, Player target, RequestType type) {

        String msg = "<green>Request accepted! Teleportation starting...";
        requester.sendMessage(plugin.getMiniMessage().deserialize(msg));
        target.sendMessage(plugin.getMiniMessage().deserialize(msg));

        Player toTeleport = (type == RequestType.TPA) ? requester : target;
        Player destination = (type == RequestType.TPA) ? target : requester;

        long seconds = plugin.getTeleportConfig().getLong("tpa.counter.seconds", 3);

        if (!plugin.getTeleportConfig().getBoolean("tpa.counter.enabled", true) || seconds <= 0) {
            performTeleport(toTeleport, destination);
            return;
        }

        toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<gray>Teleporting in <yellow>" + seconds + " <gray>seconds. Do not move!"));

        ScheduledTask warmupTask = Bukkit.getRegionScheduler().runDelayed(plugin, toTeleport.getLocation(), (task) -> {
            activeWarmups.remove(toTeleport.getUniqueId());
            performTeleport(toTeleport, destination);
        }, seconds * 20L);

        activeWarmups.put(toTeleport.getUniqueId(), warmupTask);
    }

    private void performTeleport(Player p, Player target) {
        if (p == null || !p.isOnline() || target == null || !target.isOnline()) return;

        p.teleportAsync(target.getLocation()).thenAccept(success -> {
            if (success) {
                p.sendMessage(plugin.getMiniMessage().deserialize("<green>Teleport successful!"));
            }
        });
    }

    public void cancelWarmup(Player p, String reason) {
        ScheduledTask task = activeWarmups.remove(p.getUniqueId());
        if (task != null) {
            task.cancel();
            p.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport cancelled: " + reason));
        }
    }

    public void cancelOutgoingRequest(Player requester) {

        UUID targetUUID = null;
        for (Map.Entry<UUID, TpaSession> entry : activeSessions.entrySet()) {
            if (entry.getValue().requesterId().equals(requester.getUniqueId())) {
                targetUUID = entry.getKey();
                break;
            }
        }

        if (targetUUID != null) {
            clearSession(targetUUID);
            requester.sendMessage(plugin.getMiniMessage().deserialize("<gray>Your teleport request has been <red>cancelled<gray>."));

            Player target = Bukkit.getPlayer(targetUUID);
            if (target != null) {
                target.sendMessage(plugin.getMiniMessage().deserialize("<yellow>" + requester.getName() + " <gray>cancelled their teleport request."));
            }
        } else {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>You don't have any outgoing requests!"));
        }
    }

    private File getPlayerDataFile(UUID uuid) {
        File folder = new File(plugin.getDataFolder(), "data/playerdata");
        if (!folder.exists()) folder.mkdirs();
        return new File(folder, uuid.toString() + ".yml");
    }

    public void loadPlayerData(Player player) {
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            File file = getPlayerDataFile(player.getUniqueId());
            if (!file.exists()) return;

            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            if (config.getBoolean("tpauto", false)) {
                tpAutoPlayers.add(player.getUniqueId());
            }
        });
    }

    private void savePlayerData(UUID uuid, boolean tpAutoStatus) {
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            File file = getPlayerDataFile(uuid);
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);

            config.set("tpauto", tpAutoStatus);
            // Hier können später weitere Werte mit config.set(...) hinzugefügt werden

            try {
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save player data for " + uuid);
            }
        });
    }
}