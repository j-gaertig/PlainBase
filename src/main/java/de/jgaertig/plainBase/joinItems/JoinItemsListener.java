package de.jgaertig.plainBase.joinItems;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JoinItemsListener implements Listener {

    private final PlainBase plugin;
    private final NamespacedKey joinItemKey;

    public JoinItemsListener(PlainBase plugin) {
        this.plugin = plugin;
        this.joinItemKey = new NamespacedKey(plugin, "join_item_id");
    }

    private boolean isActionRestricted(Player player, ItemStack item, String restrictionFlag) {
        if (item == null || !isJoinItem(item)) return false;
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getJoinItemsConfig();
        if (cfg == null) return false;

        // Code default matches the shipped joinitems.yml (op-bypass: false):
        // OPs are restricted like everyone else unless the admin opts in.
        if (player.isOp() && cfg.getBoolean("settings.op-bypass", false)) {
            return false;
        }

        String configKey = item.getItemMeta().getPersistentDataContainer().get(joinItemKey, PersistentDataType.STRING);
        List<String> flags = cfg.getStringList("items." + configKey + ".flags");

        return flags.contains(restrictionFlag);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        giveConfiguredItems(event.getPlayer(), null);
    }

    private void giveConfiguredItems(Player player, String requiredFlag) {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getJoinItemsConfig();
        if (cfg == null) return;
        ConfigurationSection itemsSection = cfg.getConfigurationSection("items");
        if (itemsSection == null) return;

        for (String key : itemsSection.getKeys(false)) {
            // SAFETY: an item must be explicitly enabled to be handed out.
            // Default is "true" in code so pre-existing configs (saved before
            // this flag existed) keep working unchanged after an update; the
            // shipped example config sets every example item to "false" so
            // simply enabling this module does not silently overwrite a
            // player's inventory with items nobody asked for.
            if (!itemsSection.getBoolean(key + ".enabled", true)) continue;

            // J1 duplication warning (no logic change): an item that is
            // re-given after /clear or death but is NOT protected with
            // no-inventory-move (and no-drop) can be duplicated — move the
            // original into a chest, then trigger the re-give. Warn once per
            // item key so admins notice the misconfiguration.
            warnOnUnsafeRegive(itemsSection, key);

            if (requiredFlag != null) {
                List<String> flags = itemsSection.getStringList(key + ".flags");
                if (!flags.contains(requiredFlag)) continue;
            }

            int slot = itemsSection.getInt(key + ".slot");
            // Slot validation: player inventory slots are 0-35. An invalid
            // slot must never throw or write elsewhere — skip with a warning.
            if (slot < 0 || slot >= 36) {
                plugin.getLogger().warning("Invalid slot '" + slot + "' for join item '" + key + "', skipping.");
                continue;
            }
            Material material = Material.matchMaterial(itemsSection.getString(key + ".material", "STONE"));
            String name = itemsSection.getString(key + ".name", "");
            List<String> loreStrings = itemsSection.getStringList(key + ".lore");

            if (material == null) continue;

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            if (meta != null) {
                meta.displayName(safeDeserialize(name, "name of join item '" + key + "'"));
                List<Component> lore = new ArrayList<>();
                for (String s : loreStrings) {
                    lore.add(safeDeserialize(s, "lore of join item '" + key + "'"));
                }
                meta.lore(lore);

                if (meta instanceof SkullMeta skullMeta) {
                    String owner = itemsSection.getString(key + ".skull-owner");
                    if (owner != null && !owner.isEmpty()) {
                        if (owner.equals("%player%")) {
                            skullMeta.setOwningPlayer(player);
                        } else {
                            // Never do a blocking profile lookup on the server
                            // thread: Bukkit.getOfflinePlayer(String) may hit
                            // the network (Mojang API) and stall the join.
                            // Only use an already-known profile (online or
                            // cached); an unknown name leaves the skull
                            // without an owner instead of blocking.
                            OfflinePlayer cached = resolveCachedOfflinePlayer(owner);
                            if (cached != null) {
                                skullMeta.setOwningPlayer(cached);
                            }
                        }
                    }
                }

                meta.getPersistentDataContainer().set(joinItemKey, PersistentDataType.STRING, key);
                item.setItemMeta(meta);
            }
            // Overwrite guard: never destroy a player's existing items (e.g.
            // diamonds). Only place the join item on an empty slot unless the
            // config explicitly opts in via items.<key>.overwrite: true.
            boolean overwrite = itemsSection.getBoolean(key + ".overwrite", false);
            if (!overwrite) {
                ItemStack existing = player.getInventory().getItem(slot);
                if (existing != null && !existing.getType().isAir()) {
                    continue;
                }
            }
            player.getInventory().setItem(slot, item);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return;

        ItemStack item = event.getItem();
        if (item == null || !isJoinItem(item)) return;

        // Only right-click activates a join item — a left-click (attack, block
        // hit) must pass through and never be swallowed by the item.
        if (!event.getAction().name().contains("RIGHT")) return;

        event.setCancelled(true);

        String configKey = item.getItemMeta().getPersistentDataContainer().get(joinItemKey, PersistentDataType.STRING);
        if (configKey != null) {
            org.bukkit.configuration.file.FileConfiguration cfg = plugin.getJoinItemsConfig();
            if (cfg == null) return;
            List<String> commands = cfg.getStringList("items." + configKey + ".commands");
            for (String cmd : commands) {
                if (cmd == null || cmd.trim().isEmpty()) continue;

                String finalCmd = cmd.replace("%player%", event.getPlayer().getName());
                if (finalCmd.startsWith("/")) {
                    finalCmd = finalCmd.substring(1);
                }

                event.getPlayer().performCommand(finalCmd);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isActionRestricted(event.getPlayer(), event.getItemDrop().getItemStack(), "no-drop")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (isActionRestricted(player, event.getCurrentItem(), "no-inventory-move") ||
                    isActionRestricted(player, event.getCursor(), "no-inventory-move")) {
                event.setCancelled(true);
            }

            if (!event.isCancelled() && event.getClick().isKeyboardClick()) {
                ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
                if (isActionRestricted(player, hotbarItem, "no-inventory-move")) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreativeClick(InventoryCreativeEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            // Fallback without a new key: "no-inventory-move" also blocks the
            // creative path, so items flagged only with it cannot be smuggled
            // out via creative clicks.
            if (isActionRestricted(player, event.getCurrentItem(), "no-creative-move") ||
                    isActionRestricted(player, event.getCursor(), "no-creative-move") ||
                    isActionRestricted(player, event.getCurrentItem(), "no-inventory-move") ||
                    isActionRestricted(player, event.getCursor(), "no-inventory-move")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            // Fallback without a new key: "no-inventory-move" also blocks
            // dragging, so items flagged only with it cannot be smuggled out
            // via the drag path.
            if (isActionRestricted(player, event.getOldCursor(), "no-drag") ||
                    isActionRestricted(player, event.getOldCursor(), "no-inventory-move")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        // Fallback without a new key (mirrors onCreativeClick/onDrag):
        // "no-inventory-move" also blocks the offhand swap, so items flagged
        // only with it cannot be smuggled out via the swap path.
        if (isActionRestricted(event.getPlayer(), event.getMainHandItem(), "no-swap") ||
                isActionRestricted(event.getPlayer(), event.getOffHandItem(), "no-swap") ||
                isActionRestricted(event.getPlayer(), event.getMainHandItem(), "no-inventory-move") ||
                isActionRestricted(event.getPlayer(), event.getOffHandItem(), "no-inventory-move")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // A cancelled /clear never actually cleared anything — re-giving
        // items for it would duplicate them. Exact match only: "/clearly"
        // or similar commands must not trigger a re-give.
        if (event.isCancelled()) return;
        String message = event.getMessage().toLowerCase(Locale.ROOT);
        if (message.equals("/clear") || message.startsWith("/clear ")
                || message.equals("/minecraft:clear") || message.startsWith("/minecraft:clear ")) {
            // Minimal dupe guard: a filtered clear ("/clear <player> stone")
            // only removes the filtered items, so re-giving join items for it
            // would duplicate them. Re-give only for unfiltered clears
            // ("/clear" or "/clear <player>"). Vanilla syntax is
            // /clear [<targets> [<item> [<maxCount>]]], so anything beyond
            // command + target carries an item filter. Split on \\s+ (not
            // " "): multiple spaces must not shift the argument indices.
            // The raw message (original case) is used for arg extraction so
            // exact-name lookup below sees the real spelling; `message` stays
            // the lowercased copy for command matching only.
            String rawMessage = event.getMessage();
            if (rawMessage.trim().split("\\s+").length > 2) return;
            handleReGive(event.getPlayer(), rawMessage, "re-give-after-/clear");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        // Death dupe guard: items that are re-given after death must not
        // also drop on the ground, or every death would duplicate them.
        // Only items whose own config key carries the re-give-after-death
        // flag are removed — one-shot join items without the flag keep
        // their normal drop behaviour.
        // "no-drop" items must never drop either: dropping them on death
        // would bypass the no-drop protection (and duplicate re-given ones).
        if (event.getKeepInventory()) return;
        try {
            event.getDrops().removeIf(item -> isRegiveAfterDeathJoinItem(item) || isNoDropJoinItem(item));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to filter join items from death drops: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        // Hopper / hopper-minecart siphoning: a join item must never be
        // moved by non-player automation. No player context exists here,
        // so any join item involvement cancels the move (safe default).
        if (isJoinItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent event) {
        // A join item must never be consumed as a crafting ingredient, and
        // crafting must never produce a join-item-looking result.
        if (isJoinItem(event.getRecipe().getResult())
                || isJoinItem(event.getCurrentItem())
                || isJoinItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (isJoinItem(ingredient)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        // Placing a join item into an item frame would smuggle it out of
        // the protected inventory.
        if (!(event.getRightClicked() instanceof ItemFrame)) return;
        Player player = event.getPlayer();
        if (isJoinItem(player.getInventory().getItemInMainHand())
                || isJoinItem(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        // Same smuggling path as item frames (see onInteractEntity): swapping
        // a join item onto an armor stand would move it out of the protected
        // inventory. (PlayerArmorStandManipulateEvent extends
        // PlayerInteractEntityEvent but is not caught above, which returns
        // early for non-ItemFrame entities.)
        Player player = event.getPlayer();
        if (isJoinItem(player.getInventory().getItemInMainHand())
                || isJoinItem(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // keepInventory guard: with the keep-inventory gamerule the player never
        // lost the items, so re-giving them here would duplicate every stack.
        // (GameRules, not the deprecated-for-removal GameRule constants.)
        if (Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRules.KEEP_INVENTORY))) return;
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) return;
            if (plugin.getJoinItemsConfig() == null) return;
            // Re-checked: the rule could have been toggled during the delay.
            if (Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRules.KEEP_INVENTORY))) return;
            giveConfiguredItems(player, "re-give-after-death");
        }, null, 5L);
    }

    private void handleReGive(Player sender, String rawMessage, String flag) {
        if (rawMessage == null) return;
        String[] args = rawMessage.trim().split("\\s+");
        if (args.length == 0) return;
        Player target = (args.length == 1) ? sender : resolveReGiveTarget(args[1]);
        if (target != null && target.isOnline()) {
            // Only re-give to self, or to others with explicit admin rights.
            // Without this check, "/clear <other>" from any player would hand
            // free items to that player (/clear farm).
            // NOTE: intentionally NO sender.isOp() shortcut — hasPermission()
            // already covers OPs via PermissionDefault.OP, while a bare isOp()
            // would defeat an explicit negation (-plainbase.admin).
            if (!target.equals(sender) && !sender.hasPermission("plainbase.admin")) {
                return;
            }
            target.getScheduler().runDelayed(plugin, task -> {
                if (!target.isOnline()) return;
                if (plugin.getJoinItemsConfig() == null) return;
                giveConfiguredItems(target, flag);
            }, null, 3L);
        }
    }

    /**
     * Resolves the /clear re-give target by name. Selectors (@a/@p/@r/@s/@e,
     * incl. "@p[...]" args) resolve to null — they must never trigger a
     * re-give (resolving them would either hit the wrong player or silently
     * skip, and mass-clear re-gives would duplicate items). Exact first:
     * Bukkit#getPlayer does prefix matching and could hand items to the
     * wrong player on a typo.
     */
    private static Player resolveReGiveTarget(String name) {
        if (name == null || name.startsWith("@")) return null;
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        try {
            return Bukkit.getPlayer(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True for join items whose own config entry carries the
     * re-give-after-death flag (i.e. items that will be re-given on respawn
     * and therefore must not drop on death).
     */
    private boolean isRegiveAfterDeathJoinItem(ItemStack item) {
        return joinItemHasFlag(item, "re-give-after-death");
    }

    /**
     * True for join items whose own config entry carries the no-drop flag
     * (i.e. items that must never leave the inventory, including via death
     * drops).
     */
    private boolean isNoDropJoinItem(ItemStack item) {
        return joinItemHasFlag(item, "no-drop");
    }

    private boolean joinItemHasFlag(ItemStack item, String flag) {
        if (!isJoinItem(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String configKey = meta.getPersistentDataContainer().get(joinItemKey, PersistentDataType.STRING);
        if (configKey == null) return false;
        try {
            return plugin.getJoinItemsConfig().getStringList("items." + configKey + ".flags")
                    .contains(flag);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isJoinItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(joinItemKey, PersistentDataType.STRING);
    }

    /**
     * MiniMessage-deserializes config text with a plain-text fallback: a
     * malformed tag in joinitems.yml must never break giving out items
     * (on join and on respawn, which share this path).
     */
    private Component safeDeserialize(String raw, String context) {
        if (raw == null) return Component.empty();
        try {
            return plugin.getMiniMessage().deserialize(raw);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to format " + context + ", using plain text: " + e.getMessage());
            return Component.text(raw);
        }
    }

    /**
     * Resolves an offline player without any blocking lookup: online players
     * first, then the profile cache only. Returns null when the name is not
     * known locally (no network I/O, safe on the server thread).
     */
    private static OfflinePlayer resolveCachedOfflinePlayer(String name) {
        try {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) return online;
            return Bukkit.getOfflinePlayerIfCached(name);
        } catch (Exception e) {
            return null;
        }
    }

    // J1: once-per-key guard so the unsafe re-give warning below does not spam
    // the console on every join.
    private final java.util.Set<String> regiveWarnedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * J1 duplication warning: logs when an item carries a re-give flag without
     * the matching movement protection. No logic is changed — this is purely
     * a misconfiguration hint.
     */
    private void warnOnUnsafeRegive(ConfigurationSection itemsSection, String key) {
        try {
            if (!regiveWarnedKeys.add(key)) return;
            List<String> flags = itemsSection.getStringList(key + ".flags");
            boolean hasRegive = flags.contains("re-give-after-/clear") || flags.contains("re-give-after-death");
            if (!hasRegive) return;
            boolean hasMoveLock = flags.contains("no-inventory-move");
            boolean hasDropLock = flags.contains("no-drop");
            if (!hasMoveLock || !hasDropLock) {
                plugin.getLogger().warning("Join item '" + key + "' has a re-give flag "
                        + "(re-give-after-/clear / re-give-after-death) without "
                        + (!hasMoveLock ? "'no-inventory-move'" + (!hasDropLock ? " and 'no-drop'" : "") : "'no-drop'")
                        + " — players can duplicate it by moving/dropping the original before the re-give. "
                        + "Add the missing flag(s) in modules/joinitems.yml unless duplication is intended.");
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed re-give safety check for join item '" + key + "': " + e.getMessage());
        }
    }
}