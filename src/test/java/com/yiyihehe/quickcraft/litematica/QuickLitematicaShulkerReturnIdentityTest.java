package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaShulkerReturnIdentityTest {
    @TempDir Path gameDirectory;

    @Test
    void movedOriginalIsPreferredOverOldSlotWithSameBodyAndDifferentContents() throws Exception {
        PlayerInventory inventory = newInventory();
        inventory.setStack(10, namedBox(null, new ItemStack(Items.IRON_INGOT)));
        inventory.setStack(11, namedBox(null, new ItemStack(Items.DIRT)));
        assertThat(findReturnShulker(inventory, materialFrom(namedBox(null, new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(11);
    }

    @Test
    void sameIdentifiableBoxWithChangedContentsKeepsExistingFallback() throws Exception {
        PlayerInventory inventory = newInventory();
        inventory.setStack(10, namedBox("A", new ItemStack(Items.IRON_INGOT)));
        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(10);
    }

    @Test
    void extractionCallbackRecordsActualComponentsAndReturnsRemainingStackToMovedSource() throws Exception {
        PlayerInventory inventory = newInventory();
        ItemStack extracted = new ItemStack(Items.STONE, 64);
        extracted.set(DataComponentTypes.CUSTOM_NAME, Text.literal("extracted"));
        ItemStack source = namedBox(null, extracted, new ItemStack(Items.DIRT));
        inventory.setStack(10, source);
        MinecraftClient client = clientWith(inventory);
        setField(client, "runDirectory", gameDirectory.toFile());
        var world = mock(net.minecraft.client.world.ClientWorld.class);
        when(world.getEnabledFeatures()).thenReturn(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES);
        when(QuickLitematicaEntityPlacementAccess.getEntityWorld(client.player)).thenReturn(world);
        when(client.player.getAbilities()).thenReturn(new net.minecraft.entity.player.PlayerAbilities());
        AtomicReference<ShulkerBoxScreenHandler> opened = new AtomicReference<>(new ShulkerBoxScreenHandler(7, inventory));
        opened.get().getSlot(0).setStack(extracted.copy());
        opened.get().getSlot(1).setStack(new ItemStack(Items.DIRT));
        doAnswer(call -> {
            opened.get().onSlotClick(call.getArgument(1), call.getArgument(2), call.getArgument(3), client.player);
            return null;
        }).when(client.interactionManager).clickSlot(anyInt(), anyInt(), anyInt(), any(), eq(client.player));

        try (var minecraft = mockStatic(MinecraftClient.class);
             var picking = mockStatic(fi.dy.masa.litematica.util.InventoryUtils.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            try {
                Class<?> request = nested("PendingRequest");
                Constructor<?> constructor = request.getDeclaredConstructor(ItemStack.class);
                constructor.setAccessible(true);
                setStatic("pendingRequest", constructor.newInstance(new ItemStack(Items.STONE)));
                Object initial = material(new ItemStack(Items.STONE), source, 10);
                openAction(client, opened.get(), initial, "EXTRACT", 0);
                QuickLitematicaShulkerMaterialRestock.onShulkerContentsReceived(7);
                assertThat(inventory.getStack(0).getCount()).isEqualTo(64);
                assertThat(ItemStack.areItemsAndComponentsEqual(inventory.getStack(0), extracted)).isTrue();
                assertThat(opened.get().getSlot(0).hasStack()).isFalse();
                Field records = QuickLitematicaShulkerMaterialRestock.class.getDeclaredField("retrievedMaterials");
                records.setAccessible(true);
                var retrieved = (java.util.Deque<?>) records.get(null);
                assertThat(retrieved).hasSize(1);
                Object recorded = retrieved.getFirst();
                Method identity = recorded.getClass().getDeclaredMethod("identity");
                identity.setAccessible(true);
                assertThat(ItemStack.areItemsAndComponentsEqual((ItemStack) identity.invoke(recorded), extracted)).isTrue();

                // 模拟下一次库存同步：原盒移动，旧位置被另一只同盒体、不同内容的盒占用。
                inventory.setStack(10, namedBox(null, new ItemStack(Items.IRON_INGOT)));
                inventory.setStack(11, namedBox(null, new ItemStack(Items.DIRT)));
                assertThat(findReturnShulker(inventory, recorded)).isEqualTo(11);
                inventory.getStack(0).setCount(12);
                ItemStack later = new ItemStack(Items.STONE);
                later.set(DataComponentTypes.CUSTOM_NAME, Text.literal("later"));
                inventory.setStack(5, later);
                Method candidate = QuickLitematicaShulkerMaterialRestock.class
                        .getDeclaredMethod("getReturnCandidate", PlayerInventory.class);
                candidate.setAccessible(true);
                assertThat(candidate.invoke(null, inventory)).isSameAs(recorded);
                opened.set(new ShulkerBoxScreenHandler(8, inventory));
                opened.get().getSlot(0).setStack(new ItemStack(Items.DIRT));
                openAction(client, opened.get(), recorded, "RETURN", -1);
                QuickLitematicaShulkerMaterialRestock.onShulkerContentsReceived(8);
                assertThat(inventory.getStack(0).isEmpty()).isTrue();
                assertThat(ItemStack.areItemsAndComponentsEqual(inventory.getStack(5), later)).isTrue();
                ItemStack returned = opened.get().getSlot(1).getStack();
                assertThat(returned.getCount()).isEqualTo(12);
                assertThat(ItemStack.areItemsAndComponentsEqual(returned, extracted)).isTrue();
                assertThat(retrieved).isEmpty();
                assertThat(opened.get().getCursorStack().isEmpty()).isTrue();
            } finally {
                Method reset = QuickLitematicaShulkerMaterialRestock.class.getDeclaredMethod("resetState", MinecraftClient.class);
                reset.setAccessible(true);
                reset.invoke(null, client);
            }
        }
    }

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(QuickLitematicaShulkerMaterialRestock.class.getName() + "$" + name);
    }

    private static Object enumValue(Class<?> type, String name) throws Exception {
        Field constant = type.getDeclaredField(name);
        constant.setAccessible(true);
        return constant.get(null);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = QuickLitematicaShulkerMaterialRestock.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void openAction(MinecraftClient client, ShulkerBoxScreenHandler handler, Object material,
                                   String action, int hotbar) throws Exception {
        setField(client.player, "currentScreenHandler", handler);
        Class<?> actionType = nested("ActionType");
        Constructor<?> constructor = nested("ActiveAction").getDeclaredConstructor(actionType, material.getClass(), int.class, int.class);
        constructor.setAccessible(true);
        setStatic("activeAction", constructor.newInstance(enumValue(actionType, action), material, hotbar, handler.syncId));
        setStatic("operation", enumValue(nested("Operation"), "WAITING_FOR_OPEN"));
    }

    @Test
    void boxBodyIdentityKeepsNameButIgnoresContents() throws Exception {
        ItemStack namedA = namedBox("A", new ItemStack(Items.DIRT));
        ItemStack namedB = namedBox("B", new ItemStack(Items.DIRT));
        ItemStack movedA = namedBox("A", new ItemStack(Items.STONE));

        Method identityMethod = QuickLitematicaShulkerMaterialRestock.class
                .getDeclaredMethod("boxIdentity", ItemStack.class);
        identityMethod.setAccessible(true);
        ItemStack identityA = (ItemStack) identityMethod.invoke(null, namedA);
        ItemStack identityB = (ItemStack) identityMethod.invoke(null, namedB);
        ItemStack identityMovedA = (ItemStack) identityMethod.invoke(null, movedA);

        assertThat(ItemStack.areEqual(identityA, identityB)).isFalse();
        assertThat(ItemStack.areEqual(identityA, identityMovedA)).isTrue();
    }

    @Test
    void originalSlotWinsWhenEarlierBoxHasSameContentsButDifferentName() throws Exception {
        PlayerInventory inventory = newInventory();
        inventory.setStack(9, namedBox("B", new ItemStack(Items.DIRT)));
        inventory.setStack(10, namedBox("A", new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(10);
    }

    @Test
    void movedOriginalBoxIsFoundByBodyIdentity() throws Exception {
        PlayerInventory inventory = newInventory();
        inventory.setStack(9, namedBox("B", new ItemStack(Items.DIRT)));
        inventory.setStack(11, namedBox("A", new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(11);
    }

    @Test
    void occupiedOriginalSlotDoesNotClaimTheOtherBox() throws Exception {
        PlayerInventory inventory = newInventory();
        inventory.setStack(10, namedBox("B", new ItemStack(Items.DIRT)));
        inventory.setStack(11, namedBox("A", new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(11);
    }

    @Test
    void unnamedBoxesStillReturnNormally() throws Exception {
        PlayerInventory inventory = newInventory();
        inventory.setStack(4, namedBox(null, new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox(null, new ItemStack(Items.DIRT)), 4)))
                .isEqualTo(4);
    }

    @Test
    void missingDistinctOriginalDoesNotFallBackToAnotherNamedBox() throws Exception {
        PlayerInventory inventory = newInventory();
        inventory.setStack(9, namedBox("B", new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(-1);
    }

    @Test
    void returnUsesTheExtractedComponentIdentityNotAnEarlierSameItemStack() throws Exception {
        PlayerInventory inventory = newInventory();
        ItemStack namedStone = new ItemStack(Items.STONE);
        namedStone.set(DataComponentTypes.CUSTOM_NAME, Text.literal("other"));
        inventory.setStack(5, namedStone);
        inventory.setStack(20, new ItemStack(Items.STONE, 12));

        MinecraftClient client = clientWith(inventory);
        ShulkerBoxScreenHandler handler = new ShulkerBoxScreenHandler(1, inventory);
        Object material = material(new ItemStack(Items.STONE), namedBox("A", new ItemStack(Items.DIRT)), 0);

        invokeReturnMaterial(client, handler, material);

        verify(client.interactionManager).clickSlot(
                eq(handler.syncId), eq(playerSlotId(handler, 20)), eq(0), eq(SlotActionType.PICKUP), eq(client.player));
    }

    @Test
    void returnFindsExtractedStackAfterMovingSlots() throws Exception {
        PlayerInventory inventory = newInventory();
        inventory.setStack(30, new ItemStack(Items.STONE, 7));

        MinecraftClient client = clientWith(inventory);
        ShulkerBoxScreenHandler handler = new ShulkerBoxScreenHandler(1, inventory);
        Object material = material(new ItemStack(Items.STONE), namedBox("A", new ItemStack(Items.DIRT)), 0);

        invokeReturnMaterial(client, handler, material);

        verify(client.interactionManager).clickSlot(
                eq(handler.syncId), eq(playerSlotId(handler, 30)), eq(0), eq(SlotActionType.PICKUP), eq(client.player));
    }

    @Test
    void consumedMaterialDoesNotSelectAnotherComponentStack() throws Exception {
        PlayerInventory inventory = newInventory();
        ItemStack namedStone = new ItemStack(Items.STONE);
        namedStone.set(DataComponentTypes.CUSTOM_NAME, Text.literal("other"));
        inventory.setStack(5, namedStone);

        MinecraftClient client = clientWith(inventory);
        ShulkerBoxScreenHandler handler = new ShulkerBoxScreenHandler(1, inventory);
        Object material = material(new ItemStack(Items.STONE), namedBox("A", new ItemStack(Items.DIRT)), 0);

        invokeReturnMaterial(client, handler, material);

        verify(client.interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
    }

    private static PlayerInventory newInventory() throws Exception {
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        try {
            return PlayerInventory.class.getConstructor(PlayerEntity.class).newInstance(player);
        } catch (NoSuchMethodException ignored) {
            Class<?> equipment = Class.forName("net.minecraft.entity.EntityEquipment");
            return PlayerInventory.class.getConstructor(PlayerEntity.class, equipment)
                    .newInstance(player, equipment.getConstructor().newInstance());
        }
    }

    private static MinecraftClient clientWith(PlayerInventory inventory) throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        setField(client, "player", mock(ClientPlayerEntity.class));
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        setField(client, "interactionManager", interactionManager);
        when(client.player.getInventory()).thenReturn(inventory);
        return client;
    }

    private static ItemStack namedBox(String name, ItemStack... contents) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        if (name != null) {
            box.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        }
        box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(List.of(contents)));
        return box;
    }

    private static Object materialFrom(ItemStack box, int slot) throws Exception {
        return material(new ItemStack(Items.STONE), box, slot);
    }

    private static Object material(ItemStack identity, ItemStack box, int slot) throws Exception {
        Class<?> trackedClass = Class.forName(
                "com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock$TrackedShulker");
        Method from = trackedClass.getDeclaredMethod("from", ItemStack.class, int.class);
        from.setAccessible(true);
        Object tracked = from.invoke(null, box, slot);
        Constructor<?> constructor = Class.forName(
                        "com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock$RetrievedMaterial")
                .getDeclaredConstructor(ItemStack.class, trackedClass);
        constructor.setAccessible(true);
        return constructor.newInstance(identity, tracked);
    }

    private static int findReturnShulker(PlayerInventory inventory, Object material) throws Exception {
        Class<?> materialClass = material.getClass();
        Method method = QuickLitematicaShulkerMaterialRestock.class
                .getDeclaredMethod("findReturnShulker", PlayerInventory.class, materialClass);
        method.setAccessible(true);
        try (var configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::isLitematicaShulkerMaterialOrderlyStorageEnabled).thenReturn(true);
            return (int) method.invoke(null, inventory, material);
        }
    }

    private static void invokeReturnMaterial(MinecraftClient client,
                                             ShulkerBoxScreenHandler handler,
                                             Object material) throws Exception {
        Method method = QuickLitematicaShulkerMaterialRestock.class
                .getDeclaredMethod("returnMaterial", MinecraftClient.class, ShulkerBoxScreenHandler.class,
                        material.getClass());
        method.setAccessible(true);
        method.invoke(null, client, handler, material);
    }

    private static int playerSlotId(ShulkerBoxScreenHandler handler, int inventoryIndex) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == inventoryIndex) {
                return slot.id;
            }
        }
        throw new AssertionError("missing player slot " + inventoryIndex);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
