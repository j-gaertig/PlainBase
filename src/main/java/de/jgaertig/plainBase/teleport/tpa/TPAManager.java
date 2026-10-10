package de.jgaertig.plainBase.teleport.tpa;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TPAManager {

    private final PlainBase plugin;


    private final Map<UUID, TpaSession> activeSessions = new ConcurrentHashMap<>();
    // tpAutoPlayers persists per-player tpauto flags in data/playerdata/<uuid>.yml
    // (see loadPlayerData/savePlayerData). Entries intentionally survive quit:
    // removal on quit would discard the persisted preference. A Set guarantees
    // no duplicate growth — add() is idempotent, so repeated toggles/joins
    // cannot accumulate entries.
    private final Set<UUID> tpAutoPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ScheduledTask> activeWarmups = new ConcurrentHashMap<>();

    public enum RequestType { TPA, TPAHERE }

    private record TpaSession(UUID requesterId, RequestType type, ScheduledTask timeoutTask) {}

    public TPAManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    /**
     * Escapes a user-controlled value (player name) so it can be safely
     * concatenated into a MiniMessage template BEFORE deserialization —
     * without this, a name like {@code <click:run_command:...>} would inject
     * formatting/click events (same pattern as ModerationCommandBase#esc and
     * TeamManager#msg).
     */
    private String esc(String s) {
        return plugin.getMiniMessage().escapeTags(s == null ? "" : s);
    }

    public void sendRequest(Player requester, Player target, RequestType type) {

        if (activeSessions.containsKey(target.getUniqueId())) {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>This player already has a pending teleport request. Try again later."));
            return;
        }

        for (TpaSession session : activeSessions.values()) {
            if (session.requesterId().equals(requester.getUniqueId())) {
                requester.sendMessage(plugin.getMiniMessage().deserialize("<red>You already have an outgoing teleport request! Use /tpacancel to cancel it."));
                return;
            }
        }

        requester.sendMessage(plugin.getMiniMessage().deserialize("<gray>Teleport request sent to <yellow>" + esc(target.getName()) + "<gray>."));
        target.sendMessage(plugin.getMiniMessage().deserialize("<yellow>" + esc(requester.getName()) + " <gray>has sent you a teleport request."));

        if (tpAutoPlayers.contains(target.getUniqueId())) {
            startTeleportProcedure(requester, target, type);
            return;
        }

        long seconds = plugin.getTeleportConfig().getLong("tpa.request_timeout", 300);
        if (seconds <= 0) seconds = 30;
        seconds = Math.max(5, Math.min(300, seconds));
        final long timeoutSeconds = seconds;

        String typeAction = (type == RequestType.TPA) ? "teleport to you" : "you teleport to them";
        target.sendMessage(plugin.getMiniMessage().deserialize(
                "<gray>They want to " + typeAction + ". You have <yellow>" +
                        timeoutSeconds + " <gray>seconds to respond.\n" +
                        "<gray>Use <green>/tpaccept <gray>or <red>/tpdeny<gray>."
        ));

        ScheduledTask timeoutTask = Bukkit.getAsyncScheduler().runDelayed(plugin, (t) -> {
            if (activeSessions.containsKey(target.getUniqueId())) {
                expireRequest(target.getUniqueId());
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

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
        } else {
            target.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
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
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>" + esc(target.getName()) + " denied your teleport request."));
        }
        target.sendMessage(plugin.getMiniMessage().deserialize("<red>Request denied."));

        clearSession(target.getUniqueId());
    }

    /**
     * Whether this player currently has TP-Auto (auto-accept incoming
     * teleport requests) enabled. Used by the %plainbase_teleport_tpa_autoaccept%
     * placeholder as well as internally.
     */
    public boolean isTpAutoEnabled(UUID uuid) {
        return tpAutoPlayers.contains(uuid);
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
        if (requester != null) requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request to " + (target != null ? esc(target.getName()) : "player") + " expired."));
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
        // Clamp like tpa.request_timeout above: negative/huge values must never
        // leak into the scheduler delay or the displayed countdown.
        seconds = Math.max(0, Math.min(30, seconds));

        if (!plugin.getTeleportConfig().getBoolean("tpa.counter.enabled", true) || seconds <= 0) {
            performTeleport(toTeleport, destination);
            return;
        }

        ScheduledTask old = activeWarmups.remove(toTeleport.getUniqueId());
        if (old != null) old.cancel();

        toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<gray>Teleporting in <yellow>" + seconds + " <gray>seconds. Do not move!"));

        ScheduledTask warmupTask = toTeleport.getScheduler().runDelayed(plugin, (task) -> {
            activeWarmups.remove(toTeleport.getUniqueId());
            performTeleport(toTeleport, destination);
        }, null, seconds * 20L);

        activeWarmups.put(toTeleport.getUniqueId(), warmupTask);
    }

    private void performTeleport(Player toTeleport, Player destination) {
        if (toTeleport == null || destination == null) return;
        if (!toTeleport.isOnline() || !destination.isOnline()) return;

        destination.getScheduler().run(plugin, t -> {
            if (!destination.isOnline() || !toTeleport.isOnline()) return;
            Location destLoc = destination.getLocation().clone();
            if (destLoc.getWorld() == null) return;
            toTeleport.getScheduler().run(plugin, t2 -> {
                if (!toTeleport.isOnline() || !destination.isOnline()) return;
                toTeleport.teleportAsync(destLoc).thenAccept(success -> {
                    if (success) {
                        toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<green>Teleport successful!"));
                    }
                });
            }, null);
        }, null);
    }

    public void cancelWarmup(Player p, String reason) {
        if (p == null) return;
        ScheduledTask task = activeWarmups.remove(p.getUniqueId());
        if (task != null) {
            task.cancel();
            if (p.isOnline()) {
                p.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport cancelled: " + reason));
            }
        }
    }

    public void cancelAll() {
        for (ScheduledTask task : new ArrayList<>(activeWarmups.values())) {
            if (task != null) task.cancel();
        }
        for (UUID uuid : new ArrayList<>(activeWarmups.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport cancelled: server reloading."));
            }
        }
        activeWarmups.clear();
        for (Map.Entry<UUID, TpaSession> entry : new ArrayList<>(activeSessions.entrySet())) {
            TpaSession session = entry.getValue();
            if (session != null && session.timeoutTask() != null) session.timeoutTask().cancel();
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target != null && target.isOnline()) {
                target.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: server reloading."));
            }
            if (session != null) {
                Player requester = Bukkit.getPlayer(session.requesterId());
                if (requester != null && requester.isOnline()) {
                    requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: server reloading."));
                }
            }
        }
        activeSessions.clear();
    }

    public void handleQuit(Player quitter) {
        if (quitter == null) return;
        UUID quitterId = quitter.getUniqueId();

        ScheduledTask warmup = activeWarmups.remove(quitterId);
        if (warmup != null) warmup.cancel();

        TpaSession asTarget = activeSessions.remove(quitterId);
        if (asTarget != null) {
            if (asTarget.timeoutTask() != null) asTarget.timeoutTask().cancel();
            Player requester = Bukkit.getPlayer(asTarget.requesterId());
            if (requester != null && requester.isOnline()) {
                requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
            }
        }

        List<UUID> outgoingTargets = new ArrayList<>();
        for (Map.Entry<UUID, TpaSession> entry : new ArrayList<>(activeSessions.entrySet())) {
            if (entry.getValue().requesterId().equals(quitterId)) {
                outgoingTargets.add(entry.getKey());
            }
        }
        for (UUID targetId : outgoingTargets) {
            TpaSession session = activeSessions.remove(targetId);
            if (session != null) {
                if (session.timeoutTask() != null) session.timeoutTask().cancel();
                Player target = Bukkit.getPlayer(targetId);
                if (target != null && target.isOnline()) {
                    target.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
                }
            }
        }
    }

    public void cancelOutgoingRequest(Player requester) {

        UUID targetUUID = null;
        for (Map.Entry<UUID, TpaSession> entry : new ArrayList<>(activeSessions.entrySet())) {
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
                target.sendMessage(plugin.getMiniMessage().deserialize("<yellow>" + esc(requester.getName()) + " <gray>cancelled their teleport request."));
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
                plugin.getLogger().severe("Could not save player data for " + uuid + ": " + e.getMessage());
            }
        });
    }
}
