package net.voxelarc.allaychat.multiserver.packet;

import java.util.UUID;

public record InventoryPacket(String group, UUID id, String serializedItems, String serializedTitle, int size) {

}
