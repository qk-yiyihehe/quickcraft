package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEntityMaterialConsumersTest {
    private final RegistryAccess lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @Test
    void emptyOrMissingEquipmentCannotHideNativeLegacyItems() throws Exception {
        for (boolean addEmptyEquipment : List.of(false, true)) {
            CompoundTag nbt = legacyStand();
            if (addEmptyEquipment) {
                nbt.put("equipment", new CompoundTag());
            }
            assertConsumers(
                    nbt,
                    QuickLitematicaEntityPlacementAccess.usesEntityEquipment() ? List.of(
                            new ItemStack(Items.ARMOR_STAND)
                    ) : List.of(new ItemStack(Items.ARMOR_STAND), diamond())
            );
        }
    }

    @Test
    void allNativeEquipmentSlotsKeepComponentsAndDoNotDoubleCountLegacy() throws Exception {
        CompoundTag nbt = legacyStand();
        CompoundTag equipment = new CompoundTag();
        for (String slot : List.of("mainhand", "offhand", "head", "chest", "legs", "feet", "body", "saddle")) {
            equipment.put(slot, encode(emerald()));
        }
        nbt.put("equipment", equipment);
        ItemStack expected = emerald();
        expected.setCount(expected.getCount() * 8);
        assertConsumers(
                nbt,
                List.of(
                        new ItemStack(Items.ARMOR_STAND),
                        QuickLitematicaEntityPlacementAccess.usesEntityEquipment() ? expected : diamond()
                )
        );
    }

    @Test
    void passengerEquipmentUsesSameFormatAsRoot() throws Exception {
        CompoundTag root = stand();
        CompoundTag passenger = legacyStand();
        CompoundTag equipment = new CompoundTag();
        equipment.put("head", encode(emerald()));
        passenger.put("equipment", equipment);
        ListTag passengers = new ListTag();
        passengers.add(passenger);
        root.put("Passengers", passengers);
        assertConsumers(
                root,
                List.of(
                        new ItemStack(Items.ARMOR_STAND, 2),
                        QuickLitematicaEntityPlacementAccess.usesEntityEquipment() ? emerald() : diamond()
                )
        );
    }

    private void assertConsumers(CompoundTag nbt, List<ItemStack> expected) throws Exception {
        Minecraft client = mock(Minecraft.class);
        client.level = mock(ClientLevel.class);
        when(client.level.registryAccess()).thenReturn(lookup);
        ServerLevel server = mock(ServerLevel.class);
        when(server.registryAccess()).thenReturn(lookup);
        try (var singleton = mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(client);
            Method clientMaterials = QuickLitematicaEntityPlacement.class.getDeclaredMethod(
                    "getMaterials",
                    EntityType.class,
                    CompoundTag.class
            );
            clientMaterials.setAccessible(true);
            Method serverMaterials = QuickLitematicaEntityPlacementServer.class.getDeclaredMethod(
                    "materialsForTree",
                    CompoundTag.class,
                    ServerLevel.class,
                    ServerPlayer.class
            );
            serverMaterials.setAccessible(true);
            assertStacks(
                    stacks(
                            clientMaterials.invoke(
                                    null,
                                    BuiltInRegistries.ENTITY_TYPE.getValue(
                                            net.minecraft.resources.Identifier.withDefaultNamespace("armor_stand")
                                    ),
                                    nbt
                            )
                    ),
                    expected
            );
            assertStacks(stacks(serverMaterials.invoke(null, nbt, server, null)), expected);
            List<ItemStack> hud = new ArrayList<>();
            QuickLitematicaEntityMaterials.appendMaterials(nbt, lookup, hud, 0);
            assertStacks(hud, expected);
        }
    }

    private static void assertStacks(List<ItemStack> actual, List<ItemStack> expected) {
        assertThat(actual).isNotNull();
        assertThat(actual.stream().mapToInt(ItemStack::getCount).sum()).isEqualTo(
                expected.stream().mapToInt(ItemStack::getCount).sum()
        );
        for (ItemStack wanted : expected) {
            assertThat(
                    actual.stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, wanted)).mapToInt(
                            ItemStack::getCount
                    ).sum()
            ).isEqualTo(wanted.getCount());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> stacks(Object value) {
        return (List<ItemStack>) value;
    }

    private CompoundTag legacyStand() {
        CompoundTag nbt = stand();
        ListTag hand = new ListTag();
        hand.add(encode(diamond()));
        hand.add(new CompoundTag());
        nbt.put("HandItems", hand);
        return nbt;
    }

    private CompoundTag encode(ItemStack stack) {
        return (CompoundTag) ItemStack.CODEC.encodeStart(lookup.createSerializationContext(NbtOps.INSTANCE), stack)
                .getOrThrow();
    }

    private static CompoundTag stand() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:armor_stand");
        return nbt;
    }

    private static ItemStack diamond() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 3);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("legacy equipment"));
        return stack;
    }

    private static ItemStack emerald() {
        ItemStack stack = new ItemStack(Items.EMERALD, 2);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("native equipment"));
        return stack;
    }
}
