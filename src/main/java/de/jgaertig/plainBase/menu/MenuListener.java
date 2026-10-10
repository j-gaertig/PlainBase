package de.jgaertig.plainBase.menu;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MenuListener implements Listener {

    private final PlainBase plugin;

    public MenuListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    private MenuManager.MenuHolder getMenuHolder(Inventory inventory) {
        if (inventory == null) return null;
        if (inventory.getHolder() instanceof MenuManager.MenuHolder holder) return holder;
        return null;
    }

    /**
     * Menus are always locked GUIs: clicks in the player's own inventory
     * (bottom) are always blocked so no items can move in or out of the menu.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        MenuManager.MenuHolder holder = getMenuHolder(top);
        if (holder == null) return;

        // Our menu is open — no item may ever leave the menu, even if the
        // menu definition was deleted or reloaded in the meantime.
        event.setCancelled(true);

        MenuManager mgr = plugin.getMenuManager();
        if (mgr == null) return;

        MenuManager.MenuDefinition menu = mgr.getMenu(holder.getMenuName());
        if (menu == null) return;

        int rawSlot = event.getRawSlot();

        // Click in the player's own inventory (bottom) — always blocked
        if (rawSlot >= top.getSize()) {
            return;
        }

        Map<Integer, MenuManager.ItemDefinition> items = menu.items();
        MenuManager.ItemDefinition def = items.get(rawSlot);
        if (def == null) return;

        if (def.close()) {
            player.closeInventory();
        }

        String sound = def.sound();
        if (sound != null && !sound.isEmpty()) {
            Sound s = resolveSound(sound.trim());
            if (s != null) {
                player.playSound(player.getLocation(), s, 1.0f, 1.0f);
            } else {
                plugin.getLogger().warning("Invalid sound '" + sound + "' in menu '" + menu.name() + "'");
            }
        }

        String message = def.message();
        if (message != null && !message.isEmpty()) {
            // A broken admin message template (bad MiniMessage) must never
            // break the click handler — fall back to plain text. The message
            // is expanded via MenuManager#applyPlaceholdersSafe so the
            // clicking player's name cannot inject MiniMessage tags.
            try {
                player.sendMessage(plugin.getMiniMessage().deserialize(MenuManager.applyPlaceholdersSafe(plugin, player, message)));
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid message '" + message + "' in menu '" + menu.name() + "': " + e.getMessage());
                try {
                    player.sendMessage(Component.text(message));
                } catch (Exception ignored) {
                }
            }
        }

        List<String> commands = def.commands();
        if (commands != null) {
            for (String cmd : commands) {
                if (cmd == null || cmd.trim().isEmpty()) continue;

                // Commands go to performCommand, never through MiniMessage, so
                // the raw name stays correct here (escaping would corrupt it).
                String finalCmd = plugin.applyPlaceholders(player, cmd);
                if (finalCmd.startsWith("/")) finalCmd = finalCmd.substring(1);

                player.performCommand(finalCmd);
            }
        }
    }

    /**
     * Resolves a configured sound name without any deprecated-for-removal API
     * ({@code Sound.valueOf}, {@code Registry#match}, {@code OldEnum#name()}).
     * Prefers the {@link Registry#SOUNDS} lookup via
     * {@link Registry#get(NamespacedKey)} so registry keys
     * ({@code minecraft:entity.player.levelup} or {@code entity.player.levelup})
     * keep working across Paper updates, with a fallback to legacy Bukkit
     * enum names ({@code ENTITY_PLAYER_LEVELUP}) for existing menu.yml files.
     * The fallback compares normalized registry keys ('.' and '_' treated as
     * equal, e.g. {@code BLOCK_NOTE_BLOCK_PLING} matches
     * {@code minecraft:block.note_block.pling}) and never touches deprecated APIs.
     *
     * @return the sound, or null when the name matches neither form
     */
    private Sound resolveSound(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        // 1. Direct registry-key lookup (mirrors the old Registry#match
        // normalization: lowercase + whitespace to underscore, minecraft
        // namespace by default via NamespacedKey#fromString).
        try {
            String filtered = trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
            NamespacedKey key = NamespacedKey.fromString(filtered);
            if (key != null) {
                Sound direct = Registry.SOUNDS.get(key);
                if (direct != null) return direct;
            }
        } catch (Exception ignored) {
            // Fall through to the legacy-name scan below.
        }

        // 2. Legacy Bukkit enum names without deprecated OldEnum#name():
        // compare against registry keys with '.' and '_' normalized.
        String normalized = trimmed.toLowerCase(Locale.ROOT).replace('.', '_');
        int colon = normalized.lastIndexOf(':');
        if (colon >= 0) normalized = normalized.substring(colon + 1);
        for (Sound s : Registry.SOUNDS) {
            NamespacedKey k = Registry.SOUNDS.getKey(s);
            if (k == null) continue;
            String candidate = k.getKey().replace('.', '_');
            if (candidate.equalsIgnoreCase(normalized)) return s;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Inventory top = event.getView().getTopInventory();
        MenuManager.MenuHolder holder = getMenuHolder(top);
        if (holder == null) return;

        // Dragging inside or into our menu is always cancelled — the bottom
        // inventory is locked too, so nothing can move in or out of the menu.
        event.setCancelled(true);
    }

    /**
     * M1 creative-inventory guard (mirrors JoinItemsListener#onCreativeClick):
     * without this, creative-mode clicks bypass onClick and can take menu
     * items out of the GUI.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Inventory top = event.getView().getTopInventory();
        MenuManager.MenuHolder holder = getMenuHolder(top);
        if (holder == null) return;

        event.setCancelled(true);
    }
}