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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Single entry point for every /team subcommand. Uniform grammar, always
 * action-first: {@code /team <action> [team] [player] [role]}. Every action
 * is described once in {@link #ACTIONS} — that table drives permission
 * checks, per-action config toggles, tab-completion and the built-in
 * "/team" help listing, so adding a new subcommand later is a single map
 * entry plus one switch case instead of touching parsing logic in several
 * places.
 */
public class TeamCommand implements BasicCommand {

    /**
     * @param name          subcommand literal, also the config key under {@code team.commands.<name>.enabled}
     * @param usage         argument hint shown after the action name in /team's help output
     * @param permission    permission node required to even attempt this action
     * @param requiresPlayer true if only a real player (not console) may run this
     * @param teamScoped    true if this action takes a team id as its first argument
     * @param teamRequired  only meaningful if teamScoped: false = team id may be omitted (self-service actions
     *                      fall back to the player's own single membership)
     * @param adminGated    true if, beyond the permission node, the sender must also pass
     *                      {@link TeamManager#isTeamAdmin} for the given team
     * @param hasPlayerArg  true if a target player name follows the team id
     * @param hasRoleArg    true if a role literal follows the player name (setrole only)
     * @param description  one-line summary shown in the help listing
     */
    private record ActionSpec(String name, String usage, String permission, boolean requiresPlayer,
                               boolean teamScoped, boolean teamRequired, boolean adminGated,
                               boolean hasPlayerArg, boolean hasRoleArg, String description) {
    }

    private static final Map<String, ActionSpec> ACTIONS = new LinkedHashMap<>();

    private static void register(String name, String usage, String permission, boolean requiresPlayer,
                                   boolean teamScoped, boolean teamRequired, boolean adminGated,
                                   boolean hasPlayerArg, boolean hasRoleArg, String description) {
        ACTIONS.put(name, new ActionSpec(name, usage, permission, requiresPlayer, teamScoped, teamRequired,
                adminGated, hasPlayerArg, hasRoleArg, description));
    }

    static {
        register("list", "", "plainbase.team.list", false, false, false, false, false, false,
                "List all configured teams.");
        register("info", "[team]", "plainbase.team.info", false, true, false, false, false, false,
                "Show your teams, or a specific team's members.");
        register("invites", "", "plainbase.team.invites", true, false, false, false, false, false,
                "List your own pending invites.");
        register("accept", "[team]", "plainbase.team.accept", true, true, false, false, false, false,
                "Accept a pending invite.");
        register("deny", "[team]", "plainbase.team.deny", true, true, false, false, false, false,
                "Decline a pending invite.");
        register("leave", "[team]", "plainbase.team.leave", true, true, false, false, false, false,
                "Leave a team.");
        register("request", "<team>", "plainbase.team.request", true, true, true, false, false, false,
                "Ask to join a team.");
        register("requests", "<team>", "plainbase.team.requests", false, true, true, true, false, false,
                "List a team's pending join requests.");
        register("invite", "<team> <player>", "plainbase.team.invite", false, true, true, true, true, false,
                "Invite a player to a team.");
        register("add", "<team> <player>", "plainbase.team.add", false, true, true, true, true, false,
                "Add a player directly, no confirmation needed.");
        register("kick", "<team> <player>", "plainbase.team.kick", false, true, true, true, true, false,
                "Remove a player from a team.");
        register("reject", "<team> <player>", "plainbase.team.reject", false, true, true, true, true, false,
                "Reject a pending join request.");
        register("setrole", "<team> <player> <member|admin>", "plainbase.team.setrole", false, true, true, true, true, true,
                "Change a player's role in a team.");
    }

    private final PlainBase plugin;

    public TeamCommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        TeamManager teams = plugin.getTeamManager();

        if (!plugin.getConfig().getBoolean("modules.team", true)) {
            sender.sendMessage(mm("<red>This module is currently disabled."));
            return;
        }
        if (!plugin.getTeamConfig().getBoolean("team.enabled", true) || teams == null) {
            sender.sendMessage(mm("<red>The team system has been disabled."));
            return;
        }
        if (!plugin.getTeamConfig().getBoolean("team.commands.team.enabled", true)) {
            sender.sendMessage(mm("<red>This command has been disabled."));
            return;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        String actionName = args[0].toLowerCase(Locale.ROOT);
        ActionSpec spec = ACTIONS.get(actionName);
        if (spec == null) {
            sender.sendMessage(mm("<red>Unknown /team subcommand: " + args[0] + ". Run <gray>/team<red> for a list."));
            return;
        }
        if (!checkPermission(sender, spec.permission())) return;
        if (!plugin.getTeamConfig().getBoolean("team.commands." + spec.name() + ".enabled", true)) {
            sender.sendMessage(mm("<red>This command has been disabled."));
            return;
        }
        if (spec.requiresPlayer() && !(sender instanceof Player)) {
            sender.sendMessage(mm("<red>This command can only be executed by players."));
            return;
        }

        int idx = 1;
        String teamId = null;
        if (spec.teamScoped()) {
            if (args.length > idx) {
                String candidate = args[idx].toLowerCase(Locale.ROOT);
                if (!teams.teamExists(candidate)) {
                    sender.sendMessage(mm("<red>Unknown team: " + args[idx]));
                    return;
                }
                teamId = candidate;
                idx++;
            } else if (spec.teamRequired()) {
                usage(sender, spec);
                return;
            }
        }
        if (spec.adminGated() && !teams.isTeamAdmin(sender, teamId)) {
            sender.sendMessage(mm("<red>You must be a team admin of " + teamId + " to do this."));
            return;
        }

        String playerArg = spec.hasPlayerArg() && args.length > idx ? args[idx++] : null;
        String roleArg = spec.hasRoleArg() && args.length > idx ? args[idx++] : null;
        if ((spec.hasPlayerArg() && playerArg == null) || (spec.hasRoleArg() && roleArg == null)) {
            usage(sender, spec);
            return;
        }

        Player player = sender instanceof Player p ? p : null;
        switch (actionName) {
            case "list" -> teams.listTeams(sender);
            case "info" -> {
                if (teamId != null) teams.info(sender, teamId);
                else if (player != null) teams.infoSelf(player);
                else usage(sender, spec);
            }
            case "invites" -> teams.listInvites(player);
            case "accept" -> teams.accept(player, teamId);
            case "deny" -> teams.deny(player, teamId);
            case "leave" -> teams.leave(player, teamId);
            case "request" -> teams.request(player, teamId);
            case "requests" -> teams.listRequests(sender, teamId);
            case "invite" -> teams.invite(sender, teamId, playerArg);
            case "add" -> teams.add(sender, teamId, playerArg);
            case "kick" -> teams.kick(sender, teamId, playerArg);
            case "reject" -> teams.denyRequest(sender, teamId, playerArg);
            case "setrole" -> teams.setRole(sender, teamId, playerArg, roleArg);
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(mm("<gray>--- <yellow>/team<gray> ---"));
        for (ActionSpec spec : ACTIONS.values()) {
            if (!hasPermission(sender, spec.permission())) continue;
            String line = "<yellow>/team " + spec.name() + (spec.usage().isEmpty() ? "" : " " + spec.usage())
                    + " <dark_gray>- <gray>" + spec.description();
            sender.sendMessage(mm(line));
        }
    }

    private void usage(CommandSender sender, ActionSpec spec) {
        sender.sendMessage(mm("<yellow>Usage: <gray>/team " + spec.name() + (spec.usage().isEmpty() ? "" : " " + spec.usage())));
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        return sender.hasPermission("plainbase.admin")
                || sender.hasPermission("plainbase.team.admin")
                || sender.hasPermission(permission);
    }

    private boolean checkPermission(CommandSender sender, String permission) {
        if (!hasPermission(sender, permission)) {
            sender.sendMessage(mm("<red>No permission!"));
            return false;
        }
        return true;
    }

    private net.kyori.adventure.text.Component mm(String s) {
        return plugin.getMiniMessage().deserialize(s);
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        TeamManager teams = plugin.getTeamManager();
        if (teams == null) return List.of();
        CommandSender sender = stack.getSender();

        if (args.length <= 1) {
            String input = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            return ACTIONS.values().stream()
                    .filter(spec -> hasPermission(sender, spec.permission()))
                    .map(ActionSpec::name)
                    .filter(name -> name.startsWith(input))
                    .toList();
        }

        ActionSpec spec = ACTIONS.get(args[0].toLowerCase(Locale.ROOT));
        if (spec == null || !spec.teamScoped()) return List.of();

        if (args.length == 2) {
            String input = args[1].toLowerCase(Locale.ROOT);
            return teams.getTeams().stream().map(TeamManager.TeamDefinition::id)
                    .filter(id -> id.startsWith(input)).toList();
        }
        if (args.length == 3 && spec.hasPlayerArg()) {
            String input = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(input)).toList();
        }
        if (args.length == 4 && spec.hasRoleArg()) {
            String input = args[3].toLowerCase(Locale.ROOT);
            return Stream.of("member", "admin").filter(s -> s.startsWith(input)).toList();
        }
        return List.of();
    }
}
