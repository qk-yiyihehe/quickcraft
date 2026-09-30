package com.yiyihehe.quickcraft;

import com.google.gson.JsonElement;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryWrapper;

/**
 * 1.21.8+ 村民交易物品 Codec 序列化与 ARGB 星标颜色适配。
 */
public final class QuickTradeAccess {
    public static int getFavoriteStarColor() {
        return 0xFFFFE066;
    }

    public static String encodeStack(ItemStack stack, RegistryWrapper.WrapperLookup registryLookup) {
        return ItemStack.OPTIONAL_CODEC
                .encodeStart(registryLookup.getOps(NbtOps.INSTANCE), stack)
                .result()
                .map(NbtElement::toString)
                .orElse("{}");
    }

    public static ItemStack decodeStack(JsonElement element, RegistryWrapper.WrapperLookup registryLookup) {
        if (element == null || !element.isJsonPrimitive()) {
            return ItemStack.EMPTY;
        }
        try {
            return ItemStack.OPTIONAL_CODEC
                    .parse(registryLookup.getOps(NbtOps.INSTANCE), StringNbtReader.readCompound(element.getAsString()))
                    .result()
                    .orElse(ItemStack.EMPTY);
        } catch (CommandSyntaxException ignored) {
            return ItemStack.EMPTY;
        }
    }
}
