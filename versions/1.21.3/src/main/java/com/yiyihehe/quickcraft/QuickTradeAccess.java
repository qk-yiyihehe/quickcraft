package com.yiyihehe.quickcraft;

import com.google.gson.JsonElement;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryWrapper;

/**
 * 1.21.3 村民交易物品序列化与界面星标颜色适配。
 */
public final class QuickTradeAccess {
    public static int getFavoriteStarColor() {
        return 0xFFE066;
    }

    public static String encodeStack(ItemStack stack, RegistryWrapper.WrapperLookup registryLookup) {
        return stack.toNbtAllowEmpty(registryLookup).toString();
    }

    public static ItemStack decodeStack(JsonElement element, RegistryWrapper.WrapperLookup registryLookup) {
        if (element == null || !element.isJsonPrimitive()) {
            return ItemStack.EMPTY;
        }
        try {
            return ItemStack.fromNbt(registryLookup, StringNbtReader.parse(element.getAsString()))
                    .orElse(ItemStack.EMPTY);
        } catch (CommandSyntaxException ignored) {
            return ItemStack.EMPTY;
        }
    }
}
