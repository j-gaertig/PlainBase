package de.jgaertig.plainBase.team;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class TeamListener implements Listener {

    private final PlainBase plugin;

    public TeamListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.getTeamManager() == null) return;
        plugin.getTeamManager().handleJoin(event.getPlayer());
    }
}
