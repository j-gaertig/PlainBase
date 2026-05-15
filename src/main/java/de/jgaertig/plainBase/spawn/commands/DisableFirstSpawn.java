package de.jgaertig.plainBase.spawn.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class DisableFirstSpawn implements BasicCommand {

    private final PlainBase plugin;

    public DisableFirstSpawn(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!sender.isOp()) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        if (!plugin.getSpawnConfig().getBoolean("commands.disablefirstspawn.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        var config = plugin.getSpawnConfig();
        config.set("firstspawn.enabled", false);
        plugin.saveSpawnConfig();

        sender.sendMessage(plugin.getMiniMessage().deserialize("<green>First Spawn has been disabled!"));
    }
}
