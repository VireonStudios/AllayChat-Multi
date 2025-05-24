package net.voxelarc.allaychat.multiserver.packet;

import org.jetbrains.annotations.Nullable;

public record BroadcastPacket(String group, String serializedComponent, @Nullable String permission) {
}
