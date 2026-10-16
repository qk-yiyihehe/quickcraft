package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickSortGcaCompatibilityTest {
    @Test
    void protectsEquipmentAndControlsButAllowsBothInventories() throws Exception {
        var handler = ChestMenu.sixRows(1, mock(Inventory.class));
        ItemStack marker = Items.STRUCTURE_VOID.getDefaultInstance();
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("GcaClear", true);
        tag.putInt("GcaButton", 0);
        marker.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        handler.getSlot(0).set(marker);

        var safe = QuickSort.class.getDeclaredMethod("isSafeVisibleStorageSlot",
            AbstractContainerMenu.class, Slot.class, Minecraft.class);
        safe.setAccessible(true);
        var client = mock(Minecraft.class);
        for (int slot = 0; slot < 90; slot++) {
            assertThat((boolean) safe.invoke(null, handler, handler.getSlot(slot), client))
                .as("slot %s", slot).isEqualTo(slot >= 18);
        }

        // 普通大箱子里的结构空位物品不能触发 GCA 布局。
        marker.remove(DataComponents.CUSTOM_DATA);
        assertThat((boolean) safe.invoke(null, handler, handler.getSlot(0), client)).isTrue();
    }
}
