package de.jgaertig.plainBase.menu;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MenuManager {

    private final PlainBase plugin;
    private final Map<String, MenuDefinition> menus = new ConcurrentHashMap<>();

    public record MenuDefinition(String name, String title, int size,
                                 Material fillMaterial, Map<Integer, ItemDefinition> items) {

        public Component buildTitle(PlainBase plugin, Player viewer) {
            return plugin.getMiniMessage().deserialize(plugin.applyPlaceholders(viewer, title));
        }
    }

    public record ItemDefinition(Material material, int amount, String name, List<String> lore,
                                 String sound, boolean close, List<String> commands, String message) {
    }

    /**
     * Holder used to identify our menus in inventory events.
     */
    public static final class MenuHolder implements InventoryHolder {
        private final String menuName;
        private Inventory inventory;

        public MenuHolder(String menuName) {
            this.menuName = menuName;
        }

        public String getMenuName() {
            return menuName;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        @NotNull
        public Inventory getInventory() {
            return inventory;
        }
    }

    public MenuManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public void reloadMenus() {
        menus.clear();
        FileConfiguration config = plugin.getMenuConfig();
        if (config == null) return;

        ConfigurationSection section = config.getConfigurationSection("menus");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String title = section.getString(key + ".title", key);
            int size = section.getInt(key + ".size", 27);

            Material fill = null;
            String fillString = section.getString(key + ".fill-material");
            if (fillString != null && !fillString.isEmpty()) {
                fill = Material.matchMaterial(fillString);
            }

            Map<Integer, ItemDefinition> items = new LinkedHashMap<>();
            ConfigurationSection itemsSection = section.getConfigurationSection(key + ".items");
            if (itemsSection != null) {
                for (String slotKey : itemsSection.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(slotKey);
                        ItemDefinition def = parseItem(itemsSection, slotKey);
                        if (def != null) items.put(slot, def);
                    } catch (NumberFormatException ignored) {
                        plugin.getLogger().warning("Invalid slot number '" + slotKey + "' in menu '" + key + "'");
                    }
                }
            }

            menus.put(key, new MenuDefinition(key, title, size, fill, items));
        }
    }

    private ItemDefinition parseItem(ConfigurationSection itemsSection, String key) {
        Material material = Material.matchMaterial(itemsSection.getString(key + ".material", "STONE"));
        if (material == null) {
            plugin.getLogger().warning("Invalid material in menu item '" + key + "'");
            return null;
        }

        int amount = itemsSection.getInt(key + ".amount", 1);
        String name = itemsSection.getString(key + ".name", "");
        List<String> lore = itemsSection.getStringList(key + ".lore");
        String sound = itemsSection.getString(key + ".sound", "");
        boolean close = itemsSection.getBoolean(key + ".close", false);
        List<String> commands = itemsSection.getStringList(key + ".commands");
        String message = itemsSection.getString(key + ".message", "");

        return new ItemDefinition(material, amount, name, lore, sound, close, commands, message);
    }

    public Set<String> getMenuNames() {
        return menus.keySet();
    }

    public boolean hasMenu(String name) {
        return menus.containsKey(name);
    }

    public MenuDefinition getMenu(String name) {
        return menus.get(name);
    }

    public boolean openMenu(Player player, String name) {
        MenuDefinition menu = menus.get(name);
        if (menu == null) return false;

        int size = normalizeSize(menu.size());
        MenuHolder holder = new MenuHolder(name);
        Inventory inv = Bukkit.createInventory(holder, size, menu.buildTitle(plugin, player));
        holder.setInventory(inv);

        // fill empty slots with fill material
        if (menu.fillMaterial() != null) {
            ItemStack fill = new ItemStack(menu.fillMaterial());
            ItemMeta meta = fill.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.empty());
                fill.setItemMeta(meta);
            }
            for (int i = 0; i < size; i++) {
                inv.setItem(i, fill.clone());
            }
        }

        for (Map.Entry<Integer, ItemDefinition> entry : menu.items().entrySet()) {
            if (entry.getKey() < 0 || entry.getKey() >= size) continue;
            inv.setItem(entry.getKey(), buildItem(plugin, player, entry.getValue()));
        }

        player.openInventory(inv);
        return true;
    }

    private ItemStack buildItem(PlainBase plugin, Player viewer, ItemDefinition def) {
        int amount = Math.min(Math.max(1, def.amount()), def.material().getMaxStackSize());
        ItemStack item = new ItemStack(def.material(), amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (def.name() != null && !def.name().isEmpty()) {
            meta.displayName(plugin.getMiniMessage().deserialize(plugin.applyPlaceholders(viewer, def.name())));
        }

        List<Component> lore = new ArrayList<>();
        for (String line : def.lore()) {
            lore.add(plugin.getMiniMessage().deserialize(plugin.applyPlaceholders(viewer, line)));
        }
        if (!lore.isEmpty()) meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    public void createMenu(String name) {
        FileConfiguration config = plugin.getMenuConfig();
        String path = "menus." + name;
        config.set(path + ".title", "<gray>" + name);
        config.set(path + ".size", 27);
        config.set(path + ".items", null);
        plugin.saveMenuConfig();
        reloadMenus();
    }

    public void deleteMenu(String name) {
        FileConfiguration config = plugin.getMenuConfig();
        config.set("menus." + name, null);
        plugin.saveMenuConfig();
        reloadMenus();
    }

    private int normalizeSize(int size) {
        if (size < 9) return 9;
        if (size > 54) return 54;
        // round up to a multiple of 9
        return ((size + 8) / 9) * 9;
    }
}