package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.world.phys.AABB;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 26x 保持拒绝返还物配方；不再用反射造出生产不可达的返还物丢弃状态。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftWorkbenchShulkerRemainderComponentTest {
    @Test
    void remainderRecipeIsRejectedByProductionStartBeforeAnyClick() throws Exception {
        Fixture fixture = new Fixture();
        fixture.handler.getSlot(1).set(new ItemStack(Items.MILK_BUCKET));
        fixture.handler.getSlot(0).set(new ItemStack(Items.STICK));
        setField(fixture.client, "level", mock(ClientLevel.class));
        setField(fixture.player, "containerMenu", fixture.handler);
        when(fixture.player.getBoundingBox()).thenReturn(new AABB(0, 0, 0, 1, 2, 1));
        try (var availability = mockStatic(QuickCraftWorkbenchShulker.class);
                var screens = mockStatic(QuickClientScreenAccess.class)) {
            availability.when(QuickCraftWorkbenchShulker::isAvailable).thenReturn(true);
            screens.when(() -> QuickClientScreenAccess.currentScreen(fixture.client)).thenReturn(
                    mock(CraftingScreen.class)
            );
            Method start = QuickCraftWorkbenchShulkerCraft.class.getDeclaredMethod(
                    "start",
                    Minecraft.class,
                    boolean.class
            );
            start.setAccessible(true);
            assertThat((Boolean) start.invoke(fixture.shulker, fixture.client, true)).isFalse();
        }
        verify(fixture.player).sendOverlayMessage(
                Component.translatable("quickcraft.message.crafting.shulker_recipe_remainder")
        );
        verify(fixture.gameMode, never()).handleContainerInput(
                anyInt(),
                anyInt(),
                anyInt(),
                any(ContainerInput.class),
                any()
        );
        assertThat(fixture.handler.getSlot(1).getItem().is(Items.MILK_BUCKET)).isTrue();
    }

    @Test
    void ingredientCompatibilityRejectsBothPlainAndRenamedBucket() throws Exception {
        Fixture fixture = new Fixture();
        fixture.set("pattern", patternOf(Items.MILK_BUCKET));
        Method compatible = QuickCraftWorkbenchShulkerCraft.class.getDeclaredMethod(
                "isPatternGridCompatible",
                CraftingMenu.class
        );
        compatible.setAccessible(true);
        fixture.handler.getSlot(1).set(new ItemStack(Items.BUCKET));
        assertThat((Boolean) compatible.invoke(fixture.shulker, fixture.handler)).isFalse();
        ItemStack custom = new ItemStack(Items.BUCKET);
        custom.set(DataComponents.CUSTOM_NAME, Component.literal("user bucket"));
        fixture.handler.getSlot(1).set(custom);
        assertThat((Boolean) compatible.invoke(fixture.shulker, fixture.handler)).isFalse();
        verify(fixture.gameMode, never()).handleContainerInput(
                anyInt(),
                anyInt(),
                anyInt(),
                any(ContainerInput.class),
                any()
        );
    }

    @Test
    void outputBoxSlotWithSameItemDifferentComponentsIsNotDiscarded() throws Exception {
        Fixture fixture = new Fixture();
        fixture.set("pattern", patternOf(Items.MILK_BUCKET));

        ItemStack customBucket = new ItemStack(Items.BUCKET);
        customBucket.set(DataComponents.CUSTOM_NAME, Component.literal("user bucket"));
        fixture.handler.getSlot(10).set(customBucket);

        boolean returned = fixture.returnOutputBox(10);

        assertThat(returned).isFalse();
        assertThat(fixture.handler.getSlot(10).getItem().is(Items.BUCKET)).isTrue();
        verify(fixture.gameMode, never()).handleContainerInput(
                anyInt(),
                anyInt(),
                anyInt(),
                any(ContainerInput.class),
                any()
        );
    }

    private static List<ItemStack> patternOf(Item item) {
        List<ItemStack> pattern = new ArrayList<>();
        pattern.add(new ItemStack(item));
        for (int i = 1; i < 9; i++) {
            pattern.add(ItemStack.EMPTY);
        }
        return pattern;
    }

    private static final class Fixture {
        private final Minecraft client = mock(Minecraft.class);
        private final LocalPlayer player = mock(LocalPlayer.class);
        private final MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        private final Inventory inventory;
        private final CraftingMenu handler;
        private final QuickCraftWorkbenchShulkerCraft shulker = new QuickCraftWorkbenchShulkerCraft();

        private Fixture() throws Exception {
            inventory = newInventory(player);
            handler = new CraftingMenu(0, inventory);
            doAnswer(invocation -> {
                if (invocation.getArgument(3) == ContainerInput.THROW) {
                    handler.getSlot(invocation.getArgument(1)).set(ItemStack.EMPTY);
                }
                return null;
            }).when(gameMode).handleContainerInput(
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    any(ContainerInput.class),
                    any(Player.class)
            );
            setField(client, "player", player);
            setField(client, "gameMode", gameMode);
        }

        private boolean returnOutputBox(int sourceSlotId) throws Exception {
            Method method = QuickCraftWorkbenchShulkerCraft.class.getDeclaredMethod(
                    "returnOutputBox",
                    Minecraft.class,
                    CraftingMenu.class,
                    int.class
            );
            method.setAccessible(true);
            return (Boolean) method.invoke(shulker, client, handler, sourceSlotId);
        }

        private void set(String name, Object value) throws Exception {
            setField(shulker, name, value);
        }
    }

    private static Inventory newInventory(LocalPlayer player) throws Exception {
        for (java.lang.reflect.Constructor<?> constructor : Inventory.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(player)) {
                return (Inventory) constructor.newInstance(player);
            }
            if (parameters.length == 2 && parameters[0].isInstance(player) && parameters[1].getSimpleName().equals(
                    "EntityEquipment"
            )) {
                Object equipment = parameters[1].getConstructor().newInstance();
                return (Inventory) constructor.newInstance(player, equipment);
            }
        }
        throw new AssertionError("unsupported Inventory constructor");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
