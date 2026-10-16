package com.yiyihehe.quickcraft.litematica;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEntityMaterialsTest {
    @BeforeAll
    static void initializeMinecraft() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void hudFallbackCanBeSortedWithoutChangingSource() {
        var list = mock(MaterialListBase.class);
        var first = new MaterialListEntry(new ItemStack(Items.MINECART), 2, 2, 0, 0);
        var second = new MaterialListEntry(new ItemStack(Items.ARMOR_STAND), 1, 1, 0, 0);
        try (var ignores = mockStatic(QuickLitematicaMaterialIgnores.class)) {
            ignores.when(() -> QuickLitematicaMaterialIgnores.effective(list)).thenReturn(java.util.Set.of());
            for (var source : List.of(List.of(first, second), List.<MaterialListEntry>of())) {
                var hud = QuickLitematicaProjectionMaterials.hud(list, true, () -> source);
                java.util.Collections.sort(hud, java.util.Comparator.comparingInt(
                        MaterialListEntry::getCountTotal));
                assertThat(hud).containsExactlyElementsOf(source.isEmpty() ? source : List.of(second, first));
                assertThat(source).containsExactlyElementsOf(source.isEmpty() ? source : List.of(first, second));
            }
        }
    }

    @Test
    void countsMinecartBodyButNotInventoryMobsOrDrops() {
        var cart = entity("chest_minecart");
        var inventory = new NbtList();
        inventory.add(entity("diamond"));
        cart.put("Items", inventory);
        var drops = entity("item");
        drops.put("Item", entity("diamond"));
        var result = new ArrayList<ItemStack>();
        var lookup = mock(RegistryWrapper.WrapperLookup.class);
        try (var data = mockStatic(QuickLitematicaSchematicData.class)) {
            for (var nbt : List.of(cart, entity("zombie"), entity("command_block_minecart"), drops)) {
                QuickLitematicaEntityMaterials.appendMaterials(nbt, lookup, result, 0);
            }
            data.verifyNoInteractions();
        }
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getItem()).isEqualTo(Items.CHEST_MINECART);
    }

    @Test
    void constructsGolemsAndWithersWithoutSpawnEggs() {
        var result = materials(entity("iron_golem"), entity("snow_golem"), entity("copper_golem"), entity("wither"));
        assertThat(result.stream().filter(stack -> stack.getItem() == Items.CARVED_PUMPKIN).mapToInt(ItemStack::getCount).sum()).isEqualTo(3);
        assertThat(result.stream().filter(stack -> stack.getItem() == Items.IRON_BLOCK).mapToInt(ItemStack::getCount).sum()).isEqualTo(4);
        assertThat(result.stream().filter(stack -> stack.getItem() == Items.SNOW_BLOCK).mapToInt(ItemStack::getCount).sum()).isEqualTo(2);
        assertThat(result.stream().filter(stack -> stack.getItem() == Items.COPPER_BLOCK).mapToInt(ItemStack::getCount).sum()).isEqualTo(1);
        assertThat(result.stream().filter(stack -> stack.getItem() == Items.SOUL_SAND).mapToInt(ItemStack::getCount).sum()).isEqualTo(4);
        assertThat(result.stream().filter(stack -> stack.getItem() == Items.WITHER_SKELETON_SKULL).mapToInt(ItemStack::getCount).sum()).isEqualTo(3);
        assertThat(result).hasSize(8);
    }

    @Test
    void includesFrameDisplayAndBothArmorStandEquipmentFormatsOnly() {
        var item = entity("diamond");
        item.putInt("count", 1);
        var frame = entity("glow_item_frame");
        frame.put("Item", item);
        var legacy = entity("armor_stand");
        var armor = new NbtList();
        armor.add(item);
        legacy.put("ArmorItems", armor);
        legacy.put("HandItems", armor.copy());
        var current = entity("armor_stand");
        var equipment = new NbtCompound();
        equipment.put("head", item);
        equipment.put("offhand", item);
        current.put("equipment", equipment);
        // 新格式存在时，不能将遗留字段的同一件装备再计一次。
        current.put("ArmorItems", armor.copy());
        var mob = entity("zombie");
        mob.put("equipment", equipment.copy());
        var lookup = mock(RegistryWrapper.WrapperLookup.class);
        List<ItemStack> result = new ArrayList<>();
        try (var data = mockStatic(QuickLitematicaSchematicData.class)) {
            data.when(() -> QuickLitematicaSchematicData.decodeItem(item, lookup)).thenReturn(new ItemStack(Items.DIAMOND));
            for (var nbt : List.of(frame, legacy, current, mob)) {
                QuickLitematicaEntityMaterials.appendMaterials(nbt, lookup, result, 0);
            }
            data.verify(() -> QuickLitematicaSchematicData.decodeItem(item, lookup), times(5));
        }
        assertThat(result.stream().filter(stack -> stack.getItem() == Items.DIAMOND).count()).isEqualTo(5);
        assertThat(result).hasSize(8);
    }

    @Test
    void countsPlaceablePassengersEvenWhenTheirParentIsExcluded() {
        var mob = entity("zombie");
        var passengers = new NbtList();
        passengers.add(entity("minecart"));
        mob.put("Passengers", passengers);
        assertThat(materials(mob)).singleElement().satisfies(stack -> assertThat(stack.getItem()).isEqualTo(Items.MINECART));
        var result = new ArrayList<ItemStack>();
        QuickLitematicaEntityMaterials.appendMaterials(mob, null, result, 9);
        assertThat(result).isEmpty();
    }

    private static List<ItemStack> materials(NbtCompound... entities) {
        var result = new ArrayList<ItemStack>();
        for (var entity : entities) { QuickLitematicaEntityMaterials.appendMaterials(entity, null, result, 0); }
        return result;
    }

    private static NbtCompound entity(String path) {
        var nbt = new NbtCompound();
        nbt.putString("id", "minecraft:" + path);
        return nbt;
    }
}
