package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryWrapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaSchematicDataTest {
    @BeforeAll
    static void initializeMinecraft() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void upgradesLegacyQuantityNameAndSlotWithoutChangingInput() {
        NbtCompound item = legacy("minecraft:stone", 64, 7);
        NbtCompound display = new NbtCompound();
        display.putString("Name", "{\"text\":\"marker\"}");
        NbtCompound tag = new NbtCompound();
        tag.put("display", display);
        item.put("tag", tag);
        NbtCompound repaired = (NbtCompound) QuickLitematicaSchematicData.repair(item, 3465);
        assertThat(((AbstractNbtNumber) repaired.get("count")).intValue()).isEqualTo(64);
        assertThat(repaired.contains("Count")).isFalse();
        assertThat(repaired.contains("tag")).isFalse();
        assertThat(repaired.get("Slot")).isEqualTo(item.get("Slot"));
        assertThat(((NbtCompound) repaired.get("components")).contains("minecraft:custom_name")).isTrue();
        assertThat(item.contains("Count")).isTrue();
        assertThat(item.contains("tag")).isTrue();
    }

    @Test
    void upgradesShulkerContentsRecursively() {
        NbtCompound box = legacy("minecraft:shulker_box", 1, 0);
        NbtCompound blockEntity = new NbtCompound();
        blockEntity.putString("id", "minecraft:shulker_box");
        NbtList items = new NbtList();
        items.add(legacy("minecraft:charcoal", 64, 3));
        blockEntity.put("Items", items);
        NbtCompound tag = new NbtCompound();
        tag.put("BlockEntityTag", blockEntity);
        box.put("tag", tag);
        NbtCompound repaired = (NbtCompound) QuickLitematicaSchematicData.repair(box, 3465);
        NbtCompound components = (NbtCompound) repaired.get("components");
        NbtList contents = (NbtList) components.get("minecraft:container");
        NbtCompound slot = (NbtCompound) contents.get(0);
        NbtCompound item = (NbtCompound) slot.get("item");
        assertThat(((AbstractNbtNumber) item.get("count")).intValue()).isEqualTo(64);
        assertThat(QuickLitematicaContainerMaterialsAccess.readString(item, "id")).isEqualTo("minecraft:charcoal");
        assertThat(box.contains("tag")).isTrue();
    }

    @Test
    void keepsCurrentItemsAndCustomDataUnchanged() {
        NbtCompound data = new NbtCompound();
        data.putString("id", "minecraft:stone");
        data.putByte("Count", (byte) 64);
        NbtCompound components = new NbtCompound();
        components.put("minecraft:custom_data", data);
        NbtCompound item = new NbtCompound();
        item.putString("id", "minecraft:stone");
        item.putInt("count", 64);
        item.put("components", components);
        assertThat(QuickLitematicaSchematicData.repair(item, 99999)).isSameAs(item);
    }

    @Test
    void preservesConflictingFormatsInsteadOfChoosingAQuantity() {
        NbtCompound item = legacy("minecraft:stone", 64, 0);
        item.putInt("count", 32);
        assertThat(QuickLitematicaSchematicData.repair(item, 3465)).isSameAs(item);
        assertThat(item.contains("Count")).isTrue();
        assertThat(item.get("count")).isNotNull();
    }

    @Test
    void missingBlockEntityIdUsesItsBlockTypeAndPreservesInput() {
        NbtCompound original = new NbtCompound();
        NbtCompound prepared = QuickLitematicaSchematicData.prepareBlockEntity(original, net.minecraft.block.Blocks.HOPPER.getDefaultState(), net.minecraft.util.math.BlockPos.ORIGIN);
        assertThat(QuickLitematicaContainerMaterialsAccess.readString(prepared, "id")).isEqualTo("minecraft:hopper");
        assertThat(original.contains("id")).isFalse();
        assertThat(QuickLitematicaSchematicData.prepareBlockEntity(prepared, net.minecraft.block.Blocks.HOPPER.getDefaultState(), net.minecraft.util.math.BlockPos.ORIGIN)).isSameAs(prepared);
    }

    @Test
    void failedItemDoesNotBecomeAValidPartialInventory() {
        NbtCompound good = new NbtCompound();
        good.putString("id", "minecraft:stone");
        good.putInt("count", 64);
        good.putByte("Slot", (byte) 0);
        NbtCompound unknown = new NbtCompound();
        unknown.putString("id", "missing_mod:unavailable");
        unknown.putInt("count", 32);
        unknown.putByte("Slot", (byte) 1);
        NbtList items = new NbtList();
        items.add(good);
        items.add(unknown);
        NbtCompound container = new NbtCompound();
        container.put("Items", items);
        var lookup = mock(RegistryWrapper.WrapperLookup.class);
        try (var access = mockStatic(QuickLitematicaContainerMaterialsAccess.class, CALLS_REAL_METHODS)) {
            access.when(() -> QuickLitematicaContainerMaterialsAccess.decodeCurrentItem(lookup, good))
                    .thenReturn(new ItemStack(Items.STONE, 64));
            access.when(() -> QuickLitematicaContainerMaterialsAccess.decodeCurrentItem(lookup, unknown))
                    .thenReturn(ItemStack.EMPTY);
            assertThat(QuickLitematicaContainerMaterials.readItems(container, lookup)).isNull();
            var partial = QuickLitematicaContainerMaterials.readItemsForStatistics(container, lookup);
            assertThat(partial.get(0).getCount()).isEqualTo(64);
            assertThat(partial.get(1).isEmpty()).isTrue();
        }
        assertThat(items.size()).isEqualTo(2);
        assertThat(unknown.get("count")).isNotNull();
    }

    private static NbtCompound legacy(String id, int count, int slot) {
        NbtCompound item = new NbtCompound();
        item.putString("id", id);
        item.putByte("Count", (byte) count);
        item.putByte("Slot", (byte) slot);
        return item;
    }
}
