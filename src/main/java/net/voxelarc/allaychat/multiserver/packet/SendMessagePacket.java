package net.voxelarc.allaychat.multiserver.packet;

public record SendMessagePacket(String playerName, String serializedComponent) {
}
