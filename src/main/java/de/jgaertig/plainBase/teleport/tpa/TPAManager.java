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
    // Warmup anchor tracking: the warmup task is keyed by the teleporting
    // player (toTeleport), but the destination player (anchor) keeps them in
    // place. If the anchor quits, the task keyed by toTeleport would survive
    // as an orphan. Both directions are stored so handleQuit can find and
    // cancel the other side from either quitter.
    private final Map<UUID, UUID> warmupPartners = new ConcurrentHashMap<>();

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
        if (requester == null || target == null) return;
        UUID requesterId = requester.getUniqueId();
        UUID targetId = target.getUniqueId();

        if (requesterId.equals(targetId)) {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>You cannot send a teleport request to yourself."));
            return;
        }
        if (!requester.isOnline() || !target.isOnline()) {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>That player is currently not online."));
            return;
        }

        for (TpaSession session : activeSessions.values()) {
            if (session.requesterId().equals(requesterId)) {
                requester.sendMessage(plugin.getMiniMessage().deserialize("<red>You already have an outgoing teleport request! Use /tpacancel to cancel it."));
                return;
            }
        }

        // Atomic reservation: only one request per target can win. The timeout
        // task is scheduled only after the reservation succeeded, so a lost
        // race never leaks a timeout task or overwrites the winner.
        TpaSession stub = new TpaSession(requesterId, type, null);
        if (activeSessions.putIfAbsent(targetId, stub) != null) {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>This player already has a pending teleport request. Try again later."));
            return;
        }

        requester.sendMessage(plugin.getMiniMessage().deserialize("<gray>Teleport request sent to <yellow>" + esc(target.getName()) + "<gray>."));
        target.sendMessage(plugin.getMiniMessage().deserialize("<yellow>" + esc(requester.getName()) + " <gray>has sent you a teleport request."));

        if (tpAutoPlayers.contains(targetId)) {
            activeSessions.remove(targetId, stub);
            startTeleportProcedure(requester, target, type);
            return;
        }

        long seconds = 300;
        FileConfiguration cfg = plugin.getTeleportConfig();
        if (cfg == null) {
            activeSessions.remove(targetId, stub);
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport is currently unavailable."));
            return;
        }
        seconds = cfg.getLong("tpa.request_timeout", 300);
        if (seconds <= 0) seconds = 30;
        seconds = Math.max(5, Math.min(300, seconds));
        final long timeoutSeconds = seconds;

        String typeAction = (type == RequestType.TPA) ? "teleport to you" : "you teleport to them";
        target.sendMessage(plugin.getMiniMessage().deserialize(
                "<gray>They want to " + typeAction + ". You have <yellow>" +
                        timeoutSeconds + " <gray>seconds to respond.\n" +
                        "<gray>Use <green>/tpaccept <gray>or <red>/tpdeny<gray>."
        ));

        ScheduledTask timeoutTask;
        try {
            timeoutTask = Bukkit.getAsyncScheduler().runDelayed(plugin, (t) -> {
                expireRequest(targetId);
            }, timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            activeSessions.remove(targetId, stub);
            plugin.getLogger().warning("Failed to schedule TPA timeout for " + target.getName() + ": " + e.getMessage());
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request failed. Try again later."));
            return;
        }

        TpaSession full = new TpaSession(requesterId, type, timeoutTask);
        if (!activeSessions.replace(targetId, stub, full)) {
            // Session was removed concurrently (quit/cancel/accept) before the
            // timeout was attached — never leak the orphan timeout task.
            timeoutTask.cancel();
        }
    }

    public void acceptRequest(Player target) {
        if (!acceptIfPresent(target)) {
            target.sendMessage(plugin.getMiniMessage().deserialize("<red>You don't have any pending requests!"));
        }
    }

    /**
     * Accepts the pending request for target if one exists. Silent (no
     * "no pending" message) when no session is present, so toggleTpAuto
     * enabling tpauto without a pending request only prints the
     * Enabled-message. Returns true iff a session was present.
     */
    private boolean acceptIfPresent(Player target) {
        TpaSession session = activeSessions.remove(target.getUniqueId());
        if (session == null) {
            return false;
        }
        if (session.timeoutTask() != null) session.timeoutTask().cancel();

        Player requester = Bukkit.getPlayer(session.requesterId());
        if (requester != null) {
            // Re-validate visibility: the requester may have vanished (or the
            // target may have) between send and accept. Never teleport towards
            // a player the other side can no longer see.
            if (isBlockedByVanish(requester, target)) {
                target.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
                return true;
            }
            startTeleportProcedure(requester, target, session.type());
        } else {
            target.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
        }
        return true;
    }

    public void denyRequest(Player target) {
        TpaSession session = activeSessions.remove(target.getUniqueId());
        if (session == null) {
            target.sendMessage(plugin.getMiniMessage().deserialize("<red>You don't have any pending requests!"));
            return;
        }
        if (session.timeoutTask() != null) session.timeoutTask().cancel();

        Player requester = Bukkit.getPlayer(session.requesterId());
        if (requester != null) {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>" + esc(target.getName()) + " denied your teleport request."));
        }
        target.sendMessage(plugin.getMiniMessage().deserialize("<red>Request denied."));
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
            // Silent accept: no containsKey pre-check (TOCTOU) and no
            // "no pending" spam when nothing is pending or on session race.
            acceptIfPresent(player);
        }
        savePlayerData(uuid, newStatus);
    }

    /**
     * Vanish re-check: Bukkit visibility can change between request and
     * teleport (a side vanishes mid-warmup). A teleport where either side can
     * no longer see the other — or where either side is vanished per
     * VanishManager — must not proceed. Uses the generic not-found/left
     * message so vanish state is never leaked.
     */
    private boolean isBlockedByVanish(Player a, Player b) {
        if (a == null || b == null) return true;
        try {
            if (!a.canSee(b) || !b.canSee(a)) return true;
        } catch (Exception e) {
            plugin.getLogger().fine("Vanish visibility check failed for " + a.getName() + "/" + b.getName() + ": " + e.getMessage());
        }
        try {
            if (plugin.getVanishManager() != null
                    && (plugin.getVanishManager().isVanished(a.getUniqueId())
                    || plugin.getVanishManager().isVanished(b.getUniqueId()))) {
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Vanish state check failed for " + a.getName() + "/" + b.getName() + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Whether this player currently has a pending TPA warmup. Used by
     * RTPManager.proceedToWarmup: a running warmup wins, a new RTP search
     * aborts instead of killing the TPA warmup.
     */
    public boolean hasWarmup(Player p) {
        return p != null && activeWarmups.containsKey(p.getUniqueId());
    }

    private void expireRequest(UUID targetId) {
        TpaSession session = activeSessions.remove(targetId);
        if (session == null) return;

        // Runs on the async scheduler: only the map removal above may happen
        // here. Bukkit API calls (sendMessage) must run on entity threads.
        UUID requesterId = session.requesterId();
        Player target = Bukkit.getPlayer(targetId);
        Player requester = Bukkit.getPlayer(requesterId);
        String targetName = target != null ? target.getName() : null;

        if (target != null) {
            try {
                target.getScheduler().run(plugin, (t) -> {
                    if (target.isOnline()) {
                        target.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request expired."));
                    }
                }, null);
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to notify TPA expiry for " + targetId + ": " + e.getMessage());
            }
        }
        if (requester != null) {
            try {
                requester.getScheduler().run(plugin, (t) -> {
                    if (requester.isOnline()) {
                        requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request to " + esc(targetName != null ? targetName : "player") + " expired."));
                    }
                }, null);
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to notify TPA expiry for " + requesterId + ": " + e.getMessage());
            }
        }
    }

    private void clearSession(UUID targetId) {
        TpaSession session = activeSessions.remove(targetId);
        if (session != null && session.timeoutTask() != null) {
            session.timeoutTask().cancel();
        }
    }

    private void startTeleportProcedure(Player requester, Player target, RequestType type) {
        if (requester == null || target == null) return;
        // Re-validate visibility at procedure start (covers the tpauto
        // instant-accept path in sendRequest as well as acceptIfPresent).
        if (isBlockedByVanish(requester, target)) {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
            target.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
            return;
        }

        String msg = "<green>Request accepted! Teleportation starting...";
        requester.sendMessage(plugin.getMiniMessage().deserialize(msg));
        target.sendMessage(plugin.getMiniMessage().deserialize(msg));

        Player toTeleport = (type == RequestType.TPA) ? requester : target;
        Player destination = (type == RequestType.TPA) ? target : requester;

        // Mirror to RTPManager.proceedToWarmup (which cancels the TPA warmup on
        // RTP start): a pending RTP warmup for the same player must not fire
        // after this TPA teleport. cancelWarmup is a no-op with no message
        // when no RTP warmup exists. A still-searching RTP (no warmup yet) is
        // cancelled as well so it cannot start a competing warmup afterwards.
        try {
            if (toTeleport != null && plugin.getRTPManager() != null) {
                plugin.getRTPManager().cancelWarmup(toTeleport, "TPA started.");
                plugin.getRTPManager().cancelSearch(toTeleport);
                // Symmetric to the reverse direction (RTP start refunds): a
                // search cancelled for this TPA teleport must not consume the
                // RTP cooldown. Null-guarded like the calls above.
                plugin.getRTPManager().refundCooldown(toTeleport.getUniqueId());
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to cancel RTP state for " + toTeleport.getName() + ": " + e.getMessage());
        }

        // Snapshot with null-guard: stopModules() may have cleared
        // teleport.yml between accept and procedure start — never deref
        // fresh, abort with a message instead.
        FileConfiguration teleportConfig = plugin.getTeleportConfig();
        if (teleportConfig == null) {
            requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport is currently unavailable."));
            target.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport is currently unavailable."));
            return;
        }

        long seconds = teleportConfig.getLong("tpa.counter.seconds", 3);
        // Clamp like tpa.request_timeout above: negative/huge values must never
        // leak into the scheduler delay or the displayed countdown.
        seconds = Math.max(0, Math.min(30, seconds));

        if (!teleportConfig.getBoolean("tpa.counter.enabled", true) || seconds <= 0) {
            performTeleport(toTeleport, destination);
            return;
        }

        ScheduledTask old = activeWarmups.remove(toTeleport.getUniqueId());
        if (old != null) old.cancel();
        // Drop any stale anchor mapping for a replaced warmup.
        removeWarmupPartner(toTeleport.getUniqueId());

        toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<gray>Teleporting in <yellow>" + seconds + " <gray>seconds. Do not move!"));

        UUID toTeleportId = toTeleport.getUniqueId();
        UUID destinationId = destination.getUniqueId();
        warmupPartners.put(toTeleportId, destinationId);
        warmupPartners.put(destinationId, toTeleportId);

        ScheduledTask warmupTask = toTeleport.getScheduler().runDelayed(plugin, (task) -> {
            activeWarmups.remove(toTeleportId);
            removeWarmupPartner(toTeleportId);
            performTeleport(toTeleport, destination);
        }, null, seconds * 20L);

        // Atomic reservation: a concurrent RTP warmup for the same player may
        // have been registered after the remove() above — putIfAbsent lets the
        // already-running warmup win instead of overwriting it (TOCTOU with
        // RTPManager.proceedToWarmup, which mirrors this).
        if (activeWarmups.putIfAbsent(toTeleportId, warmupTask) != null) {
            warmupTask.cancel();
            removeWarmupPartner(toTeleportId);
            toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport cancelled: a teleport is already in progress."));
            return;
        }
    }

    /**
     * Removes both directions of a warmup anchor mapping for the given player.
     */
    private void removeWarmupPartner(UUID uuid) {
        if (uuid == null) return;
        UUID partner = warmupPartners.remove(uuid);
        if (partner != null) {
            warmupPartners.remove(partner, uuid);
        }
    }

    private void performTeleport(Player toTeleport, Player destination) {
        if (toTeleport == null || destination == null) return;
        if (!toTeleport.isOnline() || !destination.isOnline()) return;
        // Last-moment vanish check before hopping threads: either side may
        // have vanished during the warmup.
        if (isBlockedByVanish(toTeleport, destination)) {
            if (toTeleport.isOnline()) {
                toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
            }
            return;
        }

        destination.getScheduler().run(plugin, t -> {
            if (!destination.isOnline() || !toTeleport.isOnline()) return;
            // Second hop, same re-check: vanish state may have changed between
            // the two scheduler hops.
            if (isBlockedByVanish(toTeleport, destination)) {
                if (toTeleport.isOnline()) {
                    toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
                }
                return;
            }
            Location destLoc = destination.getLocation().clone();
            if (destLoc.getWorld() == null) return;
            toTeleport.getScheduler().run(plugin, t2 -> {
                if (!toTeleport.isOnline() || !destination.isOnline()) return;
                if (isBlockedByVanish(toTeleport, destination)) {
                    if (toTeleport.isOnline()) {
                        toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
                    }
                    return;
                }
                toTeleport.teleportAsync(destLoc).thenAccept(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<green>Teleport successful!"));
                    } else if (toTeleport.isOnline()) {
                        try {
                            toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again!"));
                        } catch (Exception e) {
                            plugin.getLogger().fine("Failed to notify TPA teleport failure for " + toTeleport.getName() + ": " + e.getMessage());
                        }
                    }
                });
            }, null);
        }, null);
    }

    public void cancelWarmup(Player p, String reason) {
        if (p == null) return;
        ScheduledTask task = activeWarmups.remove(p.getUniqueId());
        removeWarmupPartner(p.getUniqueId());
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
        warmupPartners.clear();
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

        // The quitter may be the anchor (destination) of someone else's
        // warmup, which is keyed by the teleporting player. Without this the
        // warmup would survive as an orphan and fire against an offline
        // anchor. Snapshot the partner first, then drop the mapping and
        // cancel the other side.
        UUID partnerId = warmupPartners.get(quitterId);
        removeWarmupPartner(quitterId);
        if (partnerId != null && !partnerId.equals(quitterId)) {
            ScheduledTask partnerWarmup = activeWarmups.remove(partnerId);
            if (partnerWarmup != null) {
                partnerWarmup.cancel();
                removeWarmupPartner(partnerId);
                Player partner = Bukkit.getPlayer(partnerId);
                if (partner != null && partner.isOnline()) {
                    partner.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
                }
            }
        }

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
