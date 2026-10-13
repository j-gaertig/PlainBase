package de.jgaertig.plainBase.spawn;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public class SpawnListener implements Listener {

    private final PlainBase plugin;

    public SpawnListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            FileConfiguration config = plugin.getSpawnConfig();
            if (config == null) return;

            // Locked reads: writers (SetSpawn/DisableSpawn) mutate under
            // synchronized(config); join runs on a different thread.
            final boolean firstSpawnEnabled;
            final boolean spawnEnabled;
            synchronized (config) {
                firstSpawnEnabled = config.getBoolean("first-spawn.enabled", false);
                spawnEnabled = config.getBoolean("spawn.enabled", false);
            }

            // NOTE: join only — there is intentionally no respawn hook here
            // (that would be a new feature, not a bugfix).
            if (!firstSpawnEnabled && !spawnEnabled) return;

            // Player#hasPlayedBefore() can hit disk — never call it on the
            // join (region) thread. Async hop, then schedule back on the
            // entity thread: the delayed teleport below is unchanged, only the
            // first-join check moved off-thread. No behaviour change — the
            // entity task re-checks isOnline (quit mid-hop no-ops) and the
            // teleport path still reads the config snapshot fresh. On lookup
            // failure fail closed as a returning player (regular spawn path)
            // rather than firing first-spawn for a stranger.
            final UUID uuid = player.getUniqueId();
            final boolean firstEnabled = firstSpawnEnabled;
            final boolean spawnOnJoin = spawnEnabled;
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                boolean played;
                try {
                    played = Bukkit.getOfflinePlayer(uuid).hasPlayedBefore();
                } catch (RuntimeException e) {
                    played = true;
                }
                boolean firstJoin = !played;
                try {
                    player.getScheduler().run(plugin, t -> {
                        try {
                            if (!player.isOnline()) return;
                            // Re-check enabled right before scheduling: the spawn
                            // module may have been disabled during the async hop.
                            FileConfiguration live = plugin.getSpawnConfig();
                            boolean liveFirst = firstEnabled;
                            boolean liveSpawn = spawnOnJoin;
                            if (live != null) {
                                synchronized (live) {
                                    liveFirst = live.getBoolean("first-spawn.enabled", false);
                                    liveSpawn = live.getBoolean("spawn.enabled", false);
                                }
                            } else {
                                return;
                            }
                            if (firstJoin && liveFirst) {
                                player.getScheduler().runDelayed(plugin, t2 -> teleportToConfigLocation(player, "first-spawn.location"), null, 1L);
                                return; // Wenn First-Spawn, dann kein normaler Spawn Teleport nötig
                            }
                            if (liveSpawn) {
                                player.getScheduler().runDelayed(plugin, t2 -> teleportToConfigLocation(player, "spawn.location"), null, 1L);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to handle spawn join teleport for " + player.getName() + ": " + e.getMessage());
                        }
                    }, null);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to schedule spawn join teleport for " + player.getName() + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            // A failing spawn teleport must never break the join event itself.
            plugin.getLogger().warning("Failed to handle spawn join teleport: " + e.getMessage());
        }
    }

    private void teleportToConfigLocation(Player player, String path) {
        teleportToConfigLocation(player, path, !"spawn.location".equals(path));
    }

    /**
     * @param fallbackToSpawn when the first-spawn location is unusable, fall
     *                        back to the regular spawn location instead of
     *                        stranding the player. Never recurses: the spawn
     *                        path itself passes false.
     */
    private void teleportToConfigLocation(Player player, String path, boolean fallbackToSpawn) {
        try {
            if (player == null || !player.isOnline()) return;
            FileConfiguration config = plugin.getSpawnConfig();
            if (config == null) return;
            // Consistent snapshot: writers mutate under synchronized(config).
            final String worldName;
            final double rawX;
            final double rawY;
            final double rawZ;
            final double rawYaw;
            final double rawPitch;
            final boolean spawnEnabled;
            final boolean firstSpawnEnabled;
            synchronized (config) {
                worldName = config.getString(path + ".world");
                rawX = config.getDouble(path + ".x");
                rawY = config.getDouble(path + ".y");
                rawZ = config.getDouble(path + ".z");
                rawYaw = config.getDouble(path + ".yaw");
                rawPitch = config.getDouble(path + ".pitch");
                spawnEnabled = config.getBoolean("spawn.enabled", false);
                firstSpawnEnabled = config.getBoolean("first-spawn.enabled", false);
            }
            // Re-check enabled for the requested path right before teleporting:
            // the module may have been disabled between join and this delayed
            // task (async hop + 1 tick). Never teleport to a disabled spawn.
            boolean enabledForPath = "spawn.location".equals(path) ? spawnEnabled : firstSpawnEnabled;
            // Defensive: any other path (future) requires spawn.enabled.
            if (!"spawn.location".equals(path) && !"first-spawn.location".equals(path)) {
                enabledForPath = spawnEnabled;
            }
            if (!enabledForPath) {
                return;
            }
            if (worldName == null) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location is not set correctly. Contact an admin!"));
                plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": missing world at '" + path + ".world'.");
                tryFallback(player, fallbackToSpawn && spawnEnabled);
                return;
            }

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                // worldName comes from config and may contain MiniMessage-looking
                // characters ('<', '>'): escape it so it cannot break parsing
                // (or inject formatting) into this message.
                try {
                    String safeWorld = plugin.getMiniMessage().escapeTags(worldName);
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn world '" + safeWorld + "' not found. Contact an admin!"));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to format spawn message: " + e.getMessage());
                    player.sendMessage(Component.text("Spawn world '" + worldName + "' not found. Contact an admin!"));
                }
                plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": world '" + worldName + "' not found (path '" + path + "').");
                tryFallback(player, fallbackToSpawn && spawnEnabled);
                return;
            }

            Location loc;
            if (!Double.isFinite(rawX) || !Double.isFinite(rawY) || !Double.isFinite(rawZ)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location is not set correctly. Contact an admin!"));
                plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": non-finite coordinates at '" + path + "'.");
                tryFallback(player, fallbackToSpawn && spawnEnabled);
                return;
            }
            float yaw = Double.isFinite(rawYaw) ? (float) rawYaw : 0f;
            float pitch = Double.isFinite(rawPitch) ? (float) rawPitch : 0f;

            if (rawY < world.getMinHeight() || rawY >= world.getMaxHeight()) {
                plugin.getLogger().warning("Spawn teleport for " + player.getName() + ": Y=" + rawY
                        + " out of bounds [" + world.getMinHeight() + "," + world.getMaxHeight()
                        + ") at '" + path + "', falling back to world spawn.");
                player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Spawn location was out of bounds, using world spawn instead. Contact an admin!"));
                loc = world.getSpawnLocation().clone();
            } else {
                loc = new Location(world, rawX, rawY, rawZ, yaw, pitch);
            }
            validateSpawnThenTeleport(player, world, loc, path, fallbackToSpawn);
        } catch (Exception e) {
            // Runs on a delayed scheduler task after join: must never throw.
            plugin.getLogger().warning("Spawn teleport failed for "
                    + (player != null ? player.getName() : "<unknown>") + ": " + e.getMessage());
        }
    }

    private void validateSpawnThenTeleport(Player player, World world, Location requested, String path, boolean fallbackToSpawn) {
        if (player == null || world == null || requested == null) return;
        Location target = requested.clone();
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, target.getBlockX() >> 4, target.getBlockZ() >> 4, () -> {
                Location safe = resolveSafeSpawnLocation(world, target, path);
                Location validated = safe == null ? null : safe.clone();
                try {
                    player.getScheduler().run(plugin, entityTask -> {
                        if (!player.isOnline()) return;
                        if (validated == null) {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location could not be verified as safe. Contact an admin."));
                            tryFallback(player, fallbackToSpawn);
                            return;
                        }
                        // Re-check enabled on the entity thread immediately
                        // before initiating teleportAsync (matches Spawn.java
                        // command path): the module may have been disabled or
                        // reloaded during the region hop. Never teleport to a
                        // disabled spawn; never fall back to one either.
                        boolean stillEnabled = false;
                        try {
                            FileConfiguration live = plugin.getSpawnConfig();
                            if (live != null) {
                                synchronized (live) {
                                    if ("spawn.location".equals(path)) {
                                        stillEnabled = live.getBoolean("spawn.enabled", false);
                                    } else if ("first-spawn.location".equals(path)) {
                                        stillEnabled = live.getBoolean("first-spawn.enabled", false);
                                    } else {
                                        stillEnabled = live.getBoolean("spawn.enabled", false);
                                    }
                                }
                            } else {
                                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
                                return;
                            }
                        } catch (Exception e) {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
                            return;
                        }
                        if (!stillEnabled) {
                            // Disabled mid-hop: abort without fallback — the
                            // fallback target re-checks enabled itself and may
                            // also be disabled.
                            return;
                        }
                        initiateSpawnTeleport(player, validated, fallbackToSpawn);
                    }, null);
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not schedule spawn teleport for " + player.getUniqueId() + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            try {
                player.getScheduler().run(plugin, entityTask -> {
                    if (player.isOnline()) player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location could not be verified as safe. Contact an admin."));
                }, null);
            } catch (Exception ignored) {}
            plugin.getLogger().warning("Could not schedule spawn safety check for " + player.getUniqueId() + ": " + e.getMessage());
        }
    }

    private void initiateSpawnTeleport(Player player, Location loc, boolean fallbackToSpawn) {
        try {
            player.teleportAsync(loc).thenAccept(success -> {
                // thenAccept runs off the entity thread: hop back onto the
                // EntityScheduler before any sendMessage/fallback scheduling
                // (Folia scheduler retained).
                try {
                    player.getScheduler().run(plugin, t -> {
                        if (!Boolean.TRUE.equals(success) && player.isOnline()) {
                            // First-spawn failed (e.g. target chunk unavailable): fall
                            // back to the regular spawn instead of stranding the
                            // player. Now on the entity thread — re-check
                            // online state and never throw.
                            if (fallbackToSpawn) {
                                try {
                                    FileConfiguration cfg = plugin.getSpawnConfig();
                                    boolean enabled = false;
                                    if (cfg != null) {
                                        synchronized (cfg) {
                                            enabled = cfg.getBoolean("spawn.enabled", false);
                                        }
                                    }
                                    if (enabled && player.isOnline()) {
                                        player.getScheduler().run(plugin, t2 -> teleportToConfigLocation(player, "spawn.location", false), null);
                                        return;
                                    }
                                } catch (Exception ex) {
                                    plugin.getLogger().warning("Spawn fallback failed for " + player.getName() + ": " + ex.getMessage());
                                }
                            }
                            try {
                                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again or contact an admin!"));
                            } catch (Exception ex) {
                                plugin.getLogger().warning("Failed to notify " + player.getName() + " about spawn teleport failure: " + ex.getMessage());
                            }
                        }
                    }, null);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to schedule spawn teleport notify for " + player.getName() + ": " + ex.getMessage());
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Spawn teleport failed for " + player.getUniqueId() + ": " + e.getMessage());
            if (fallbackToSpawn) tryFallback(player, true);
        }
    }

    /**
     * Spawn safety: when feet/head at the configured location are obstructed,
     * scan up (max +5, same pattern as TPAManager arrival) for feet+head
     * passable with solid ground below. If no candidate can be verified safe,
     * fail closed rather than sending the player to an unsafe or unchecked spot.
     */
    private Location resolveSafeSpawnLocation(World world, Location loc, String path) {
        try {
            if (world == null || loc == null) return loc;
            if (isSpawnSpotSafe(loc)) return loc;
            String reason = unsafeSpawnReason(loc);
            if (reason == null) reason = "obstructed";
            Location found = null;
            int foundOffset = 0;
            for (int i = 1; i <= 5; i++) {
                Location cand = loc.clone().add(0, i, 0);
                if (cand.getY() + 1 >= world.getMaxHeight()) break;
                if (cand.getY() < world.getMinHeight()) continue;
                if (isSpawnSpotSafe(cand)) {
                    found = cand;
                    foundOffset = i;
                    break;
                }
            }
            if (found != null) {
                plugin.getLogger().fine("Spawn location at '" + path + "' unsafe (" + reason + "), using safe spot +" + foundOffset + " up.");
                return found;
            }
            return null;
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check spawn safety at '" + path + "': " + e.getMessage());
            return null;
        }
    }

    private boolean isSpawnSpotSafe(Location cand) {
        return unsafeSpawnReason(cand) == null;
    }

    /**
     * Null when safe; otherwise a reason. Lookup failures are unsafe.
     */
    private String unsafeSpawnReason(Location cand) {
        try {
            org.bukkit.block.Block feet = cand.getBlock();
            org.bukkit.block.Block head = cand.clone().add(0, 1, 0).getBlock();
            org.bukkit.block.Block ground = cand.clone().add(0, -1, 0).getBlock();
            if (!feet.isPassable() || !head.isPassable()) return "obstructed";
            String groundName;
            boolean solid;
            try {
                solid = ground.getType().isSolid();
                groundName = ground.getType().name();
            } catch (Exception e) {
                return "unable to verify ground";
            }
            if (!solid) return "no solid ground below";
            // Leaves are solid safe support (e.g. treehouse spawns): do not
            // reject them as ground. Damaging/unsafe validation below stays.
            try {
                if (isDamagingSpawnGround(groundName)
                        || isAlwaysUnsafeSpawnBlock(groundName)) return "unsafe ground below";
                String feetName = feet.getType().name();
                String headName = head.getType().name();
                if (isAlwaysUnsafeSpawnBlock(feetName) || isAlwaysUnsafeSpawnBlock(headName)
                        || isDamagingSpawnGround(feetName) || isDamagingSpawnGround(headName)
                        || feetName.contains("LEAVES") || headName.contains("LEAVES")) return "unsafe headroom";
            } catch (Exception e) {
                return "unable to verify blocks";
            }
        } catch (Exception e) {
            return "unable to verify location";
        }
        return null;
    }

    /**
     * Damaging ground types (magma, campfire, cactus, wither rose, berry
     * bush — RTPManager ground rejects). Name-based, no Material import.
     */
    private static boolean isDamagingSpawnGround(String materialName) {
        if (materialName == null) return false;
        return switch (materialName) {
            case "LAVA", "WATER", "FIRE",
                    "CACTUS", "MAGMA_BLOCK",
                    "CAMPFIRE", "SOUL_CAMPFIRE",
                    "WITHER_ROSE", "SWEET_BERRY_BUSH" -> true;
            default -> false;
        };
    }

    private static boolean isAlwaysUnsafeSpawnBlock(String materialName) {
        if (materialName == null) return true;
        return switch (materialName) {
            case "BEDROCK", "BARRIER", "PORTAL", "NETHER_PORTAL", "END_PORTAL",
                    "ENDER_PORTAL", "END_GATEWAY", "POWDER_SNOW" -> true;
            default -> false;
        };
    }

    /**
     * Fallback helper: tries the regular spawn location when the first-spawn
     * location turned out to be unusable. No-op unless explicitly allowed.
     */
    private void tryFallback(Player player, boolean allowed) {
        if (!allowed || player == null || !player.isOnline()) return;
        try {
            teleportToConfigLocation(player, "spawn.location", false);
        } catch (Exception e) {
            plugin.getLogger().warning("Spawn fallback failed for " + player.getName() + ": " + e.getMessage());
        }
    }
}
