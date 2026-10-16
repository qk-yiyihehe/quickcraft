package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickSortGcaCompatibilityTest {
    @Test
    void protectsEquipmentAndControlsButAllowsBothInventories() throws Exception {
        var handler = GenericContainerScreenHandler.createGeneric9x6(1, mock(PlayerInventory.class));
        ItemStack marker = Items.STRUCTURE_VOID.getDefaultStack();
        NbtCompound tag = new NbtCompound();
        tag.putBoolean("GcaClear", true);
        tag.putInt("GcaButton", 0);
        marker.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        handler.getSlot(0).setStack(marker);

        var safe = QuickSort.class.getDeclaredMethod("isSafeVisibleStorageSlot",
            ScreenHandler.class, Slot.class, MinecraftClient.class);
        safe.setAccessible(true);
        var client = mock(MinecraftClient.class);
        for (int slot = 0; slot < 90; slot++) {
            assertThat((boolean) safe.invoke(null, handler, handler.getSlot(slot), client))
                .as("slot %s", slot).isEqualTo(slot >= 18);
        }

        // 普通大箱子里的结构空位物品不能触发 GCA 布局。
        marker.remove(DataComponentTypes.CUSTOM_DATA);
        assertThat((boolean) safe.invoke(null, handler, handler.getSlot(0), client)).isTrue();
    }
}
