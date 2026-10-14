package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.HolderLookup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaSchematicDataTest {
    @BeforeAll
    static void initializeMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void upgradesLegacyQuantityNameAndSlotWithoutChangingInput() {
        CompoundTag item = legacy("minecraft:stone", 64, 7);
        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"marker\"}");
        CompoundTag tag = new CompoundTag();
        tag.put("display", display);
        item.put("tag", tag);
        CompoundTag repaired = (CompoundTag) QuickLitematicaSchematicData.repair(item, 3465);
        assertThat(((NumericTag) repaired.get("count")).intValue()).isEqualTo(64);
        assertThat(repaired.contains("Count")).isFalse();
        assertThat(repaired.contains("tag")).isFalse();
        assertThat(repaired.get("Slot")).isEqualTo(item.get("Slot"));
        assertThat(((CompoundTag) repaired.get("components")).contains("minecraft:custom_name")).isTrue();
        assertThat(item.contains("Count")).isTrue();
        assertThat(item.contains("tag")).isTrue();
    }

    @Test
    void upgradesShulkerContentsRecursively() {
        CompoundTag box = legacy("minecraft:shulker_box", 1, 0);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:shulker_box");
        ListTag items = new ListTag();
        items.add(legacy("minecraft:charcoal", 64, 3));
        blockEntity.put("Items", items);
        CompoundTag tag = new CompoundTag();
        tag.put("BlockEntityTag", blockEntity);
        box.put("tag", tag);
        CompoundTag repaired = (CompoundTag) QuickLitematicaSchematicData.repair(box, 3465);
        CompoundTag components = (CompoundTag) repaired.get("components");
        ListTag contents = (ListTag) components.get("minecraft:container");
        CompoundTag slot = (CompoundTag) contents.get(0);
        CompoundTag item = (CompoundTag) slot.get("item");
        assertThat(((NumericTag) item.get("count")).intValue()).isEqualTo(64);
        assertThat(QuickLitematicaContainerMaterialsAccess.readString(item, "id")).isEqualTo("minecraft:charcoal");
        assertThat(box.contains("tag")).isTrue();
    }

    @Test
    void keepsCurrentItemsAndCustomDataUnchanged() {
        CompoundTag data = new CompoundTag();
        data.putString("id", "minecraft:stone");
        data.putByte("Count", (byte) 64);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_data", data);
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:stone");
        item.putInt("count", 64);
        item.put("components", components);
        assertThat(QuickLitematicaSchematicData.repair(item, 99999)).isSameAs(item);
    }

    @Test
    void preservesConflictingFormatsInsteadOfChoosingAQuantity() {
        CompoundTag item = legacy("minecraft:stone", 64, 0);
        item.putInt("count", 32);
        assertThat(QuickLitematicaSchematicData.repair(item, 3465)).isSameAs(item);
        assertThat(item.contains("Count")).isTrue();
        assertThat(item.get("count")).isNotNull();
    }

    @Test
    void missingBlockEntityIdUsesItsBlockTypeAndPreservesInput() {
        CompoundTag original = new CompoundTag();
        CompoundTag prepared = QuickLitematicaSchematicData.prepareBlockEntity(original, net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState(), net.minecraft.core.BlockPos.ZERO);
        assertThat(QuickLitematicaContainerMaterialsAccess.readString(prepared, "id")).isEqualTo("minecraft:hopper");
        assertThat(original.contains("id")).isFalse();
        assertThat(QuickLitematicaSchematicData.prepareBlockEntity(prepared, net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState(), net.minecraft.core.BlockPos.ZERO)).isSameAs(prepared);
    }

    @Test
    void failedItemDoesNotBecomeAValidPartialInventory() {
        CompoundTag good = new CompoundTag();
        good.putString("id", "minecraft:stone");
        good.putInt("count", 64);
        good.putByte("Slot", (byte) 0);
        CompoundTag unknown = new CompoundTag();
        unknown.putString("id", "missing_mod:unavailable");
        unknown.putInt("count", 32);
        unknown.putByte("Slot", (byte) 1);
        ListTag items = new ListTag();
        items.add(good);
        items.add(unknown);
        CompoundTag container = new CompoundTag();
        container.put("Items", items);
        var lookup = mock(HolderLookup.Provider.class);
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

    private static CompoundTag legacy(String id, int count, int slot) {
        CompoundTag item = new CompoundTag();
        item.putString("id", id);
        item.putByte("Count", (byte) count);
        item.putByte("Slot", (byte) slot);
        return item;
    }
}
