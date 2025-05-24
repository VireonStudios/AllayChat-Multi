package net.voxelarc.allaychat.multiserver.chat;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.voxelarc.allaychat.api.AllayChat;
import net.voxelarc.allaychat.api.chat.ChatManager;
import net.voxelarc.allaychat.api.user.ChatUser;
import net.voxelarc.allaychat.api.util.ChatUtils;
import net.voxelarc.allaychat.multiserver.MultiServerModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class CrossChatManager implements ChatManager {

    private final MultiServerModule module;

    private final AllayChat plugin;

    @Getter
    private final Cache<String, String> lastMessageCache = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES).build();

    @Getter
    private final Cache<UUID, Inventory> inventoryCache = CacheBuilder.newBuilder()
            .expireAfterWrite(3, TimeUnit.MINUTES).build();

    @Override
    public void onEnable() {
        module.getLocalChatManager().onEnable();
    }

    @Override
    public ChatRenderer.ViewerUnaware getChatRenderer() {
        return module.getLocalChatManager().getChatRenderer();
    }

    @Override
    public Component formatMessage(Player player, Component message) {
        Component component = module.getLocalChatManager().formatMessage(player, message);
        module.publishMessage(player.getName(), component);
        return component;
    }

    @Override
    public boolean handleMessage(Player player, String s) {
        return module.getLocalChatManager().handleMessage(player, s);
    }

    @Override
    public void handleChatEvent(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (!handleMessage(event.getPlayer(), message)) {
            formatMessage(event.getPlayer(), event.message());
        }

        event.setCancelled(true);
    }

    @Override
    public boolean handlePrivateMessage(Player from, String to, String message) {
        ChatUser user = plugin.getUserManager().getUser(from.getUniqueId());
        if (user == null) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getMessagesConfig().getString("messages.data-not-loaded")
            ));
            return false;
        }

        if (!plugin.getPlayerManager().getAllPlayers().contains(to)) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getPrivateMessageConfig().getString("messages.not-found")
            ));
            return false;
        }

        if (from.getName().equalsIgnoreCase(to)) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getPrivateMessageConfig().getString("messages.self")
            ));
            return false;
        }

        if (user.getIgnoredPlayers().contains(to)) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getMessagesConfig().getString("messages.ignoring"),
                    Placeholder.unparsed("player", to)
            ));
            return false;
        }

        if (plugin.getPrivateMessageConfig().getBoolean("filter")
                && handleMessage(from, message)) {
            return false;
        }

        Component spyComponent = ChatUtils.format(
                plugin.getPrivateMessageConfig().getString("messages.spy"),
                Placeholder.unparsed("from", from.getName()),
                Placeholder.unparsed("to", to),
                Placeholder.unparsed("message", message)
        );

        Component msgTarget = ChatUtils.format(
                plugin.getPrivateMessageConfig().getString("messages.format-target"),
                Placeholder.unparsed("player", from.getName()),
                Placeholder.unparsed("message", message)
        );

        Component msgSender = ChatUtils.format(
                plugin.getPrivateMessageConfig().getString("messages.format-self"),
                Placeholder.unparsed("player", to),
                Placeholder.unparsed("message", message)
        );

        plugin.getPlayerManager().sendMessage(to, msgTarget);
        ChatUtils.sendMessage(from, msgSender);

        module.publishLastReply(from.getName(), to);
        module.publishLastReply(to, from.getName());

        plugin.getUserManager().getAllUsers().stream().filter(ChatUser::isSpyEnabled).forEach(spyUser -> {
            Player player = Bukkit.getPlayer(spyUser.getUniqueId());
            if (player == null) return;

            plugin.getPlayerManager().sendMessage(player.getName(), spyComponent);
        });

        return true;
    }

    @Override
    public void handleStaffChatMessage(Player player, String message) {
        // no need to override
        this.module.getLocalChatManager().handleStaffChatMessage(player, message);
    }

    @Override
    public String getLastMessagedPlayer(String player) {
        return lastMessageCache.getIfPresent(player);
    }

    @Override
    public Inventory getInventory(UUID uuid) {
        return this.inventoryCache.getIfPresent(uuid);
    }

    @Override
    public void setInventory(UUID uuid, String playerName, Inventory inventory, InventoryType type) {
        int size = switch (type) {
            case SHULKER -> 27;
            case INVENTORY -> 45;
            case ENDER_CHEST -> inventory.getSize(); // Purpur supports custom ender chest sizes
        };

        String titleString = switch (type) {
            case SHULKER -> plugin.getReplacementConfig().getString("shulker.gui-title");
            case INVENTORY -> plugin.getReplacementConfig().getString("inventory.gui-title");
            case ENDER_CHEST -> plugin.getReplacementConfig().getString("enderchest.gui-title");
        };

        Component title = ChatUtils.format(titleString, Placeholder.unparsed("player", playerName));
        module.publishInventory(uuid, title, inventory.getContents(), size);
    }

}
