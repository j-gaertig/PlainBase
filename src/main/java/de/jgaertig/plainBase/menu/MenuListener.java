package de.jgaertig.plainBase.menu;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

import java.util.List;
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        MenuManager.MenuHolder holder = getMenuHolder(top);
        if (holder == null) return;

        // Our menu is open — no item may ever leave the menu, even if the
        // menu definition was deleted or reloaded in the meantime.
        event.setCancelled(true);

        MenuManager.MenuDefinition menu = plugin.getMenuManager().getMenu(holder.getMenuName());
        if (menu == null) return;

        int rawSlot = event.getRawSlot();

        // Click in the player's own inventory (bottom)
        if (rawSlot >= top.getSize()) {
            if (!menu.showPlayerInventory()) {
                event.setCancelled(true);
                return;
            }
            // Allow plain clicks inside the player's inventory, but never
            // allow shift-clicks or hotbar swaps that would transfer items
            // into the menu.
            if (event.isShiftClick()
                    || event.getAction() == InventoryAction.HOTBAR_SWAP
                    || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
                event.setCancelled(true);
            }
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
            try {
                Sound s = Sound.valueOf(sound.toUpperCase());
                player.playSound(player.getLocation(), s, 1.0f, 1.0f);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid sound '" + sound + "' in menu '" + menu.name() + "'");
            }
        }

        String message = def.message();
        if (message != null && !message.isEmpty()) {
            player.sendMessage(plugin.getMiniMessage().deserialize(plugin.applyPlaceholders(player, message)));
        }

        List<String> commands = def.commands();
        if (commands != null) {
            for (String cmd : commands) {
                if (cmd == null || cmd.trim().isEmpty()) continue;

                String finalCmd = plugin.applyPlaceholders(player, cmd);
                if (finalCmd.startsWith("/")) finalCmd = finalCmd.substring(1);

                player.performCommand(finalCmd);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Inventory top = event.getView().getTopInventory();
        MenuManager.MenuHolder holder = getMenuHolder(top);
        if (holder == null) return;

        MenuManager.MenuDefinition menu = plugin.getMenuManager().getMenu(holder.getMenuName());
        if (menu == null) return;

        // Dragging into the menu is always cancelled; dragging inside the
        // player's own inventory is allowed only when show-player-inventory is on
        for (int slot : event.getRawSlots()) {
            if (slot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
        if (!menu.showPlayerInventory()) {
            event.setCancelled(true);
        }
    }
}