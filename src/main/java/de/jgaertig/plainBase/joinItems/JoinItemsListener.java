package de.jgaertig.plainBase.joinItems;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        giveJoinItems(event.getPlayer());
    }

    private void giveJoinItems(Player player) {
        ConfigurationSection itemsSection = plugin.getJoinItemsConfig().getConfigurationSection("items");
        if (itemsSection == null) return;

        for (String key : itemsSection.getKeys(false)) {
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

                // Hier speichern wir die ID aus der Config (z.B. "navigator")
                meta.getPersistentDataContainer().set(joinItemKey, PersistentDataType.STRING, key);

                item.setItemMeta(meta);
            }

            player.getInventory().setItem(slot, item);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !isJoinItem(item)) return;

        // ID aus dem Item auslesen
        String configKey = item.getItemMeta().getPersistentDataContainer().get(joinItemKey, PersistentDataType.STRING);

        if (configKey != null) {
            List<String> commands = plugin.getJoinItemsConfig().getStringList("items." + configKey + ".commands");
            for (String cmd : commands) {
                event.getPlayer().performCommand(cmd.replace("%player%", event.getPlayer().getName()));
            }
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (isJoinItem(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isJoinItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (plugin.getJoinItemsConfig().getBoolean("settings.lock-items", true)) {
            if (isJoinItem(event.getCurrentItem()) || isJoinItem(event.getCursor())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isJoinItem(event.getMainHandItem()) || isJoinItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    private boolean isJoinItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(joinItemKey, PersistentDataType.STRING);
    }
}