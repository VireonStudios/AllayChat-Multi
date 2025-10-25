package net.voxelarc.allaychat.multiserver;

import com.google.gson.Gson;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import lombok.Getter;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.title.Title;
import net.voxelarc.allaychat.api.chat.ChatManager;
import net.voxelarc.allaychat.api.inventory.impl.AllayInventory;
import net.voxelarc.allaychat.api.module.Module;
import net.voxelarc.allaychat.api.user.ChatUser;
import net.voxelarc.allaychat.multiserver.chat.CrossChatManager;
import net.voxelarc.allaychat.multiserver.listener.ConnectionListener;
import net.voxelarc.allaychat.multiserver.packet.*;
import net.voxelarc.allaychat.multiserver.player.CrossPlayerManager;
import net.voxelarc.allaychat.multiserver.util.ItemSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class MultiServerModule extends Module {

    public static final Gson GSON = new Gson();

    public static final String MESSAGE_CHANNEL = "allaychat:message:main";

    public static final String PLAY_SOUND_CHANNEL = "allaychat:send:sound";
    public static final String TITLE_CHANNEL = "allaychat:send:title";
    public static final String ACTIONBAR_CHANNEL = "allaychat:send:actionbar";
    public static final String SEND_MESSAGE_CHANNEL = "allaychat:send:message";
    public static final String BROADCAST_CHANNEL = "allaychat:send:broadcast";
    public static final String MENTION_CHANNEL = "allaychat:mention";

    public static final String INVENTORY_CHANNEL = "allaychat:inventory";

    public static final String REPLY_CHANNEL = "allaychat:reply";

    public static final String MUTE_CHANNEL = "allaychat:mute";

    public static final String PLAYER_JOIN_CHANNEL = "allaychat:player:join";
    public static final String PLAYER_QUIT_CHANNEL = "allaychat:player:quit";
    public static final String PLAYER_CLEAR_CHANNEL = "allaychat:player:clear";
    public static final String PLAYER_LIST_MAP_KEY = "allaychat:players:";
    public static final String SERVER_LIST_MAP_KEY = "allaychat:servers:";

    private RedisClient redisClient;

    private StatefulRedisConnection<String, String> redisConnection; // Everything else
    private StatefulRedisPubSubConnection<String, String> pubSubConnection; // Sub

    @Getter private String group;
    @Getter private UUID serverId;

    @Getter private ChatManager localChatManager;

    @Getter private CrossChatManager crossChatManager;
    @Getter private CrossPlayerManager crossPlayerManager;

    @Override
    public void onLoad() {
        localChatManager = getPlugin().getChatManager();

        // Register the CrossChatManager
        crossChatManager = new CrossChatManager(this, getPlugin());
        getPlugin().setChatManager(crossChatManager);

        // Register the CrossPlayerManager
        crossPlayerManager = new CrossPlayerManager(this);
        getPlugin().setPlayerManager(crossPlayerManager);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        group = getConfig().getString("group");
        serverId = UUID.randomUUID();

        getLogger().info("ServerID: " + serverId);

        redisClient = RedisClient.create(getConfig().getString("redis-uri"));

        redisConnection = redisClient.connect();
        pubSubConnection = redisClient.connectPubSub();

        RedisPubSubAsyncCommands<String, String> connection = pubSubConnection.async();
        pubSubConnection.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                handleMessage(channel, message);
            }

            @Override
            public void subscribed(String channel, long count) {
                getLogger().info("Successfully subscribed to channel: " + channel + " (total: " + count + ")");
            }

            @Override
            public void unsubscribed(String channel, long count) {
                getLogger().info("Unsubscribed from channel: " + channel + " (remaining: " + count + ")");
            }
        });

        connection.subscribe(
                MESSAGE_CHANNEL, INVENTORY_CHANNEL, BROADCAST_CHANNEL,
                PLAY_SOUND_CHANNEL, TITLE_CHANNEL, ACTIONBAR_CHANNEL, SEND_MESSAGE_CHANNEL,
                PLAYER_CLEAR_CHANNEL, PLAYER_JOIN_CHANNEL, PLAYER_QUIT_CHANNEL, REPLY_CHANNEL, MUTE_CHANNEL, MENTION_CHANNEL
        ).whenComplete((result, throwable) -> {
            if (throwable != null) {
                getLogger().log(Level.SEVERE, "Subscribe failed: " + throwable.getMessage(), throwable);
            }
        });

        Consumer<ScheduledTask> playerUpdateTask = (task) -> {
            updateLastHeartbeat();
            RedisCommands<String, String> c = redisConnection.sync();
            Set<String> allPlayers = crossPlayerManager.getAllPlayers();
            allPlayers.clear();
            allPlayers.addAll(c.hgetall(PLAYER_LIST_MAP_KEY + group).keySet());
        };

        Bukkit.getAsyncScheduler().runAtFixedRate(getPlugin(), playerUpdateTask, 10, 10, TimeUnit.SECONDS);

        if (getConfig().getBoolean("main-server")) {
            Consumer<ScheduledTask> playerListValidateTask = (task) -> {
                RedisCommands<String, String> c = redisConnection.sync();
                for (Map.Entry<String, String> entry : c.hgetall(SERVER_LIST_MAP_KEY + group).entrySet()) {
                    long lastHeartbeat = Long.parseLong(entry.getValue());
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastHeartbeat > 3 * 60 * 1000) { // 3 minutes
                        MultiServerModule.this.getLogger().info("Marking server " + entry.getKey() + " as offline due to no heartbeat received in the last 3 minutes.");
                        c.hdel(SERVER_LIST_MAP_KEY + group, entry.getKey());

                        // remove all players from the player list map that are associated with this server
                        for (Map.Entry<String, String> playerEntry : c.hgetall(PLAYER_LIST_MAP_KEY + group).entrySet()) {
                            if (playerEntry.getValue().equalsIgnoreCase(entry.getKey())) {
                                c.hdel(PLAYER_LIST_MAP_KEY + group, playerEntry.getKey());
                            }
                        }
                    }
                }
            };
            Bukkit.getAsyncScheduler().runAtFixedRate(getPlugin(), playerListValidateTask, 1, 30, TimeUnit.SECONDS);
        }

        registerListeners(new ConnectionListener(this));
    }

    @Override
    public void onDisable() {
        RedisCommands<String, String> connection = redisConnection.sync();
        connection.hdel(SERVER_LIST_MAP_KEY + group, this.serverId.toString());
        for (Player player : Bukkit.getOnlinePlayers()) {
            connection.hdel(PLAYER_LIST_MAP_KEY + group, player.getName());
            connection.publish(PLAYER_QUIT_CHANNEL, GSON.toJson(new QuitPacket(player.getName(), group)));
        }

        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    private void handleMessage(String channel, String message) {
        switch (channel) {
            case MESSAGE_CHANNEL -> {
                MessagePacket packet = GSON.fromJson(message, MessagePacket.class);
                if (!packet.group().equals(group)) return;

                Bukkit.getServer().filterAudience(audience -> {
                    if (audience instanceof Player player) {
                        ChatUser chatUser = getPlugin().getUserManager().getUser(player.getUniqueId());
                        if (chatUser == null) return true;

                        return !chatUser.getIgnoredPlayers().contains(packet.playerName());
                    }
                    return true;
                }).sendMessage(GsonComponentSerializer.gson().deserialize(packet.serializedComponent()));
            }

            case INVENTORY_CHANNEL -> {
                InventoryPacket packet = GSON.fromJson(message, InventoryPacket.class);
                if (!packet.group().equals(group)) return;

                ItemStack[] items = ItemSerializer.itemStackArrayFromBase64(packet.serializedItems(), getPlugin());
                Component title = GsonComponentSerializer.gson().deserialize(packet.serializedTitle());

                Inventory inventory = new AllayInventory(items, title, packet.size()).getInventory();
                getCrossChatManager().getInventoryCache().put(packet.id(), inventory);
            }

            case PLAY_SOUND_CHANNEL -> {
                SoundPacket packet = GSON.fromJson(message, SoundPacket.class);
                Player player = Bukkit.getPlayerExact(packet.playerName());
                if (player == null) return;

                Sound sound = Sound.sound(Key.key(packet.soundKey()), Sound.Source.MASTER, 1, 1);
                player.playSound(sound);
            }

            case TITLE_CHANNEL -> {
                TitlePacket packet = GSON.fromJson(message, TitlePacket.class);
                Player player = Bukkit.getPlayerExact(packet.playerName());
                if (player == null) return;

                Component title = GsonComponentSerializer.gson().deserialize(packet.serializedComponentTitle());
                Component subtitle = GsonComponentSerializer.gson().deserialize(packet.serializedComponentSubtitle());
                player.showTitle(Title.title(title, subtitle));
            }

            case ACTIONBAR_CHANNEL -> {
                ActionbarPacket packet = GSON.fromJson(message, ActionbarPacket.class);
                Player player = Bukkit.getPlayerExact(packet.playerName());
                if (player == null) return;

                Component actionbar = GsonComponentSerializer.gson().deserialize(packet.serializedComponent());
                player.sendActionBar(actionbar);
            }

            case SEND_MESSAGE_CHANNEL -> {
                SendMessagePacket packet = GSON.fromJson(message, SendMessagePacket.class);
                Player player = Bukkit.getPlayerExact(packet.playerName());
                if (player == null) return;

                Component component = GsonComponentSerializer.gson().deserialize(packet.serializedComponent());
                player.sendMessage(component);
            }

            case BROADCAST_CHANNEL -> {
                BroadcastPacket packet = GSON.fromJson(message, BroadcastPacket.class);
                if (!packet.group().equals(group)) return;

                Component component = GsonComponentSerializer.gson().deserialize(packet.serializedComponent());
                if (packet.permission() != null) {
                    Bukkit.getServer().broadcast(component, packet.permission());
                } else {
                    Bukkit.getServer().broadcast(component);
                }
            }

            case PLAYER_JOIN_CHANNEL -> {
                JoinPacket packet = GSON.fromJson(message, JoinPacket.class);
                if (!packet.group().equals(group)) return;

                crossPlayerManager.getAllPlayers().add(packet.playerName());
            }

            case PLAYER_QUIT_CHANNEL -> {
                QuitPacket packet = GSON.fromJson(message, QuitPacket.class);
                if (!packet.group().equals(group)) return;

                crossPlayerManager.getAllPlayers().remove(packet.playerName());
            }

            case REPLY_CHANNEL -> {
                SetLastReplyPacket packet = GSON.fromJson(message, SetLastReplyPacket.class);
                if (!packet.group().equals(group)) return;

                crossChatManager.getLastMessageCache().put(packet.playerOne(), packet.playerTwo());
            }

            case MUTE_CHANNEL -> {
                MutePacket packet = GSON.fromJson(message, MutePacket.class);
                crossChatManager.setMutedStatus(packet.muted());
            }

            case MENTION_CHANNEL -> {
                MentionPacket packet = GSON.fromJson(message, MentionPacket.class);
                if (!packet.group().equals(group)) return;

                crossChatManager.handleMentionInternally(packet);
            }
        }
    }

    public void updateLastHeartbeat() {
        redisConnection.async().hset(SERVER_LIST_MAP_KEY + group, serverId.toString(), String.valueOf(System.currentTimeMillis()));
    }

    public void addPlayer(String playerName) {
        RedisAsyncCommands<String, String> connection = redisConnection.async();
        connection.hset(PLAYER_LIST_MAP_KEY + group, playerName, serverId.toString());
        connection.publish(PLAYER_JOIN_CHANNEL, GSON.toJson(new JoinPacket(playerName, group)));
    }

    public void removePlayer(String playerName) {
        RedisAsyncCommands<String, String> connection = redisConnection.async();
        connection.hdel(PLAYER_LIST_MAP_KEY + group, playerName);
        connection.publish(PLAYER_QUIT_CHANNEL, GSON.toJson(new QuitPacket(playerName, group)));
    }

    public void publishMessage(String playerName, Component component) {
        MessagePacket packet = new MessagePacket(group, playerName, GsonComponentSerializer.gson().serialize(component));
        redisConnection.async().publish(MESSAGE_CHANNEL, GSON.toJson(packet));
    }

    public void publishLastReply(String playerOne, String playerTwo) {
        SetLastReplyPacket packet = new SetLastReplyPacket(group, playerOne, playerTwo);
        redisConnection.async().publish(REPLY_CHANNEL, GSON.toJson(packet));
    }

    public void publishInventory(UUID id, Component title, ItemStack[] items, int size) {
        InventoryPacket packet = new InventoryPacket(
                group,
                id,
                ItemSerializer.itemStackArrayToBase64(items, getPlugin()),
                GsonComponentSerializer.gson().serialize(title),
                size
        );
        redisConnection.async().publish(INVENTORY_CHANNEL, GSON.toJson(packet));
    }

    public void publishSound(String playerName, Sound sound) {
        SoundPacket packet = new SoundPacket(playerName, sound.name().asString());
        redisConnection.async().publish(PLAY_SOUND_CHANNEL, GSON.toJson(packet));
    }

    public void publishTitle(String playerName, Component title, Component subtitle) {
        TitlePacket packet = new TitlePacket(
                playerName,
                GsonComponentSerializer.gson().serialize(title),
                GsonComponentSerializer.gson().serialize(subtitle)
        );
        redisConnection.async().publish(TITLE_CHANNEL, GSON.toJson(packet));
    }

    public void publishActionbar(String playerName, Component component) {
        ActionbarPacket packet = new ActionbarPacket(playerName, GsonComponentSerializer.gson().serialize(component));
        redisConnection.async().publish(ACTIONBAR_CHANNEL, GSON.toJson(packet));
    }

    public void publishSendMessage(String playerName, Component component) {
        SendMessagePacket packet = new SendMessagePacket(playerName, GsonComponentSerializer.gson().serialize(component));
        redisConnection.async().publish(SEND_MESSAGE_CHANNEL, GSON.toJson(packet));
    }

    public void publishBroadcast(Component component, String permission) {
        BroadcastPacket packet = new BroadcastPacket(group, GsonComponentSerializer.gson().serialize(component), permission);
        redisConnection.async().publish(BROADCAST_CHANNEL, GSON.toJson(packet));
    }

    public void publishMuteStatus(boolean muted) {
        MutePacket packet = new MutePacket(muted);
        redisConnection.async().publish(MUTE_CHANNEL, GSON.toJson(packet));
    }

    public void publishMention(String mentioner, String mentioned) {
        MentionPacket packet = new MentionPacket(mentioned, mentioner, group);
        redisConnection.async().publish(MENTION_CHANNEL, GSON.toJson(packet));
    }

}
