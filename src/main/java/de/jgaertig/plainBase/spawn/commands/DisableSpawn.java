package de.jgaertig.plainBase.spawn.commands;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.command.CommandExecutor;

public class DisableSpawn implements CommandExecutor {

    private final PlainBase plugin;

    public DisableSpawn(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
         if (!plugin.getSpawnConfig().getBoolean("commands.disablespawn.enabled", true)) {
             sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
             return true;
         }

        var config = plugin.getSpawnConfig();
        config.set("spawn.enabled", false);
        plugin.saveSpawnConfig();

        sender.sendMessage(plugin.getMiniMessage().deserialize("<green>Spawn has been disabled!"));

        return true;
    }
}
