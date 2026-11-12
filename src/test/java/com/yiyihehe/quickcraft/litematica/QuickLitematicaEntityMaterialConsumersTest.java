package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEntityMaterialConsumersTest {
    private final DynamicRegistryManager lookup = DynamicRegistryManager.of(Registries.REGISTRIES);

    @Test
    void emptyOrMissingEquipmentCannotHideNativeLegacyItems() throws Exception {
        for (boolean addEmptyEquipment : List.of(false, true)) {
            NbtCompound nbt = legacyStand();
            if (addEmptyEquipment) nbt.put("equipment", new NbtCompound());
            assertConsumers(nbt, QuickLitematicaEntityPlacementAccess.usesEntityEquipment()
                    ? List.of(new ItemStack(Items.ARMOR_STAND))
                    : List.of(new ItemStack(Items.ARMOR_STAND), diamond()));
        }
    }

    @Test
    void allNativeEquipmentSlotsKeepComponentsAndDoNotDoubleCountLegacy() throws Exception {
        NbtCompound nbt = legacyStand();
        NbtCompound equipment = new NbtCompound();
        for (String slot : List.of("mainhand", "offhand", "head", "chest", "legs", "feet", "body", "saddle")) {
            equipment.put(slot, encode(emerald()));
        }
        nbt.put("equipment", equipment);
        ItemStack expected = emerald();
        expected.setCount(expected.getCount() * 8);
        assertConsumers(nbt, List.of(new ItemStack(Items.ARMOR_STAND),
                QuickLitematicaEntityPlacementAccess.usesEntityEquipment() ? expected : diamond()));
    }

    @Test
    void passengerEquipmentUsesSameFormatAsRoot() throws Exception {
        NbtCompound root = stand();
        NbtCompound passenger = legacyStand();
        NbtCompound equipment = new NbtCompound();
        equipment.put("head", encode(emerald()));
        passenger.put("equipment", equipment);
        NbtList passengers = new NbtList();
        passengers.add(passenger);
        root.put("Passengers", passengers);
        assertConsumers(root, List.of(new ItemStack(Items.ARMOR_STAND, 2),
                QuickLitematicaEntityPlacementAccess.usesEntityEquipment() ? emerald() : diamond()));
    }

    private void assertConsumers(NbtCompound nbt, List<ItemStack> expected) throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        client.world = mock(ClientWorld.class);
        when(client.world.getRegistryManager()).thenReturn(lookup);
        ServerWorld server = mock(ServerWorld.class);
        when(server.getRegistryManager()).thenReturn(lookup);
        try (var singleton = mockStatic(MinecraftClient.class)) {
            singleton.when(MinecraftClient::getInstance).thenReturn(client);
            Method clientMaterials = QuickLitematicaEntityPlacement.class.getDeclaredMethod(
                    "getMaterials", EntityType.class, NbtCompound.class);
            clientMaterials.setAccessible(true);
            Method serverMaterials = QuickLitematicaEntityPlacementServer.class.getDeclaredMethod(
                    "materialsForTree", NbtCompound.class, ServerWorld.class, ServerPlayerEntity.class);
            serverMaterials.setAccessible(true);
            assertStacks(stacks(clientMaterials.invoke(null, EntityType.ARMOR_STAND, nbt)), expected);
            assertStacks(stacks(serverMaterials.invoke(null, nbt, server, null)), expected);
            List<ItemStack> hud = new ArrayList<>();
            QuickLitematicaEntityMaterials.appendMaterials(nbt, lookup, hud, 0);
            assertStacks(hud, expected);
        }
    }

    private static void assertStacks(List<ItemStack> actual, List<ItemStack> expected) {
        assertThat(actual).isNotNull();
        assertThat(actual.stream().mapToInt(ItemStack::getCount).sum())
                .isEqualTo(expected.stream().mapToInt(ItemStack::getCount).sum());
        for (ItemStack wanted : expected) {
            assertThat(actual.stream().filter(stack -> ItemStack.areItemsAndComponentsEqual(stack, wanted))
                    .mapToInt(ItemStack::getCount).sum()).isEqualTo(wanted.getCount());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> stacks(Object value) { return (List<ItemStack>) value; }

    private NbtCompound legacyStand() {
        NbtCompound nbt = stand();
        NbtList hand = new NbtList();
        hand.add(encode(diamond()));
        hand.add(new NbtCompound());
        nbt.put("HandItems", hand);
        return nbt;
    }

    private NbtCompound encode(ItemStack stack) {
        return (NbtCompound) ItemStack.CODEC.encodeStart(lookup.getOps(NbtOps.INSTANCE), stack).getOrThrow();
    }

    private static NbtCompound stand() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:armor_stand");
        return nbt;
    }

    private static ItemStack diamond() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 3);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("legacy equipment"));
        return stack;
    }

    private static ItemStack emerald() {
        ItemStack stack = new ItemStack(Items.EMERALD, 2);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("native equipment"));
        return stack;
    }
}
