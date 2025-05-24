package net.voxelarc.allaychat.multiserver.player;

import lombok.RequiredArgsConstructor;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.voxelarc.allaychat.api.player.PlayerManager;
import net.voxelarc.allaychat.multiserver.MultiServerModule;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class CrossPlayerManager implements PlayerManager {

    private final MultiServerModule module;

    private final Set<String> playerList = ConcurrentHashMap.newKeySet();

    @Override
    public Set<String> getAllPlayers() {
        return playerList;
    }

    @Override
    public void playSound(String playerName, Sound sound) {
        module.publishSound(playerName, sound);
    }

    @Override
    public void showTitle(String playerName, Title title) {
        Component titleComponent = title.title();
        Component subtitleComponent = title.subtitle();
        module.publishTitle(playerName, titleComponent, subtitleComponent);
    }

    @Override
    public void showActionBar(String playerName, Component component) {
        module.publishActionbar(playerName, component);
    }

    @Override
    public void sendMessage(String playerName, Component component) {
        module.publishSendMessage(playerName, component);
    }

    @Override
    public void broadcast(Component component) {
        this.broadcast(component, null);
    }

    @Override
    public void broadcast(Component component, String permission) {
        module.publishBroadcast(component, permission);
    }

}
