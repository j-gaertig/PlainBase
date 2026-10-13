package de.jgaertig.plainBase.teleport.tpa;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.PlayerDataLocks;
import de.jgaertig.plainBase.teleport.WarmupReservations;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TPAManager {

    private final PlainBase plugin;

    // Generation guard for warmup bodies: a /plainbase reload constructs a
    // fresh TPAManager and cancels pending warmups, but an already-firing
    // task of the previous instance must never teleport. The body checks
    // that this instance is still the live one before acting.
    private static final AtomicLong GENERATION = new AtomicLong(0);
    private final long generation = GENERATION.incrementAndGet();


    private final Map<UUID, TpaSession> activeSessions = new ConcurrentHashMap<>();
    // tpAutoPlayers persists per-player tpauto flags in data/playerdata/<uuid>.yml
    // (see loadPlayerData/savePlayerData). Entries intentionally survive quit:
    // removal on quit would discard the persisted preference. A Set guarantees
    // no duplicate growth — add() is idempotent, so repeated toggles/joins
    // cannot accumulate entries.
    private final Set<UUID> tpAutoPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Object> tpAutoLoadIdentities = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> activeWarmups = new ConcurrentHashMap<>();
    private final Map<UUID, Object> warmupIdentities = new ConcurrentHashMap<>();
    private final Object warmupLock = new Object();
    private volatile boolean shuttingDown;
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

    private void notifyPlayer(Player player, String message) {
        if (player == null) return;
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) player.sendMessage(plugin.getMiniMessage().deserialize(message));
            }, null);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not schedule TPA notification: " + e.getMessage());
        }
    }

    private void runForPlayer(Player player, Runnable action) {
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) action.run();
            }, null);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not schedule TPA player action: " + e.getMessage());
        }
    }

    private void checkOnlineOnEntity(Player player, java.util.function.Consumer<Boolean> callback) {
        AtomicBoolean completed = new AtomicBoolean();
        try {
            player.getScheduler().run(plugin, task -> {
                if (!completed.compareAndSet(false, true)) return;
                boolean online;
                try { online = player.isOnline(); }
                catch (RuntimeException e) { online = false; }
                callback.accept(online);
            }, () -> {
                if (completed.compareAndSet(false, true)) callback.accept(false);
            });
        } catch (RuntimeException e) {
            if (completed.compareAndSet(false, true)) callback.accept(false);
        }
    }

    private boolean isActive() {
        return !shuttingDown && plugin.getTPAManager() == this;
    }

    public void sendRequest(Player requester, Player target, RequestType type) {
        if (requester == null || target == null || !isActive()) return;
        checkOnlineOnEntity(requester, requesterOnline -> {
            if (!isActive()) return;
            checkOnlineOnEntity(target, targetOnline -> {
                if (!isActive()) return;
                if (!requesterOnline || !targetOnline) {
                    notifyPlayer(requester, "<red>That player is currently not online.");
                    return;
                }
                runForPlayer(requester, () -> {
                    if (isActive()) sendRequestAfterOnlineCheck(requester, target, type);
                });
            });
        });
    }

    private void sendRequestAfterOnlineCheck(Player requester, Player target, RequestType type) {
        UUID requesterId = requester.getUniqueId();
        UUID targetId = target.getUniqueId();

        if (requesterId.equals(targetId)) {
            notifyPlayer(requester, "<red>You cannot send a teleport request to yourself.");
            return;
        }
        // Atomic check+reserve: the outgoing scan and the target reservation
        // must hold the same monitor — otherwise two concurrent requests from
        // the same requester to different targets could both pass the scan.
        TpaSession stub = new TpaSession(requesterId, type, null);
        synchronized (activeSessions) {
            // Retirement uses this same monitor as its publication barrier:
            // a queued entity callback cannot publish into an old manager once
            // cancelAll has begun, even if it passed the earlier callback checks.
            if (!isActive()) return;
            for (TpaSession session : activeSessions.values()) {
                if (session != null && session.requesterId().equals(requesterId)) {
                    notifyPlayer(requester, "<red>You already have an outgoing teleport request! Use /tpacancel to cancel it.");
                    return;
                }
            }
            // Cross-request guard: the requester already has an incoming
            // pending request (someone waits for them). A reverse request
            // (B->A while A->B is pending) must not coexist — reuse the
            // outgoing message, no new key.
            if (activeSessions.containsKey(requesterId)) {
                notifyPlayer(requester, "<red>You already have an outgoing teleport request! Use /tpacancel to cancel it.");
                return;
            }

            // Atomic reservation: only one request per target can win. The timeout
            // task is scheduled only after the reservation succeeded, so a lost
            // race never leaks a timeout task or overwrites the winner.
            if (activeSessions.putIfAbsent(targetId, stub) != null) {
                notifyPlayer(requester, "<red>This player already has a pending teleport request. Try again later.");
                return;
            }
            // Queue the sent notifications before releasing the publication
            // barrier. If cancelAll follows, its cancellation notifications
            // are queued after these instead of making a lost request appear
            // to have been sent after retirement.
            notifyPlayer(requester, "<gray>Teleport request sent to <yellow>" + esc(target.getName()) + "<gray>.");
            notifyPlayer(target, "<yellow>" + esc(requester.getName()) + " <gray>has sent you a teleport request.");
        }

        if (tpAutoPlayers.contains(targetId)) {
            activeSessions.remove(targetId, stub);
            startTeleportProcedure(requester, target, type);
            return;
        }

        long seconds = 300;
        FileConfiguration cfg = plugin.getTeleportConfig();
        if (cfg == null) {
            activeSessions.remove(targetId, stub);
            notifyPlayer(requester, "<red>Teleport is currently unavailable.");
            return;
        }
        seconds = cfg.getLong("tpa.request_timeout", 300);
        if (seconds <= 0) seconds = 30;
        seconds = Math.max(5, Math.min(300, seconds));
        final long timeoutSeconds = seconds;

        String typeAction = (type == RequestType.TPA) ? "teleport to you" : "you teleport to them";
        notifyPlayer(target, "<gray>They want to " + typeAction + ". You have <yellow>" +
                timeoutSeconds + " <gray>seconds to respond.\n" +
                "<gray>Use <green>/tpaccept <gray>or <red>/tpdeny<gray>.");

        // The full session reserves its own identity for the conditional
        // expiry remove below: the timeout must never delete a NEWER session
        // installed after accept/deny/cancel re-armed the slot.
        // L4: capture display names synchronously — the async expiry below
        // must never call Bukkit/Player getters off-thread.
        String capturedTargetName;
        try {
            capturedTargetName = target.getName();
        } catch (Exception e) {
            capturedTargetName = "player";
        }
        final String targetNameForTimeout = capturedTargetName;
        AtomicReference<TpaSession> expectedRef = new AtomicReference<>();
        final Player expiryTarget = target;
        final Player expiryRequester = requester;
        ScheduledTask timeoutTask;
        try {
            timeoutTask = Bukkit.getAsyncScheduler().runDelayed(plugin, (t) -> {
                expireRequest(targetId, expectedRef.get(), targetNameForTimeout, expiryTarget, expiryRequester);
            }, timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            activeSessions.remove(targetId, stub);
            plugin.getLogger().warning("Failed to schedule TPA timeout for " + target.getName() + ": " + e.getMessage());
            notifyPlayer(requester, "<red>Teleport request failed. Try again later.");
            return;
        }

        TpaSession full = new TpaSession(requesterId, type, timeoutTask);
        expectedRef.set(full);
        if (!activeSessions.replace(targetId, stub, full)) {
            // Session was removed concurrently (quit/cancel/accept) before the
            // timeout was attached — never leak the orphan timeout task.
            timeoutTask.cancel();
        }
    }

    public void acceptRequest(Player target) {
        if (!acceptIfPresent(target)) {
            notifyPlayer(target, "<red>You don't have any pending requests!");
        }
    }

    /**
     * R3 UX hint (no behavior change): name of the player whose request is
     * currently pending for target, or null when none (or the requester went
     * offline). Lets /tpaccept and /tpdeny tell a typo'd argument apart from
     * the real requester without changing accept/deny semantics. Never throws.
     */
    public String getPendingRequesterName(Player target) {
        if (target == null) return null;
        TpaSession session;
        try {
            session = activeSessions.get(target.getUniqueId());
        } catch (Exception e) {
            return null;
        }
        if (session == null) return null;
        try {
            Player requester = Bukkit.getPlayer(session.requesterId());
            return requester != null ? requester.getName() : null;
        } catch (Exception e) {
            return null;
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
            // startTeleportProcedure revalidates liveness and visibility on
            // both players' entity schedulers before it begins the warmup.
            startTeleportProcedure(requester, target, session.type());
        } else {
            notifyPlayer(target, "<red>Teleport request cancelled: player left the server.");
        }
        return true;
    }

    public void denyRequest(Player target) {
        TpaSession session = activeSessions.remove(target.getUniqueId());
        if (session == null) {
            notifyPlayer(target, "<red>You don't have any pending requests!");
            return;
        }
        if (session.timeoutTask() != null) session.timeoutTask().cancel();

        Player requester = Bukkit.getPlayer(session.requesterId());
        if (requester != null) {
            notifyPlayer(requester, "<red>" + esc(target.getName()) + " denied your teleport request.");
        }
        notifyPlayer(target, "<red>Request denied.");
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
        synchronized (PlayerDataLocks.lockFor(uuid)) {
            // A join-time async read must not restore an old persisted value
            // after the player has made a newer choice in this session.
            tpAutoLoadIdentities.remove(uuid);
            if (tpAutoPlayers.contains(uuid)) {
                tpAutoPlayers.remove(uuid);
                newStatus = false;
            } else {
                tpAutoPlayers.add(uuid);
                newStatus = true;
            }
        }
        if (newStatus) {
            notifyPlayer(player, "<gray>TP-Auto <green>enabled<gray>.");
            // Silent accept: no containsKey pre-check (TOCTOU) and no
            // "no pending" spam when nothing is pending or on session race.
            acceptIfPresent(player);
        } else {
            notifyPlayer(player, "<gray>TP-Auto <red>disabled<gray>.");
        }
        savePlayerData(uuid, newStatus);
    }

    /**
     * Vanish re-check: Bukkit visibility can change between request and
     * teleport (a side vanishes mid-warmup). A teleport where either side can
     * no longer see the other must not proceed. Visibility only (canSee) —
     * no separate isVanished check: canSee already encodes vanish state plus
     * permissions, a pure isVanished clause would block staff-to-staff
     * teleports that canSee allows. Uses the generic not-found/left
     * message so vanish state is never leaked.
     */
    /**
     * Whether this player currently has a pending TPA warmup. Used by
     * RTPManager.proceedToWarmup: a running warmup wins, a new RTP search
     * aborts instead of killing the TPA warmup.
     */
    public boolean hasWarmup(Player p) {
        if (p == null) return false;
        synchronized (warmupLock) {
            UUID uuid = p.getUniqueId();
            return activeWarmups.containsKey(uuid) || warmupIdentities.containsKey(uuid);
        }
    }

    /**
     * Async expiry: removes only the session captured at schedule time. A
     * newer session for the same target (re-request after accept/deny/cancel)
     * is foreign and must survive — hence remove(key, expected), never an
     * unconditional remove.
     */
    private void expireRequest(UUID targetId, TpaSession expected, String targetName,
                               Player targetRef, Player requesterRef) {
        if (targetId == null || expected == null) return;
        if (!activeSessions.remove(targetId, expected)) return;
        TpaSession session = expected;

        // Runs on the async scheduler: only the map removal above may happen
        // here. Bukkit API calls (sendMessage) must run on entity threads.
        // L4: names were captured synchronously at schedule time — never call
        // target.getName()/requester.getName() here off-thread.
        UUID requesterId = session.requesterId();
        Player target = targetRef;
        Player requester = requesterRef;
        final String safeTargetName = targetName != null ? targetName : "player";

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
                        requester.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request to " + esc(safeTargetName) + " expired."));
                    }
                }, null);
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to notify TPA expiry for " + requesterId + ": " + e.getMessage());
            }
        }
    }

    private void startTeleportProcedure(Player requester, Player target, RequestType type) {
        if (requester == null || target == null) return;
        Player toTeleport = (type == RequestType.TPA) ? requester : target;
        Player destination = (type == RequestType.TPA) ? target : requester;
        UUID toTeleportId = toTeleport.getUniqueId();
        UUID destinationId = destination.getUniqueId();
        Object warmupIdentity = new Object();
        synchronized (warmupLock) {
            if (shuttingDown || plugin.getTPAManager() != this) {
                notifyPlayer(requester, "<red>Teleport is reloading. Try again shortly.");
                return;
            }
            UUID existingAnchor = warmupPartners.get(destinationId);
            UUID existingTeleport = warmupPartners.get(toTeleportId);
            if ((existingAnchor != null && !existingAnchor.equals(toTeleportId))
                    || (existingTeleport != null && !existingTeleport.equals(destinationId))
                    || warmupIdentities.containsKey(destinationId)) {
                notifyPlayer(toTeleport, "<red>Teleport cancelled: the destination is already in use.");
                return;
            }
            if (!WarmupReservations.reservePair(toTeleportId, destinationId, warmupIdentity)) {
                notifyPlayer(toTeleport, "<red>Teleport cancelled: a teleport is already in progress.");
                return;
            }
            warmupIdentities.put(toTeleportId, warmupIdentity);
            warmupPartners.put(toTeleportId, destinationId);
            warmupPartners.put(destinationId, toTeleportId);
        }
        AtomicInteger checks = new AtomicInteger(2);
        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicBoolean requesterChecked = new AtomicBoolean();
        AtomicBoolean targetChecked = new AtomicBoolean();
        Runnable complete = () -> {
            if (checks.decrementAndGet() != 0) return;
            if (allowed.get()) {
                continueTeleportProcedure(requester, target, type, toTeleport, destination, toTeleportId, warmupIdentity);
            } else {
                finishWarmup(toTeleportId, warmupIdentity);
                notifyPlayer(requester, "<red>Teleport request cancelled: player left the server.");
                notifyPlayer(target, "<red>Teleport request cancelled: player left the server.");
            }
        };
        try {
            requester.getScheduler().run(plugin, task -> {
                if (!requesterChecked.compareAndSet(false, true)) return;
                try {
                    if (!requester.isOnline() || requester.isDead() || !requester.canSee(target)) allowed.set(false);
                } catch (Exception e) { allowed.set(false); }
                complete.run();
            }, () -> {
                if (requesterChecked.compareAndSet(false, true)) { allowed.set(false); complete.run(); }
            });
        } catch (RuntimeException e) {
            if (requesterChecked.compareAndSet(false, true)) { allowed.set(false); complete.run(); }
        }
        try {
            target.getScheduler().run(plugin, task -> {
                if (!targetChecked.compareAndSet(false, true)) return;
                try {
                    if (!target.isOnline() || target.isDead() || !target.canSee(requester)) allowed.set(false);
                } catch (Exception e) { allowed.set(false); }
                complete.run();
            }, () -> {
                if (targetChecked.compareAndSet(false, true)) { allowed.set(false); complete.run(); }
            });
        } catch (RuntimeException e) {
            if (targetChecked.compareAndSet(false, true)) { allowed.set(false); complete.run(); }
        }
    }

    /** Called only after both players were checked on their own entity schedulers. */
    private void continueTeleportProcedure(Player requester, Player target, RequestType type,
                                          Player toTeleport, Player destination, UUID toTeleportId,
                                          Object warmupIdentity) {
        if (!WarmupReservations.isOwner(toTeleportId, warmupIdentity)) return;
        String msg = "<green>Request accepted! Teleportation starting...";
        notifyPlayer(requester, msg);
        notifyPlayer(target, msg);

        // Mirror to RTPManager.proceedToWarmup (which cancels the TPA warmup on
        // RTP start): a pending RTP warmup for the same player must not fire
        // after this TPA teleport. cancelWarmup is a no-op with no message
        // when no RTP warmup exists. A still-searching RTP (no warmup yet) is
        // cancelled as well so it cannot start a competing warmup afterwards.
        try {
            if (toTeleport != null && plugin.getRTPManager() != null) {
                plugin.getRTPManager().cancelWarmup(toTeleport, "TPA started.");
                // cancelSearch/cancelWarmup refund the cooldown owned by the
                // operation they revoke. A separate unconditional refund here
                // could erase a newer RTP cooldown started after cancellation.
                plugin.getRTPManager().cancelSearch(toTeleport);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to cancel RTP state for " + toTeleport.getUniqueId() + ": " + e.getMessage());
        }

        // Snapshot with null-guard: stopModules() may have cleared
        // teleport.yml between accept and procedure start — never deref
        // fresh, abort with a message instead.
        FileConfiguration teleportConfig = plugin.getTeleportConfig();
        if (teleportConfig == null) {
            finishWarmup(toTeleportId, warmupIdentity);
            notifyPlayer(requester, "<red>Teleport is currently unavailable.");
            notifyPlayer(target, "<red>Teleport is currently unavailable.");
            return;
        }

        long seconds = teleportConfig.getLong("tpa.counter.seconds", 3);
        // Clamp like tpa.request_timeout above: negative/huge values must never
        // leak into the scheduler delay or the displayed countdown.
        seconds = Math.max(0, Math.min(30, seconds));

        // Keep both anchors registered for the entire teleport operation,
        // including the scheduler hops after the timer fires and the
        // zero-second fast path. /tpacancel and quit handling can then revoke
        // the identity until teleportAsync has actually been initiated.

        if (!teleportConfig.getBoolean("tpa.counter.enabled", true) || seconds <= 0) {
            performTeleport(toTeleport, destination, toTeleportId, warmupIdentity);
            return;
        }

        notifyPlayer(toTeleport, "<gray>Teleporting in <yellow>" + seconds + " <gray>seconds. Do not move!");

        final long capturedGeneration = generation;

        ScheduledTask warmupTask;
        try {
            warmupTask = toTeleport.getScheduler().runDelayed(plugin, (task) -> {
                // Generation guard: a stale task of a previous manager
                // instance (reload) must never teleport.
                if (!WarmupReservations.isOwner(toTeleportId, warmupIdentity)) return;
                if (capturedGeneration != GENERATION.get() || plugin.getTPAManager() != this) {
                    finishWarmup(toTeleportId, warmupIdentity);
                    return;
                }
                activeWarmups.remove(toTeleportId);
                performTeleport(toTeleport, destination, toTeleportId, warmupIdentity);
            }, null, seconds * 20L);
        } catch (Exception e) {
            synchronized (warmupLock) {
                if (warmupIdentities.remove(toTeleportId, warmupIdentity)) {
                    WarmupReservations.releaseIdentity(warmupIdentity);
                    removeWarmupPartner(toTeleportId);
                }
            }
            plugin.getLogger().fine("Failed to schedule TPA warmup for " + toTeleportId + ": " + e.getMessage());
            notifyPlayer(toTeleport, "<red>Teleport failed. Try again!");
            return;
        }

        if (WarmupReservations.isOwner(toTeleportId, warmupIdentity)
                && warmupIdentities.get(toTeleportId) == warmupIdentity) {
            synchronized (warmupLock) {
                if (WarmupReservations.isOwner(toTeleportId, warmupIdentity)
                        && warmupIdentities.get(toTeleportId) == warmupIdentity) {
                    activeWarmups.put(toTeleportId, warmupTask);
                } else {
                    activeWarmups.remove(toTeleportId, warmupTask);
                    warmupTask.cancel();
                }
            }
        } else {
            warmupTask.cancel();
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

    /**
     * Damaging ground types that must never be a TPA arrival spot, even when
     * solid (mirrors the RTPManager ground hazard rejects). Name-based so
     * version renames can never slip through on any Paper build.
     */
    private static boolean isDamagingGroundBlock(String materialName) {
        if (materialName == null) return false;
        return switch (materialName) {
            case "LAVA", "WATER", "FIRE",
                    "CACTUS", "MAGMA_BLOCK",
                    "CAMPFIRE", "SOUL_CAMPFIRE",
                    "WITHER_ROSE", "SWEET_BERRY_BUSH" -> true;
            default -> false;
        };
    }

    /**
     * Hardcoded always-unsafe block basis, same as
     * RTPManager#isAlwaysUnsafeBlock: applied independently of any config.
     */
    private static boolean isAlwaysUnsafeBlock(String materialName) {
        if (materialName == null) return false;
        return switch (materialName) {
            case "BEDROCK", "BARRIER",
                    "PORTAL", "NETHER_PORTAL",
                    "END_PORTAL", "ENDER_PORTAL", "END_GATEWAY",
                    "POWDER_SNOW" -> true;
            default -> false;
        };
    }

    private void performTeleport(Player toTeleport, Player destination, UUID teleportingId, Object warmupIdentity) {
        if (toTeleport == null || destination == null || !WarmupReservations.isOwner(teleportingId, warmupIdentity)) return;

        // Every player state/visibility read runs on that player's own entity
        // scheduler. Only the boolean decisions and immutable destination data
        // cross between schedulers.
        try {
            toTeleport.getScheduler().run(plugin, teleporteeTask -> {
                if (!WarmupReservations.isOwner(teleportingId, warmupIdentity)) return;
                try {
                    if (!toTeleport.isOnline() || toTeleport.isDead()) {
                        finishWarmup(teleportingId, warmupIdentity);
                        return;
                    }
                } catch (Exception e) { finishWarmup(teleportingId, warmupIdentity); return; }
                final boolean canSeeDestination;
                try { canSeeDestination = toTeleport.canSee(destination); }
                catch (Exception e) { finishWarmup(teleportingId, warmupIdentity); return; }
                if (!canSeeDestination) {
                    finishWarmup(teleportingId, warmupIdentity);
                    try {
                        toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
                    } catch (Exception ignored) {
                    }
                    return;
                }

                try {
                    destination.getScheduler().run(plugin, destinationTask -> {
                        if (!WarmupReservations.isOwner(teleportingId, warmupIdentity)) return;
                        try {
                            if (!destination.isOnline() || destination.isDead()) {
                                finishWarmup(teleportingId, warmupIdentity);
                                return;
                            }
                        } catch (Exception e) { finishWarmup(teleportingId, warmupIdentity); return; }
                        final boolean canSeeTeleportee;
                        try { canSeeTeleportee = destination.canSee(toTeleport); }
                        catch (Exception e) { finishWarmup(teleportingId, warmupIdentity); return; }
                        if (!canSeeTeleportee) {
                            finishWarmup(teleportingId, warmupIdentity);
                            try { toTeleport.getScheduler().run(plugin, notify -> {
                                if (toTeleport.isOnline()) toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport request cancelled: player left the server."));
                            }, null); } catch (Exception ignored) {}
                            return;
                        }

                        Location arrivalLoc;
                        try {
                            Location destLoc = destination.getLocation().clone();
                            if (destLoc.getWorld() == null) { finishWarmup(teleportingId, warmupIdentity); return; }
                            destLoc.setX(destLoc.getBlockX() + 0.5);
                            destLoc.setZ(destLoc.getBlockZ() + 0.5);
                            arrivalLoc = null;
                            for (int i = 0; i <= 5; i++) {
                                Location cand = destLoc.clone().add(0, i, 0);
                                if (cand.getY() + 1 >= cand.getWorld().getMaxHeight()) break;
                                if (cand.getY() < cand.getWorld().getMinHeight()) continue;
                                try {
                                    org.bukkit.block.Block feet = cand.getBlock();
                                    org.bukkit.block.Block head = cand.clone().add(0, 1, 0).getBlock();
                                    org.bukkit.block.Block ground = cand.clone().add(0, -1, 0).getBlock();
                                    if (!feet.isPassable() || !head.isPassable()) continue;
                                    if (isAlwaysUnsafeBlock(feet.getType().name()) || isAlwaysUnsafeBlock(head.getType().name())) continue;
                                    if (!ground.getType().isSolid()) continue;
                                    String groundName = ground.getType().name();
                                    if (groundName.contains("LEAVES") || isDamagingGroundBlock(groundName)
                                            || isAlwaysUnsafeBlock(groundName)) continue;
                                    arrivalLoc = cand;
                                    break;
                                } catch (Exception ignored) {}
                            }
                        } catch (Exception e) { finishWarmup(teleportingId, warmupIdentity); return; }
                        if (arrivalLoc == null) {
                            finishWarmup(teleportingId, warmupIdentity);
                            try { toTeleport.getScheduler().run(plugin, notify -> {
                                if (toTeleport.isOnline()) toTeleport.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed: destination is obstructed."));
                            }, null); } catch (Exception ignored) {}
                            return;
                        }
                        final Location validatedArrival = arrivalLoc.clone();
                        try {
                            toTeleport.getScheduler().run(plugin, initiate -> {
                                boolean notifyTeleportFailed = false;
                                synchronized (warmupLock) {
                                    // Linearize initiation against warmup cancellation.
                                    // cancelWarmup and /tpacancel release the reservation
                                    // before this point or retain it after expectTeleport
                                    // commits the non-revocable teleportAsync operation.
                                    if (shuttingDown || plugin.getTPAManager() != this
                                            || warmupIdentities.get(teleportingId) != warmupIdentity
                                            || !WarmupReservations.isOwner(teleportingId, warmupIdentity)) return;
                                    try {
                                        if (!toTeleport.isOnline() || toTeleport.isDead()) {
                                            finishWarmup(teleportingId, warmupIdentity);
                                            return;
                                        }
                                        if (!WarmupReservations.expectTeleport(teleportingId, warmupIdentity, validatedArrival)) {
                                            finishWarmup(teleportingId, warmupIdentity);
                                            return;
                                        }
                                        toTeleport.teleportAsync(validatedArrival)
                                                .handle((success, error) -> error == null && Boolean.TRUE.equals(success))
                                                .thenAccept(success -> {
                                            if (!WarmupReservations.isOwner(teleportingId, warmupIdentity)) return;
                                            finishWarmup(teleportingId, warmupIdentity);
                                            try { toTeleport.getScheduler().run(plugin, notify -> {
                                                if (!toTeleport.isOnline()) return;
                                                toTeleport.sendMessage(plugin.getMiniMessage().deserialize(Boolean.TRUE.equals(success)
                                                        ? "<green>Teleport successful!" : "<red>Teleport failed. Try again!"));
                                            }, null); } catch (Exception ignored) {}
                                        });
                                    } catch (Exception e) {
                                        finishWarmup(teleportingId, warmupIdentity);
                                        notifyTeleportFailed = true;
                                    }
                                }
                                if (notifyTeleportFailed) {
                                    notifyPlayer(toTeleport, "<red>Teleport failed. Try again!");
                                }
                            }, null);
                        } catch (Exception e) { finishWarmup(teleportingId, warmupIdentity); }
                    }, null);
                } catch (Exception e) { finishWarmup(teleportingId, warmupIdentity); }
            }, null);
        } catch (Exception e) { finishWarmup(teleportingId, warmupIdentity); }
    }

    private void finishWarmup(UUID uuid, Object identity) {
        synchronized (warmupLock) {
            // Keep reservation release and pair-map cleanup atomic relative to
            // startTeleportProcedure, which publishes new operations under
            // this same lock. Otherwise old cleanup can erase a newer pair's
            // identical UUID mapping.
            if (!warmupIdentities.remove(uuid, identity)) return;
            WarmupReservations.releaseIdentity(identity);
            removeWarmupPartner(uuid);
        }
    }

    public void cancelWarmup(Player p, String reason) {
        if (p == null) return;
        UUID uuid = p.getUniqueId();
        ScheduledTask task;
        Object identity;
        synchronized (warmupLock) {
            identity = warmupIdentities.get(uuid);
            // Once teleportAsync is initiated it cannot be safely cancelled;
            // retain ownership until its completion callback runs.
            if (WarmupReservations.isTeleportInFlight(uuid, identity)) return;
            task = activeWarmups.remove(uuid);
            identity = warmupIdentities.remove(uuid);
            WarmupReservations.releaseIdentity(identity);
            if (task != null || identity != null) removeWarmupPartner(uuid);
        }
        if (task != null || identity != null) {
            if (task != null) task.cancel();
            notifyPlayer(p, "<red>Teleport cancelled: " + reason);
        }
    }

    public void cancelAll() {
        // Fence request publication before tearing down sessions. Keep this
        // separate from warmupLock: request reservation never nests the two
        // locks, which avoids introducing a shutdown lock-order cycle.
        synchronized (activeSessions) {
            shuttingDown = true;
        }
        List<ScheduledTask> warmupTasks;
        List<UUID> warmupPlayers;
        synchronized (warmupLock) {
            shuttingDown = true;
            warmupTasks = new ArrayList<>(activeWarmups.values());
            warmupPlayers = new ArrayList<>(warmupIdentities.keySet());
            warmupIdentities.values().forEach(WarmupReservations::releaseIdentity);
            warmupIdentities.clear();
            activeWarmups.clear();
            warmupPartners.clear();
        }
        for (ScheduledTask task : warmupTasks) {
            if (task != null) task.cancel();
        }
        for (UUID uuid : warmupPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) notifyPlayer(p, "<red>Teleport cancelled: server reloading.");
        }
        List<Map.Entry<UUID, TpaSession>> cancelledSessions;
        synchronized (activeSessions) {
            cancelledSessions = new ArrayList<>(activeSessions.entrySet());
            activeSessions.clear();
        }
        for (Map.Entry<UUID, TpaSession> entry : cancelledSessions) {
            TpaSession session = entry.getValue();
            if (session != null && session.timeoutTask() != null) session.timeoutTask().cancel();
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target != null) notifyPlayer(target, "<red>Teleport request cancelled: server reloading.");
            if (session != null) {
                Player requester = Bukkit.getPlayer(session.requesterId());
                if (requester != null) notifyPlayer(requester, "<red>Teleport request cancelled: server reloading.");
            }
        }
    }

    public void handleQuit(Player quitter) {
        if (quitter == null) return;
        UUID quitterId = quitter.getUniqueId();

        ScheduledTask warmup;
        Object ownIdentity;
        UUID partnerId;
        ScheduledTask partnerWarmup = null;
        Object partnerIdentity = null;
        synchronized (warmupLock) {
            warmup = activeWarmups.remove(quitterId);
            ownIdentity = warmupIdentities.remove(quitterId);
            WarmupReservations.releaseIdentity(ownIdentity);
            // Snapshot and clear the pair while reservation publication is
            // excluded; otherwise a new identical pair could appear between
            // lookup and cleanup and be mistaken for the old operation.
            partnerId = warmupPartners.get(quitterId);
            removeWarmupPartner(quitterId);
            if (partnerId != null && !partnerId.equals(quitterId)) {
                Object pendingPartnerIdentity = warmupIdentities.get(partnerId);
                if (!WarmupReservations.isTeleportInFlight(partnerId, pendingPartnerIdentity)) {
                    partnerWarmup = activeWarmups.remove(partnerId);
                    partnerIdentity = warmupIdentities.remove(partnerId);
                    WarmupReservations.releaseIdentity(partnerIdentity);
                    if (partnerWarmup != null || partnerIdentity != null) removeWarmupPartner(partnerId);
                }
            }
        }
        if (warmup != null) warmup.cancel();
        boolean hadOwnWarmup = warmup != null || ownIdentity != null;

        // The quitter may be the anchor (destination) of someone else's
        // warmup, which is keyed by the teleporting player. Without this the
        // warmup would survive as an orphan and fire against an offline
        // anchor. Snapshot the partner first, then drop the mapping and
        // cancel the other side.
        if (partnerId != null && !partnerId.equals(quitterId)) {
            if (partnerWarmup != null || partnerIdentity != null) {
                if (partnerWarmup != null) partnerWarmup.cancel();
                Player partner = Bukkit.getPlayer(partnerId);
                if (partner != null) notifyPlayer(partner, "<red>Teleport request cancelled: player left the server.");
            } else if (hadOwnWarmup) {
                // Direct-warmup case: the quitter was the teleporting player,
                // the anchor holds no warmup entry — still inform the anchor.
                Player partner = Bukkit.getPlayer(partnerId);
                if (partner != null) notifyPlayer(partner, "<red>Teleport request cancelled: player left the server.");
            }
        }

        TpaSession asTarget = activeSessions.remove(quitterId);
        if (asTarget != null) {
            if (asTarget.timeoutTask() != null) asTarget.timeoutTask().cancel();
            Player requester = Bukkit.getPlayer(asTarget.requesterId());
            if (requester != null) notifyPlayer(requester, "<red>Teleport request cancelled: player left the server.");
        }

        List<Map.Entry<UUID, TpaSession>> outgoing = new ArrayList<>();
        for (Map.Entry<UUID, TpaSession> entry : new ArrayList<>(activeSessions.entrySet())) {
            TpaSession s = entry.getValue();
            if (s != null && s.requesterId().equals(quitterId)) {
                outgoing.add(entry);
            }
        }
        for (Map.Entry<UUID, TpaSession> e : outgoing) {
            UUID targetId = e.getKey();
            TpaSession expected = e.getValue();
            // Conditional remove (same race as cancelOutgoingRequest): the
            // slot may have been replaced for a different requester since the
            // scan — never delete a foreign session.
            if (expected == null || !activeSessions.remove(targetId, expected)) {
                continue;
            }
            TpaSession session = expected;
            if (session != null) {
                if (session.timeoutTask() != null) session.timeoutTask().cancel();
                Player target = Bukkit.getPlayer(targetId);
                if (target != null) notifyPlayer(target, "<red>Teleport request cancelled: player left the server.");
            }
        }
    }

    public void cancelOutgoingRequest(Player requester) {
        if (requester == null) return;
        UUID requesterId;
        try {
            requesterId = requester.getUniqueId();
        } catch (Exception e) {
            return;
        }

        UUID targetUUID = null;
        TpaSession expected = null;
        for (Map.Entry<UUID, TpaSession> entry : new ArrayList<>(activeSessions.entrySet())) {
            TpaSession s = entry.getValue();
            if (s != null && s.requesterId().equals(requesterId)) {
                targetUUID = entry.getKey();
                expected = s;
                break;
            }
        }

        if (targetUUID != null && expected != null) {
            // Conditional remove: a concurrent sendRequest may have replaced
            // the session at targetUUID for a different requester between the
            // scan above and now — remove(key, expected) never deletes that
            // foreign session.
            if (!activeSessions.remove(targetUUID, expected)) {
                // Lost the race — the slot holds a foreign session now. Still
                // check for a running warmup below before reporting "no outgoing".
                if (cancelAnyWarmupInvolving(requesterId)) {
                    notifyPlayer(requester, "<gray>Your teleport request has been <red>cancelled<gray>.");
                } else {
                    notifyPlayer(requester, "<red>You don't have any outgoing requests!");
                }
                return;
            }
            if (expected.timeoutTask() != null) expected.timeoutTask().cancel();
            notifyPlayer(requester, "<gray>Your teleport request has been <red>cancelled<gray>.");

            Player target = Bukkit.getPlayer(targetUUID);
            if (target != null) {
                notifyPlayer(target, "<yellow>" + esc(requester.getName()) + " <gray>cancelled their teleport request.");
            }
            // A warmup may already run for this requester (accepted but not yet
            // teleported) — cancel it too so /tpacancel always stops the teleport.
            cancelAnyWarmupInvolving(requesterId);
        } else {
            // No pending session — a post-accept warmup may still run keyed by
            // either side. Cancel it instead of reporting "no outgoing".
            if (cancelAnyWarmupInvolving(requesterId)) {
                notifyPlayer(requester, "<gray>Your teleport request has been <red>cancelled<gray>.");
            } else {
                notifyPlayer(requester, "<red>You don't have any outgoing requests!");
            }
        }
    }

    /**
     * Cancels any active warmup involving the given player — either keyed
     * directly by them (they teleport) or via the anchor mapping (they are
     * the destination). Returns true iff a warmup was cancelled.
     */
    private boolean cancelAnyWarmupInvolving(UUID uuid) {
        if (uuid == null) return false;
        ScheduledTask toCancel = null;
        UUID cancelledTeleporter = null;
        ScheduledTask direct = null;
        Object directIdentity;
        synchronized (warmupLock) {
            directIdentity = warmupIdentities.get(uuid);
            if (!WarmupReservations.isTeleportInFlight(uuid, directIdentity)) {
                direct = activeWarmups.remove(uuid);
                directIdentity = warmupIdentities.remove(uuid);
                if (direct != null || directIdentity != null) {
                    WarmupReservations.releaseIdentity(directIdentity);
                    removeWarmupPartner(uuid);
                    toCancel = direct;
                    cancelledTeleporter = uuid;
                } else {
                    UUID partnerId = warmupPartners.get(uuid);
                    if (partnerId != null && !partnerId.equals(uuid)) {
                        Object partnerIdentity = warmupIdentities.get(partnerId);
                        if (!WarmupReservations.isTeleportInFlight(partnerId, partnerIdentity)) {
                            ScheduledTask partnerTask = activeWarmups.remove(partnerId);
                            partnerIdentity = warmupIdentities.remove(partnerId);
                            if (partnerTask != null || partnerIdentity != null) {
                                WarmupReservations.releaseIdentity(partnerIdentity);
                                removeWarmupPartner(partnerId);
                                toCancel = partnerTask;
                                cancelledTeleporter = partnerId;
                            }
                        }
                    }
                }
            }
        }
        if (cancelledTeleporter != null) {
            try {
                if (toCancel != null) toCancel.cancel();
            } catch (Exception ignored) {
            }
            try {
                Player p = Bukkit.getPlayer(cancelledTeleporter);
                if (p != null) notifyPlayer(p, "<red>Teleport cancelled: Request cancelled.");
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    private File getPlayerDataFile(UUID uuid) {
        // Read path: never creates directories as a side effect.
        return new File(new File(plugin.getDataFolder(), "data/playerdata"), uuid.toString() + ".yml");
    }

    private File getPlayerDataFileForWrite(UUID uuid) {
        File folder = new File(plugin.getDataFolder(), "data/playerdata");
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            plugin.getLogger().warning("Could not create directory: " + folder);
        }
        return new File(folder, uuid.toString() + ".yml");
    }

    /**
     * Restore TP-Auto preferences for players already online when this
     * manager is installed. The setup path runs on the global region thread;
     * player state and the call into loadPlayerData are dispatched to each
     * entity's Folia scheduler. A queued callback from a reload that has
     * since replaced this manager is ignored.
     */
    public void loadOnlinePlayerData() {
        if (!isCurrentManager()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.getScheduler().run(plugin, task -> {
                    if (!isCurrentManager() || !player.isOnline()) return;
                    // Entity identity and online state are read on this
                    // player's Folia scheduler. A join event may have won
                    // while this callback was queued; preserve its newer
                    // load identity instead of scheduling a duplicate read.
                    UUID id = player.getUniqueId();
                    Object loadIdentity = new Object();
                    synchronized (PlayerDataLocks.lockFor(id)) {
                        if (tpAutoLoadIdentities.containsKey(id)) return;
                        tpAutoLoadIdentities.put(id, loadIdentity);
                    }
                    schedulePlayerDataLoad(id, loadIdentity);
                }, null);
            } catch (RuntimeException e) {
                plugin.getLogger().fine("Could not schedule tpauto restore for an online player: " + e.getMessage());
            }
        }
    }

    private boolean isCurrentManager() {
        return !shuttingDown && generation == GENERATION.get() && plugin.getTPAManager() == this;
    }

    public void loadPlayerData(Player player) {
        if (!isCurrentManager()) return;
        UUID uuid;
        try {
            uuid = player.getUniqueId();
        } catch (Exception e) {
            return;
        }
        final UUID id = uuid;
        final Object loadIdentity = new Object();
        synchronized (PlayerDataLocks.lockFor(id)) {
            tpAutoLoadIdentities.put(id, loadIdentity);
        }
        schedulePlayerDataLoad(id, loadIdentity);
    }

    private void schedulePlayerDataLoad(UUID id, Object loadIdentity) {
        try {
          Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            // Shared per-UUID lock with VanishManager (same physical file).
            synchronized (PlayerDataLocks.lockFor(id)) {
                try {
                    if (!isCurrentManager() || tpAutoLoadIdentities.get(id) != loadIdentity) return;
                    File file = getPlayerDataFile(id);
                    if (!file.isFile()) return;

                    YamlConfiguration config = new YamlConfiguration();
                    try {
                        config.load(file);
                    } catch (org.bukkit.configuration.InvalidConfigurationException corrupt) {
                        backupCorrupt(file);
                        plugin.getLogger().warning("Corrupt player data for " + id + " moved aside, ignoring tpauto flag.");
                        return;
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not read player data for " + id + ": " + e.getMessage());
                        return;
                    }
                    if (config.getBoolean("tpauto", false)) tpAutoPlayers.add(id);
                } finally {
                    tpAutoLoadIdentities.remove(id, loadIdentity);
                }
            }
          });
        } catch (RuntimeException e) {
            clearPlayerDataLoad(id, loadIdentity);
            plugin.getLogger().fine("Could not schedule tpauto load for " + id + ": " + e.getMessage());
        }
    }

    private void clearPlayerDataLoad(UUID id, Object loadIdentity) {
        synchronized (PlayerDataLocks.lockFor(id)) {
            tpAutoLoadIdentities.remove(id, loadIdentity);
        }
    }

    private boolean backupCorrupt(File file) {
        try {
            File backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            plugin.getLogger().fine("Could not back up corrupt player data " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Crash-safe write: dump to "{@code <uuid>.yml.tmp}" in the same directory,
     * then move over the target atomically (non-atomic fallback where the file
     * system lacks atomic-move support), so a crash can never leave a
     * half-written playerdata file behind. Same pattern as
     * VanishManager#saveAtomically (shared per-UUID lock above already
     * serializes against concurrent vanish saves of the same file).
     * Never throws.
     */
    private void saveAtomically(YamlConfiguration config, File target, UUID uuid) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            config.save(tmp);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save player data for " + uuid + ": " + e.getMessage());
        }
    }

    private void savePlayerData(UUID uuid, boolean tpAutoStatus) {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        plugin.trackPendingSave(pending);
        long version = PlayerDataLocks.beginTpAutoSave(uuid);
        try {
          Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            try {
                // Shared per-UUID lock with VanishManager: load-merge-save must
                // never interleave with a concurrent vanish save of the same
                // file (both keys would otherwise overwrite each other).
                synchronized (PlayerDataLocks.lockFor(uuid)) {
                    if (!PlayerDataLocks.isCurrentTpAutoSave(uuid, version)
                            || tpAutoPlayers.contains(uuid) != tpAutoStatus) return;
                    File file = getPlayerDataFileForWrite(uuid);
                    YamlConfiguration config = new YamlConfiguration();
                    if (file.isFile()) {
                        try {
                            config.load(file);
                        } catch (org.bukkit.configuration.InvalidConfigurationException corrupt) {
                            if (!backupCorrupt(file)) return;
                            plugin.getLogger().warning("Corrupt player data for " + uuid + " moved aside, rewriting tpauto flag.");
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not read player data for " + uuid + ": " + e.getMessage());
                            return;
                        }
                    }

                    config.set("tpauto", tpAutoStatus);
                    // Hier können später weitere Werte mit config.set(...) hinzugefügt werden

                    saveAtomically(config, file, uuid);
                }
            } finally {
                pending.complete(null);
                PlayerDataLocks.finishTpAutoSave(uuid);
            }
          });
        } catch (RuntimeException e) {
            pending.completeExceptionally(e);
            PlayerDataLocks.finishTpAutoSave(uuid);
            plugin.getLogger().fine("Could not schedule tpauto save for " + uuid + ": " + e.getMessage());
        }
    }
}
