package net.voxelarc.allaychat.multiserver.packet;

public record PrivateMessagePacket(String sender, String recipient, String message, String group) {
}
