package net.voxelarc.allaychat.multiserver.listener;

import lombok.RequiredArgsConstructor;
import net.voxelarc.allaychat.multiserver.MultiServerModule;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class ConnectionListener implements Listener {

    private final MultiServerModule module;

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Remove on PlayerQuitEvent may conflict on server switches, so we delay adding the player
        Bukkit.getAsyncScheduler().runDelayed(module.getPlugin(),
                (task) -> module.addPlayer(event.getPlayer().getName()), 1, TimeUnit.SECONDS);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        module.removePlayer(event.getPlayer().getName());
    }

}
