package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
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
        Inventory inventory = newInventory();
        inventory.setItem(10, namedBox(null, new ItemStack(Items.IRON_INGOT)));
        inventory.setItem(11, namedBox(null, new ItemStack(Items.DIRT)));
        assertThat(findReturnShulker(inventory, materialFrom(namedBox(null, new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(11);
    }

    @Test
    void sameIdentifiableBoxWithChangedContentsKeepsExistingFallback() throws Exception {
        Inventory inventory = newInventory();
        inventory.setItem(10, namedBox("A", new ItemStack(Items.IRON_INGOT)));
        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(10);
    }

    @Test
    void extractionCallbackRecordsActualComponentsAndReturnsRemainingStackToMovedSource() throws Exception {
        Inventory inventory = newInventory();
        ItemStack extracted = new ItemStack(Items.STONE, 64);
        extracted.set(DataComponents.CUSTOM_NAME, Component.literal("extracted"));
        ItemStack source = namedBox(null, extracted, new ItemStack(Items.DIRT));
        inventory.setItem(10, source);
        Minecraft client = clientWith(inventory);
        setField(client, "gameDirectory", gameDirectory.toFile());
        setField(client, "gui", mock(net.minecraft.client.gui.Gui.class));
        var world = mock(net.minecraft.client.multiplayer.ClientLevel.class);
        when(world.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
        when(client.player.level()).thenReturn(world);
        when(client.player.getAbilities()).thenReturn(new net.minecraft.world.entity.player.Abilities());
        AtomicReference<ShulkerBoxMenu> opened = new AtomicReference<>(new ShulkerBoxMenu(7, inventory));
        opened.get().getSlot(0).set(extracted.copy());
        opened.get().getSlot(1).set(new ItemStack(Items.DIRT));
        doAnswer(call -> {
            opened.get().clicked(call.getArgument(1), call.getArgument(2), call.getArgument(3), client.player);
            return null;
        }).when(client.gameMode).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), eq(client.player));

        try (var minecraft = mockStatic(Minecraft.class);
             var picking = mockStatic(fi.dy.masa.litematica.util.InventoryUtils.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            try {
                Class<?> request = nested("PendingRequest");
                Constructor<?> constructor = request.getDeclaredConstructor(ItemStack.class);
                constructor.setAccessible(true);
                setStatic("pendingRequest", constructor.newInstance(new ItemStack(Items.STONE)));
                Object initial = material(new ItemStack(Items.STONE), source, 10);
                openAction(client, opened.get(), initial, "EXTRACT", 0);
                QuickLitematicaShulkerMaterialRestock.onShulkerContentsReceived(7);
                assertThat(inventory.getItem(0).getCount()).isEqualTo(64);
                assertThat(ItemStack.isSameItemSameComponents(inventory.getItem(0), extracted)).isTrue();
                assertThat(opened.get().getSlot(0).hasItem()).isFalse();
                Field records = QuickLitematicaShulkerMaterialRestock.class.getDeclaredField("retrievedMaterials");
                records.setAccessible(true);
                var retrieved = (java.util.Deque<?>) records.get(null);
                assertThat(retrieved).hasSize(1);
                Object recorded = retrieved.getFirst();
                Method identity = recorded.getClass().getDeclaredMethod("identity");
                identity.setAccessible(true);
                assertThat(ItemStack.isSameItemSameComponents((ItemStack) identity.invoke(recorded), extracted)).isTrue();

                // 模拟下一次库存同步：原盒移动，旧位置被另一只同盒体、不同内容的盒占用。
                inventory.setItem(10, namedBox(null, new ItemStack(Items.IRON_INGOT)));
                inventory.setItem(11, namedBox(null, new ItemStack(Items.DIRT)));
                assertThat(findReturnShulker(inventory, recorded)).isEqualTo(11);
                inventory.getItem(0).setCount(12);
                ItemStack later = new ItemStack(Items.STONE);
                later.set(DataComponents.CUSTOM_NAME, Component.literal("later"));
                inventory.setItem(5, later);
                Method candidate = QuickLitematicaShulkerMaterialRestock.class
                        .getDeclaredMethod("getReturnCandidate", Inventory.class);
                candidate.setAccessible(true);
                assertThat(candidate.invoke(null, inventory)).isSameAs(recorded);
                opened.set(new ShulkerBoxMenu(8, inventory));
                opened.get().getSlot(0).set(new ItemStack(Items.DIRT));
                openAction(client, opened.get(), recorded, "RETURN", -1);
                QuickLitematicaShulkerMaterialRestock.onShulkerContentsReceived(8);
                assertThat(inventory.getItem(0).isEmpty()).isTrue();
                assertThat(ItemStack.isSameItemSameComponents(inventory.getItem(5), later)).isTrue();
                ItemStack returned = opened.get().getSlot(1).getItem();
                assertThat(returned.getCount()).isEqualTo(12);
                assertThat(ItemStack.isSameItemSameComponents(returned, extracted)).isTrue();
                assertThat(retrieved).isEmpty();
                assertThat(opened.get().getCarried().isEmpty()).isTrue();
            } finally {
                Method reset = QuickLitematicaShulkerMaterialRestock.class.getDeclaredMethod("resetState", Minecraft.class);
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

    private static void openAction(Minecraft client, ShulkerBoxMenu handler, Object material,
                                   String action, int hotbar) throws Exception {
        setField(client.player, "containerMenu", handler);
        Class<?> actionType = nested("ActionType");
        Constructor<?> constructor = nested("ActiveAction").getDeclaredConstructor(actionType, material.getClass(), int.class, int.class);
        constructor.setAccessible(true);
        setStatic("activeAction", constructor.newInstance(enumValue(actionType, action), material, hotbar, handler.containerId));
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

        assertThat(ItemStack.matches(identityA, identityB)).isFalse();
        assertThat(ItemStack.matches(identityA, identityMovedA)).isTrue();
    }

    @Test
    void originalSlotWinsWhenEarlierBoxHasSameContentsButDifferentName() throws Exception {
        Inventory inventory = newInventory();
        inventory.setItem(9, namedBox("B", new ItemStack(Items.DIRT)));
        inventory.setItem(10, namedBox("A", new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(10);
    }

    @Test
    void movedOriginalBoxIsFoundByBodyIdentity() throws Exception {
        Inventory inventory = newInventory();
        inventory.setItem(9, namedBox("B", new ItemStack(Items.DIRT)));
        inventory.setItem(11, namedBox("A", new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(11);
    }

    @Test
    void occupiedOriginalSlotDoesNotClaimTheOtherBox() throws Exception {
        Inventory inventory = newInventory();
        inventory.setItem(10, namedBox("B", new ItemStack(Items.DIRT)));
        inventory.setItem(11, namedBox("A", new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(11);
    }

    @Test
    void unnamedBoxesStillReturnNormally() throws Exception {
        Inventory inventory = newInventory();
        inventory.setItem(4, namedBox(null, new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox(null, new ItemStack(Items.DIRT)), 4)))
                .isEqualTo(4);
    }

    @Test
    void missingDistinctOriginalDoesNotFallBackToAnotherNamedBox() throws Exception {
        Inventory inventory = newInventory();
        inventory.setItem(9, namedBox("B", new ItemStack(Items.DIRT)));

        assertThat(findReturnShulker(inventory, materialFrom(namedBox("A", new ItemStack(Items.DIRT)), 10)))
                .isEqualTo(-1);
    }

    @Test
    void returnUsesTheExtractedComponentIdentityNotAnEarlierSameItemStack() throws Exception {
        Inventory inventory = newInventory();
        ItemStack namedStone = new ItemStack(Items.STONE);
        namedStone.set(DataComponents.CUSTOM_NAME, Component.literal("other"));
        inventory.setItem(5, namedStone);
        inventory.setItem(20, new ItemStack(Items.STONE, 12));

        Minecraft client = clientWith(inventory);
        ShulkerBoxMenu handler = new ShulkerBoxMenu(1, inventory);
        Object material = material(new ItemStack(Items.STONE), namedBox("A", new ItemStack(Items.DIRT)), 0);

        invokeReturnMaterial(client, handler, material);

        verify(client.gameMode).handleContainerInput(
                eq(handler.containerId), eq(playerSlotId(handler, 20)), eq(0), eq(ContainerInput.PICKUP), eq(client.player));
    }

    @Test
    void returnFindsExtractedStackAfterMovingSlots() throws Exception {
        Inventory inventory = newInventory();
        inventory.setItem(30, new ItemStack(Items.STONE, 7));

        Minecraft client = clientWith(inventory);
        ShulkerBoxMenu handler = new ShulkerBoxMenu(1, inventory);
        Object material = material(new ItemStack(Items.STONE), namedBox("A", new ItemStack(Items.DIRT)), 0);

        invokeReturnMaterial(client, handler, material);

        verify(client.gameMode).handleContainerInput(
                eq(handler.containerId), eq(playerSlotId(handler, 30)), eq(0), eq(ContainerInput.PICKUP), eq(client.player));
    }

    @Test
    void consumedMaterialDoesNotSelectAnotherComponentStack() throws Exception {
        Inventory inventory = newInventory();
        ItemStack namedStone = new ItemStack(Items.STONE);
        namedStone.set(DataComponents.CUSTOM_NAME, Component.literal("other"));
        inventory.setItem(5, namedStone);

        Minecraft client = clientWith(inventory);
        ShulkerBoxMenu handler = new ShulkerBoxMenu(1, inventory);
        Object material = material(new ItemStack(Items.STONE), namedBox("A", new ItemStack(Items.DIRT)), 0);

        invokeReturnMaterial(client, handler, material);

        verify(client.gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
    }

    private static Inventory newInventory() {
        return new Inventory(mock(LocalPlayer.class), new net.minecraft.world.entity.EntityEquipment());
    }

    private static Minecraft clientWith(Inventory inventory) throws Exception {
        Minecraft client = mock(Minecraft.class);
        setField(client, "player", mock(LocalPlayer.class));
        MultiPlayerGameMode interactionManager = mock(MultiPlayerGameMode.class);
        setField(client, "gameMode", interactionManager);
        when(client.player.getInventory()).thenReturn(inventory);
        return client;
    }

    private static ItemStack namedBox(String name, ItemStack... contents) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        if (name != null) {
            box.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        }
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
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

    private static int findReturnShulker(Inventory inventory, Object material) throws Exception {
        Class<?> materialClass = material.getClass();
        Method method = QuickLitematicaShulkerMaterialRestock.class
                .getDeclaredMethod("findReturnShulker", Inventory.class, materialClass);
        method.setAccessible(true);
        try (var configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::isLitematicaShulkerMaterialOrderlyStorageEnabled).thenReturn(true);
            return (int) method.invoke(null, inventory, material);
        }
    }

    private static void invokeReturnMaterial(Minecraft client,
                                             ShulkerBoxMenu handler,
                                             Object material) throws Exception {
        Method method = QuickLitematicaShulkerMaterialRestock.class
                .getDeclaredMethod("returnMaterial", Minecraft.class, ShulkerBoxMenu.class,
                        material.getClass());
        method.setAccessible(true);
        method.invoke(null, client, handler, material);
    }

    private static int playerSlotId(ShulkerBoxMenu handler, int inventoryIndex) {
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory && slot.getContainerSlot() == inventoryIndex) {
                return slot.index;
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
