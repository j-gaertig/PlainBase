package de.jgaertig.plainBase.team.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.team.TeamManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Single entry point for every /team subcommand. Two argument shapes:
 * - global actions first: /team accept|deny|leave|list [team]
 * - team-scoped actions: /team <team> invite|add|kick|setrole|request|info|deny-request ...
 */
public class TeamCommand implements BasicCommand {

    private static final Set<String> GLOBAL_ACTIONS = Set.of("accept", "deny", "leave", "list");
    private static final Set<String> TEAM_ACTIONS = Set.of("invite", "add", "kick", "setrole", "request", "info", "deny-request");

    private final PlainBase plugin;

    public TeamCommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        TeamManager teams = plugin.getTeamManager();

        if (!plugin.getConfig().getBoolean("modules.team", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }
        if (!plugin.getTeamConfig().getBoolean("team.enabled", true) || teams == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>The team system has been disabled."));
            return;
        }
        if (!plugin.getTeamConfig().getBoolean("team.commands.team.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        String first = args[0].toLowerCase();

        if (GLOBAL_ACTIONS.contains(first)) {
            handleGlobalAction(sender, teams, first, args);
            return;
        }

        // Otherwise args[0] must be a team id.
        if (!teams.teamExists(first)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Unknown team or subcommand: " + args[0]));
            return;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        handleTeamAction(sender, teams, first, args[1].toLowerCase(), args);
    }

    private void handleGlobalAction(CommandSender sender, TeamManager teams, String action, String[] args) {
        switch (action) {
            case "list" -> {
                if (!checkPermission(sender, "plainbase.team.list")) return;
                teams.listTeams(sender);
            }
            case "accept", "deny", "leave" -> {
                if (!checkPermission(sender, "plainbase.team." + action)) return;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
                    return;
                }
                String teamArg = args.length > 1 ? args[1].toLowerCase() : null;
                switch (action) {
                    case "accept" -> teams.accept(player, teamArg);
                    case "deny" -> teams.deny(player, teamArg);
                    case "leave" -> teams.leave(player, teamArg);
                }
            }
        }
    }

    private void handleTeamAction(CommandSender sender, TeamManager teams, String teamId, String action, String[] args) {
        if (!TEAM_ACTIONS.contains(action)) {
            sendUsage(sender);
            return;
        }
        if (!checkPermission(sender, "plainbase.team." + (action.equals("deny-request") ? "deny" : action))) return;

        switch (action) {
            case "info" -> teams.info(sender, teamId);
            case "request" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
                    return;
                }
                teams.request(player, teamId);
            }
            case "invite", "add", "kick", "setrole", "deny-request" -> {
                if (!teams.isTeamAdmin(sender, teamId)) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize("<red>You must be a team admin of " + teamId + " to do this."));
                    return;
                }
                handleAdminAction(sender, teams, teamId, action, args);
            }
        }
    }

    private void handleAdminAction(CommandSender sender, TeamManager teams, String teamId, String action, String[] args) {
        switch (action) {
            case "invite" -> {
                if (args.length < 3) { usageTeam(sender, teamId, "invite <player>"); return; }
                teams.invite(sender, teamId, args[2]);
            }
            case "add" -> {
                if (args.length < 3) { usageTeam(sender, teamId, "add <player>"); return; }
                teams.add(sender, teamId, args[2]);
            }
            case "kick" -> {
                if (args.length < 3) { usageTeam(sender, teamId, "kick <player>"); return; }
                teams.kick(sender, teamId, args[2]);
            }
            case "deny-request" -> {
                if (args.length < 3) { usageTeam(sender, teamId, "deny-request <player>"); return; }
                teams.denyRequest(sender, teamId, args[2]);
            }
            case "setrole" -> {
                if (args.length < 4) { usageTeam(sender, teamId, "setrole <player> <member|admin>"); return; }
                teams.setRole(sender, teamId, args[2], args[3]);
            }
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(plugin.getMiniMessage().deserialize(
                "<yellow>Usage: <gray>/team <accept|deny|leave|list> [team] <white>or<gray> /team <team> <invite|add|kick|setrole|request|info> ..."
        ));
    }

    private void usageTeam(CommandSender sender, String teamId, String rest) {
        sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/team " + teamId + " " + rest));
    }

    private boolean checkPermission(CommandSender sender, String permission) {
        if (!sender.hasPermission("plainbase.admin")
                && !sender.hasPermission("plainbase.team.admin")
                && !sender.hasPermission(permission)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return false;
        }
        return true;
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        TeamManager teams = plugin.getTeamManager();
        if (teams == null) return List.of();

        if (args.length <= 1) {
            String input = args.length == 1 ? args[0].toLowerCase() : "";
            List<String> options = new ArrayList<>(GLOBAL_ACTIONS);
            for (TeamManager.TeamDefinition def : teams.getTeams()) options.add(def.id());
            return options.stream().filter(s -> s.startsWith(input)).toList();
        }

        String first = args[0].toLowerCase();

        if (first.equals("accept") || first.equals("deny") || first.equals("leave")) {
            if (args.length == 2) {
                String input = args[1].toLowerCase();
                return teams.getTeams().stream().map(TeamManager.TeamDefinition::id)
                        .filter(id -> id.startsWith(input)).toList();
            }
            return List.of();
        }

        if (teams.teamExists(first)) {
            if (args.length == 2) {
                String input = args[1].toLowerCase();
                return TEAM_ACTIONS.stream().filter(s -> s.startsWith(input)).toList();
            }
            if (args.length == 3) {
                String action = args[1].toLowerCase();
                if (Set.of("invite", "add", "kick", "setrole", "deny-request").contains(action)) {
                    String input = args[2].toLowerCase();
                    return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                            .filter(n -> n.toLowerCase().startsWith(input)).toList();
                }
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("setrole")) {
                String input = args[3].toLowerCase();
                return Stream.of("member", "admin").filter(s -> s.startsWith(input)).toList();
            }
        }
        return List.of();
    }
}
