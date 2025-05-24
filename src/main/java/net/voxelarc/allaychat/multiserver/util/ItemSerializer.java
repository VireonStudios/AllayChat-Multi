package net.voxelarc.allaychat.multiserver.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.*;
import java.util.logging.Level;

public class ItemSerializer {

    public static String itemStackArrayToBase64(ItemStack[] items, JavaPlugin plugin) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            DataOutputStream dataOutput = new DataOutputStream(outputStream);

            dataOutput.writeInt(items.length);

            for (ItemStack item : items) {
                if (item != null) {
                    dataOutput.writeBoolean(true);
                    byte[] itemBytes = item.serializeAsBytes();
                    dataOutput.writeInt(itemBytes.length);
                    dataOutput.write(itemBytes);
                } else {
                    dataOutput.writeBoolean(false);
                }
            }

            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not convert ItemStack array to a Base64 string!", e);
            return null;
        }
    }

    public static ItemStack[] itemStackArrayFromBase64(String data, JavaPlugin plugin) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            DataInputStream dataInput = new DataInputStream(inputStream);

            int size = dataInput.readInt();
            ItemStack[] items = new ItemStack[size];

            for (int i = 0; i < size; i++) {
                boolean itemExists = dataInput.readBoolean();
                if (itemExists) {
                    int length = dataInput.readInt();
                    byte[] itemBytes = new byte[length];
                    dataInput.readFully(itemBytes);
                    items[i] = ItemStack.deserializeBytes(itemBytes);
                } else {
                    items[i] = null;
                }
            }

            dataInput.close();
            return items;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not convert Base64 string to ItemStack array!", e);
            return new ItemStack[0];
        }
    }

}
