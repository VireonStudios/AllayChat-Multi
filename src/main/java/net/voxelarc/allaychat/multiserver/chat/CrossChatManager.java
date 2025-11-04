package net.voxelarc.allaychat.multiserver.chat;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.voxelarc.allaychat.api.AllayChat;
import net.voxelarc.allaychat.api.chat.ChatManager;
import net.voxelarc.allaychat.api.config.YamlConfig;
import net.voxelarc.allaychat.api.user.ChatUser;
import net.voxelarc.allaychat.api.util.ChatUtils;
import net.voxelarc.allaychat.multiserver.MultiServerModule;
import net.voxelarc.allaychat.multiserver.packet.MentionPacket;
import net.voxelarc.allaychat.multiserver.packet.PrivateMessagePacket;
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

    @Setter
    private boolean mutedStatus = false;

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

        if (!user.isMsgEnabled()) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getPrivateMessageConfig().getString("messages.disabled")
            ));
            return false;
        }

        if (plugin.getPrivateMessageConfig().getBoolean("filter")
                && handleMessage(from, message)) {
            return false;
        }

        module.publishDM(from.getName(), to, message);

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

    @Override
    public boolean isChatMuted() {
        return this.mutedStatus;
    }

    @Override
    public void setChatMuted(boolean b) {
        module.publishMuteStatus(b);
    }

    @Override
    public Component handleMentions(Player player, String messageContent, Component messageComponent) {
        YamlConfig replacementConfig = plugin.getReplacementConfig();
        if (replacementConfig.getBoolean("mention.enabled")) {
            for (String playerName : plugin.getPlayerManager().getAllPlayers()) {
                if (messageContent.contains(playerName)) {
                    module.publishMention(player.getName(), playerName);
                    messageComponent = messageComponent.replaceText(TextReplacementConfig.builder()
                            .matchLiteral(playerName)
                            .replacement(ChatUtils.format(replacementConfig.getString("mention.text"), Placeholder.unparsed("player", playerName)))
                            .build()
                    );
                }
            }
        }

        return messageComponent;
    }

    public void handleMentionInternally(MentionPacket packet) {
        YamlConfig replacementConfig = plugin.getReplacementConfig();
        String playerName = packet.mentionedPlayer();
        Player targetPlayer = Bukkit.getPlayerExact(playerName);
        if (targetPlayer == null) return;
        ChatUser user = plugin.getUserManager().getUser(targetPlayer.getUniqueId());
        if (user == null) return;
        boolean allow = user.isChatEnabled() && user.isMentionsEnabled() && !user.getIgnoredPlayers().contains(packet.mentionerPlayer());

        String soundName = replacementConfig.getString("mention.sound");
        if (soundName != null && !soundName.isEmpty() && allow) {
            Sound sound = Sound.sound(Key.key(soundName), Sound.Source.MASTER, 1.0f, 1.0f);
            targetPlayer.playSound(sound);
        }

        if (replacementConfig.getBoolean("mention.title.enabled") && allow) {
            String titleText = replacementConfig.getString("mention.title.title");
            String subtitleText = replacementConfig.getString("mention.title.subtitle");
            Title title = Title.title(
                    ChatUtils.format(titleText, Placeholder.unparsed("player", packet.mentionerPlayer())),
                    ChatUtils.format(subtitleText, Placeholder.unparsed("player", packet.mentionerPlayer()))
            );
            targetPlayer.showTitle(title);
        }

        String actionBar = replacementConfig.getString("mention.actionbar");
        if (actionBar != null && !actionBar.isEmpty() && allow) {
            Component actionBarComponent = ChatUtils.format(actionBar, Placeholder.unparsed("player", packet.mentionerPlayer()));
            targetPlayer.sendActionBar(actionBarComponent);
        }

        String mentionMessage = replacementConfig.getString("mention.message");
        if (mentionMessage != null && !mentionMessage.isEmpty() && allow) {
            Component mentionMessageComponent = ChatUtils.format(mentionMessage, Placeholder.unparsed("player", packet.mentionerPlayer()));
            ChatUtils.sendMessage(targetPlayer, mentionMessageComponent);
        }
    }

    public void handleDMInternally(PrivateMessagePacket packet) {
        Player toPlayer = Bukkit.getPlayerExact(packet.recipient());
        if (toPlayer == null) return;

        ChatUser user = plugin.getUserManager().getUser(toPlayer.getUniqueId());
        if (user == null) return;

        if (!user.isMsgEnabled()) {
            module.getCrossPlayerManager().sendMessage(packet.sender(), ChatUtils.format(
                    plugin.getPrivateMessageConfig().getString("messages.disabled-other"),
                    Placeholder.unparsed("player", packet.recipient())
            ));
            return;
        }

        if (user.getIgnoredPlayers().contains(packet.sender())) {
            module.getCrossPlayerManager().sendMessage(packet.sender(), ChatUtils.format(
                    plugin.getMessagesConfig().getString("messages.ignoring-you"),
                    Placeholder.unparsed("player", packet.recipient())
            ));
            return;
        }

        Component spyComponent = ChatUtils.format(
                plugin.getPrivateMessageConfig().getString("messages.spy"),
                Placeholder.unparsed("from", packet.sender()),
                Placeholder.unparsed("to", packet.recipient()),
                Placeholder.unparsed("message", packet.message())
        );

        Component msgTarget = ChatUtils.format(
                plugin.getPrivateMessageConfig().getString("messages.format-target"),
                Placeholder.unparsed("player", packet.sender()),
                Placeholder.unparsed("message", packet.message())
        );

        Component msgSender = ChatUtils.format(
                plugin.getPrivateMessageConfig().getString("messages.format-self"),
                Placeholder.unparsed("player", packet.recipient()),
                Placeholder.unparsed("message", packet.message())
        );

        ChatUtils.sendMessage(toPlayer, msgTarget);
        module.getCrossPlayerManager().sendMessage(packet.sender(), msgSender);

        module.publishLastReply(packet.sender(), packet.recipient());
        module.publishLastReply(packet.recipient(), packet.sender());

        module.publishSpy(spyComponent);
    }

}
