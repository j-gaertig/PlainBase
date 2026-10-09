package de.jgaertig.plainBase.joinItems;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
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

public class JoinItemsListener implements Listener {

    private final PlainBase plugin;
    private final NamespacedKey joinItemKey;

    public JoinItemsListener(PlainBase plugin) {
        this.plugin = plugin;
        this.joinItemKey = new NamespacedKey(plugin, "join_item_id");
    }

    private boolean isActionRestricted(Player player, ItemStack item, String restrictionFlag) {
        if (item == null || !isJoinItem(item)) return false;

        if (player.isOp() && plugin.getJoinItemsConfig().getBoolean("settings.op-bypass", true)) {
            return false;
        }

        String configKey = item.getItemMeta().getPersistentDataContainer().get(joinItemKey, PersistentDataType.STRING);
        List<String> flags = plugin.getJoinItemsConfig().getStringList("items." + configKey + ".flags");

        return flags.contains(restrictionFlag);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        giveConfiguredItems(event.getPlayer(), null);
    }

    private void giveConfiguredItems(Player player, String requiredFlag) {
        ConfigurationSection itemsSection = plugin.getJoinItemsConfig().getConfigurationSection("items");
        if (itemsSection == null) return;

        for (String key : itemsSection.getKeys(false)) {
            // SAFETY: an item must be explicitly enabled to be handed out.
            // Default is "true" in code so pre-existing configs (saved before
            // this flag existed) keep working unchanged after an update; the
            // shipped example config sets every example item to "false" so
            // simply enabling this module does not silently overwrite a
            // player's inventory with items nobody asked for.
            if (!itemsSection.getBoolean(key + ".enabled", true)) continue;

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
                meta.displayName(plugin.getMiniMessage().deserialize(name));
                List<Component> lore = new ArrayList<>();
                for (String s : loreStrings) {
                    lore.add(plugin.getMiniMessage().deserialize(s));
                }
                meta.lore(lore);

                if (meta instanceof SkullMeta skullMeta) {
                    String owner = itemsSection.getString(key + ".skull-owner");
                    if (owner != null) {
                        if (owner.equals("%player%")) {
                            skullMeta.setOwningPlayer(player);
                        } else {
                            skullMeta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(owner));
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

        event.setCancelled(true);

        if (event.getAction().name().contains("RIGHT")) {
            String configKey = item.getItemMeta().getPersistentDataContainer().get(joinItemKey, PersistentDataType.STRING);
            if (configKey != null) {
                List<String> commands = plugin.getJoinItemsConfig().getStringList("items." + configKey + ".commands");
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
            if (isActionRestricted(player, event.getCurrentItem(), "no-creative-move") ||
                    isActionRestricted(player, event.getCursor(), "no-creative-move")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (isActionRestricted(player, event.getOldCursor(), "no-drag")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isActionRestricted(event.getPlayer(), event.getMainHandItem(), "no-swap") ||
                isActionRestricted(event.getPlayer(), event.getOffHandItem(), "no-swap")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // A cancelled /clear never actually cleared anything — re-giving
        // items for it would duplicate them. Exact match only: "/clearly"
        // or similar commands must not trigger a re-give.
        if (event.isCancelled()) return;
        String message = event.getMessage().toLowerCase();
        if (message.equals("/clear") || message.startsWith("/clear ")
                || message.equals("/minecraft:clear") || message.startsWith("/minecraft:clear ")) {
            handleReGive(event.getPlayer(), message, "re-give-after-/clear");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        // Death dupe guard: items that are re-given after death must not
        // also drop on the ground, or every death would duplicate them.
        // Only items whose own config key carries the re-give-after-death
        // flag are removed — one-shot join items without the flag keep
        // their normal drop behaviour.
        if (event.getKeepInventory()) return;
        try {
            event.getDrops().removeIf(this::isRegiveAfterDeathJoinItem);
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

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) return;
            giveConfiguredItems(player, "re-give-after-death");
        }, null, 5L);
    }

    private void handleReGive(Player sender, String message, String flag) {
        String[] args = message.split(" ");
        Player target = (args.length == 1) ? sender : Bukkit.getPlayer(args[1]);
        if (target != null && target.isOnline()) {
            // Only re-give to self, or to others with explicit admin rights.
            // Without this check, "/clear <other>" from any player would hand
            // free items to that player (/clear farm).
            if (!target.equals(sender) && !sender.hasPermission("plainbase.admin") && !sender.isOp()) {
                return;
            }
            target.getScheduler().runDelayed(plugin, task -> { if (!target.isOnline()) return; giveConfiguredItems(target, flag); }, null, 3L);
        }
    }

    /**
     * True for join items whose own config entry carries the
     * re-give-after-death flag (i.e. items that will be re-given on respawn
     * and therefore must not drop on death).
     */
    private boolean isRegiveAfterDeathJoinItem(ItemStack item) {
        if (!isJoinItem(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String configKey = meta.getPersistentDataContainer().get(joinItemKey, PersistentDataType.STRING);
        if (configKey == null) return false;
        try {
            return plugin.getJoinItemsConfig().getStringList("items." + configKey + ".flags")
                    .contains("re-give-after-death");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isJoinItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(joinItemKey, PersistentDataType.STRING);
    }
}