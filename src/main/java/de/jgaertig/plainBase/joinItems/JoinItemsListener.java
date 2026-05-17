package de.jgaertig.plainBase.joinItems;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
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
            if (requiredFlag != null) {
                List<String> flags = itemsSection.getStringList(key + ".flags");
                if (!flags.contains(requiredFlag)) continue;
            }

            int slot = itemsSection.getInt(key + ".slot");
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase();
        if (message.startsWith("/clear") || message.startsWith("/minecraft:clear")) {
            handleReGive(event.getPlayer(), message, "re-give-after-/clear");
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> {
            giveConfiguredItems(event.getPlayer(), "re-give-after-death");
        }, null, 5L);
    }

    private void handleReGive(Player sender, String message, String flag) {
        String[] args = message.split(" ");
        Player target = (args.length == 1) ? sender : Bukkit.getPlayer(args[1]);
        if (target != null && target.isOnline()) {
            target.getScheduler().runDelayed(plugin, task -> giveConfiguredItems(target, flag), null, 3L);
        }
    }

    private boolean isJoinItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(joinItemKey, PersistentDataType.STRING);
    }
}