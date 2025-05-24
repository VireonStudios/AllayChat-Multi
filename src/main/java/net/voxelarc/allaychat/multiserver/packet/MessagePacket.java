package net.voxelarc.allaychat.multiserver.packet;

public record MessagePacket(String group, String playerName, String serializedComponent) {
}
