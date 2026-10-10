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
            String raw = title != null ? MenuManager.applyPlaceholdersSafe(plugin, viewer, title) : name;
            if (raw == null) raw = name != null ? name : "";
            try {
                return plugin.getMiniMessage().deserialize(raw);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to format title of menu '" + name + "', using plain text.");
                return Component.text(raw);
            }
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
            if (inventory == null) {
                throw new IllegalStateException("MenuHolder for '" + menuName + "' has no inventory yet");
            }
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
            // A menu must be explicitly enabled to show up in /menu list|open.
            // Default "true" keeps pre-existing configs (saved before this
            // flag existed) working unchanged; the shipped example menu ships
            // disabled so a fresh install doesn't expose an unreviewed demo.
            if (!section.getBoolean(key + ".enabled", true)) continue;

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
                // Slots are 0-indexed: a menu with size 27 uses slots 0-26.
                // Out-of-range entries are skipped with a warning (a silent skip
                // would leave admins staring at an empty menu, e.g. slot 54 in
                // a size-27 menu from 1-indexed thinking).
                int maxSlot = normalizeSize(size) - 1;
                for (String slotKey : itemsSection.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(slotKey);
                        if (slot < 0 || slot > maxSlot) {
                            plugin.getLogger().warning("Slot '" + slotKey + "' in menu '" + key
                                    + "' is out of range (size " + size + " allows 0-indexed slots 0-"
                                    + maxSlot + "); item ignored.");
                            continue;
                        }
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

    /**
     * Synchronous best-effort close, called from stopModules() BEFORE
     * HandlerList.unregisterAll: the deferred {@link #closeAllMenus()} below
     * only schedules per-player EntityScheduler tasks, so unregistering
     * immediately afterwards would leave a race where an open menu's clicks
     * are no longer cancelled. This pass tries to close directly so menus
     * are confirmed closed before the listener is gone; where it throws
     * (Folia entity-thread-only access from the global thread) the deferred
     * backup covers it. Never throws.
     */
    public void closeAllMenusSyncBestEffort() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null) continue;
            try {
                Inventory top;
                try {
                    top = player.getOpenInventory().getTopInventory();
                } catch (Exception e) {
                    // Wrong thread on Folia — deferred closeAllMenus() retries
                    // on the entity thread.
                    continue;
                }
                if (top != null && top.getHolder() instanceof MenuHolder) {
                    try {
                        player.closeInventory();
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to close menu for " + player.getName() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to close menu (sync): " + e.getMessage());
            }
        }
    }

    /**
     * Deferred backup for {@link #closeAllMenusSyncBestEffort()}: closes every
     * open menu inventory. Called on module stop/reload before
     * the MenuListener is unregistered: an open menu whose clicks are no
     * longer cancelled would let players take items out of the GUI
     * (duplication/exploit risk).
     * <p>
     * Folia: inventory access is entity-thread-only, so the holder check and
     * the close hop per player via {@code player.getScheduler().run(...)}
     * (same pattern as TPAManager expiry notifications). Each player is
     * isolated in its own try/catch so one throwing player never aborts the
     * loop.
     */
    public void closeAllMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null) continue;
            try {
                player.getScheduler().run(plugin, (t) -> {
                    try {
                        if (!player.isOnline()) return;
                        Inventory top = player.getOpenInventory().getTopInventory();
                        if (top != null && top.getHolder() instanceof MenuHolder) {
                            player.closeInventory();
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to close menu for " + player.getName() + ": " + e.getMessage());
                    }
                }, null);
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to schedule menu close: " + e.getMessage());
            }
        }
    }

    public boolean openMenu(Player player, String name) {
        if (player == null || name == null) return false;
        MenuDefinition menu = menus.get(name);
        if (menu == null) return false;

        int size = normalizeSize(menu.size());
        MenuHolder holder = new MenuHolder(name);
        Component title;
        try {
            title = menu.buildTitle(plugin, player);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to build title for menu '" + name + "', using plain text.");
            title = Component.text(name);
        }
        Inventory inv;
        try {
            inv = Bukkit.createInventory(holder, size, title);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to open menu '" + name + "' for " + player.getName() + ": " + e.getMessage());
            return false;
        }
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

        Map<Integer, ItemDefinition> defs = menu.items();
        if (defs != null) {
            for (Map.Entry<Integer, ItemDefinition> entry : defs.entrySet()) {
                if (entry == null || entry.getKey() == null) continue;
                if (entry.getKey() < 0 || entry.getKey() >= size) continue;
                try {
                    inv.setItem(entry.getKey(), buildItem(plugin, player, entry.getValue()));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to build item for menu '" + name + "', skipping slot " + entry.getKey() + ".");
                }
            }
        }

        player.openInventory(inv);
        return true;
    }

    private ItemStack buildItem(PlainBase plugin, Player viewer, ItemDefinition def) {
        if (def == null || def.material() == null) return new ItemStack(Material.STONE);
        int amount = Math.min(Math.max(1, def.amount()), def.material().getMaxStackSize());
        ItemStack item = new ItemStack(def.material(), amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (def.name() != null && !def.name().isEmpty()) {
            String rawName = applyPlaceholdersSafe(plugin, viewer, def.name());
            if (rawName == null) rawName = "";
            try {
                meta.displayName(plugin.getMiniMessage().deserialize(rawName));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to format menu item name, using plain text.");
                meta.displayName(Component.text(rawName));
            }
        }

        List<Component> lore = new ArrayList<>();
        if (def.lore() != null) {
            for (String line : def.lore()) {
                if (line == null) continue;
                String raw = applyPlaceholdersSafe(plugin, viewer, line);
                if (raw == null) raw = "";
                try {
                    lore.add(plugin.getMiniMessage().deserialize(raw));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to format menu item lore line, using plain text.");
                    lore.add(Component.text(raw));
                }
            }
        }
        if (!lore.isEmpty()) meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Placeholder expansion safe for MiniMessage (same pattern as
     * TeamManager#msg): the viewer's own name is substituted escaped BEFORE
     * PlaceholderAPI runs, so a name containing MiniMessage tags can never
     * inject formatting or click events into titles, names, lore or messages.
     * The admin-authored template itself stays raw on purpose (MiniMessage by
     * design). Package-private for MenuListener (same package, no new API).
     */
    static String applyPlaceholdersSafe(PlainBase plugin, Player viewer, String template) {
        if (template == null) return null;
        String pre = viewer != null
                ? template.replace("%player%", plugin.getMiniMessage().escapeTags(viewer.getName()))
                : template;
        return plugin.applyPlaceholders(viewer, pre);
    }

    public void createMenu(String name) {
        FileConfiguration config = plugin.getMenuConfig();
        if (config == null) {
            plugin.getLogger().warning("Cannot create menu '" + name + "': menu.yml is not loaded.");
            return;
        }
        String path = "menus." + name;
        // The name is player-typed (/menu new) and lands inside a MiniMessage
        // template — escape it so tags in the name cannot inject formatting or
        // click events into the stored title (same pattern as TeamManager#msg).
        config.set(path + ".title", "<gray>" + plugin.getMiniMessage().escapeTags(name));
        config.set(path + ".size", 27);
        config.set(path + ".items", null);
        // Async persist: the in-memory config is already updated, so the
        // reloadMenus() below sees the change even before the disk write lands.
        plugin.saveMenuConfigAsync();
        reloadMenus();
    }

    public void deleteMenu(String name) {
        FileConfiguration config = plugin.getMenuConfig();
        if (config == null) {
            plugin.getLogger().warning("Cannot delete menu '" + name + "': menu.yml is not loaded.");
            return;
        }
        config.set("menus." + name, null);
        // Async persist (see createMenu): in-memory state is authoritative here.
        plugin.saveMenuConfigAsync();
        // Close open GUIs first: after reloadMenus() the MenuListener no longer
        // knows this menu, and clicks in a stale open inventory would no
        // longer be cancelled (item-takeout exploit).
        closeAllMenus();
        reloadMenus();
    }

    private int normalizeSize(int size) {
        if (size < 9) return 9;
        if (size > 54) return 54;
        // round up to a multiple of 9
        return ((size + 8) / 9) * 9;
    }
}