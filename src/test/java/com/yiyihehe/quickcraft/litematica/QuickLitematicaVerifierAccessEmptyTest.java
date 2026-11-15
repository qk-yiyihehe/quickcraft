package com.yiyihehe.quickcraft.litematica;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaVerifierAccessEmptyTest {
    @Test
    void nullCacheIsNotAConfirmedEmptyContainer() {
        assertThat(QuickLitematicaVerifierAccess.isServerEmptyContainerNbt(null, new SimpleInventory(27))).isFalse();
    }

    @Test
    void itemlessCachedPayloadIsAConfirmedEmptyContainerRegardlessOfExpectedContents() {
        NbtCompound cached = new NbtCompound();
        cached.putString("id", "minecraft:chest");
        SimpleInventory expected = new SimpleInventory(27);
        assertThat(QuickLitematicaVerifierAccess.isServerEmptyContainerNbt(cached, expected)).isTrue();

        expected.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE));
        assertThat(QuickLitematicaVerifierAccess.isServerEmptyContainerNbt(cached, expected)).isTrue();
    }

    @Test
    void alternateContainerItemPayloadsAreNotTreatedAsEmpty() {
        NbtCompound pot = new NbtCompound();
        pot.putString("id", "minecraft:decorated_pot");
        pot.putString("item", "minecraft:stone");
        assertThat(QuickLitematicaVerifierAccess.isServerEmptyContainerNbt(pot, new SimpleInventory(1))).isFalse();
    }

    @Test
    void itemPayloadIsNotTreatedAsEmpty() {
        NbtCompound cached = new NbtCompound();
        NbtList items = new NbtList();
        NbtCompound item = new NbtCompound();
        item.putString("id", "minecraft:stone");
        item.putInt("count", 1);
        items.add(item);
        cached.put("Items", items);
        assertThat(QuickLitematicaVerifierAccess.isServerEmptyContainerNbt(cached, new SimpleInventory(27))).isFalse();
    }
}
