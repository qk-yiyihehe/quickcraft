package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.QuickShulkerStash;
import com.yiyihehe.quickcraft.QuickSort;
import com.yiyihehe.quickcraft.QuickStash;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickContainerButtonsTest {
    @Test
    void registersOnceRepositionsHidesAndRebuildsWhileActionsKeepTheirOriginalTarget() {
        HandledScreen<?> screen = mock(HandledScreen.class);
        MinecraftClient client = mock(MinecraftClient.class);
        List<QuickDraggableButton> children = new ArrayList<>();
        doReturn(children).when(screen).children();
        Map<QuickDraggableButton.PositionKey, ButtonWidget.PressAction> actions =
                new EnumMap<>(QuickDraggableButton.PositionKey.class);
        try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class);
             MockedStatic<QuickContainerLock> locks = mockStatic(QuickContainerLock.class);
             MockedStatic<QuickSort> sort = mockStatic(QuickSort.class);
             MockedStatic<QuickStash> stash = mockStatic(QuickStash.class);
             MockedStatic<QuickCraftKeyBindings> keys = mockStatic(QuickCraftKeyBindings.class);
             MockedStatic<QuickShulkerStash> shulkerStash = mockStatic(QuickShulkerStash.class);
             MockedConstruction<QuickStashButton> stashButtons = mockConstruction(QuickStashButton.class,
                     (widget, context) -> actions.put((QuickDraggableButton.PositionKey) context.arguments().get(3),
                             (ButtonWidget.PressAction) context.arguments().get(2)));
             MockedConstruction<QuickRetrieveButton> retrieveButtons = mockConstruction(QuickRetrieveButton.class,
                     (widget, context) -> actions.put((QuickDraggableButton.PositionKey) context.arguments().get(3),
                             (ButtonWidget.PressAction) context.arguments().get(2)));
             MockedConstruction<QuickSortButton> sortButtons = mockConstruction(QuickSortButton.class,
                     (widget, context) -> actions.put((QuickDraggableButton.PositionKey) context.arguments().get(3),
                             (ButtonWidget.PressAction) context.arguments().get(2)));
             MockedConstruction<QuickContainerLockButton> lockButtons = mockConstruction(QuickContainerLockButton.class,
                     (widget, context) -> actions.put((QuickDraggableButton.PositionKey) context.arguments().get(4),
                             (ButtonWidget.PressAction) context.arguments().get(2)))) {
            configs.when(QuickCraftConfigs::isQuickStashButtonVisible).thenReturn(true);
            configs.when(QuickCraftConfigs::isQuickSortButtonVisible).thenReturn(true);
            configs.when(QuickCraftConfigs::isContainerLockButtonVisible).thenReturn(true);
            locks.when(() -> QuickContainerLock.shouldShowLockButton(screen)).thenReturn(true);
            QuickContainerButtons buttons = new QuickContainerButtons();
            buttons.sync(screen, client, 10, 20, 176, 222, children::add);
            assertThat(children).hasSize(6);
            List<QuickDraggableButton> original = List.copyOf(children);
            buttons.sync(screen, client, 30, 40, 176, 222, children::add);
            assertThat(children).containsExactlyElementsOf(original);
            verify(children.get(0)).setDefaultPosition(172, 44);
            verify(children.get(1)).setDefaultPosition(172, 166);
            verify(children.get(2)).setDefaultPosition(156, 44);
            verify(children.get(3)).setDefaultPosition(156, 166);
            verify(children.get(4)).setDefaultPosition(188, 166);
            verify(children.get(5)).setDefaultPosition(188, 44);

            configs.when(QuickCraftConfigs::isQuickStashButtonVisible).thenReturn(false);
            configs.when(QuickCraftConfigs::isQuickSortButtonVisible).thenReturn(false);
            configs.when(QuickCraftConfigs::isContainerLockButtonVisible).thenReturn(false);
            locks.when(() -> QuickContainerLock.shouldShowLockButton(screen)).thenReturn(false);
            buttons.sync(screen, client, 30, 40, 176, 222, children::add);
            assertThat(children).allMatch(widget -> !widget.visible);
            configs.when(QuickCraftConfigs::isQuickStashButtonVisible).thenReturn(true);
            configs.when(QuickCraftConfigs::isQuickSortButtonVisible).thenReturn(true);
            configs.when(QuickCraftConfigs::isContainerLockButtonVisible).thenReturn(true);
            locks.when(() -> QuickContainerLock.shouldShowLockButton(screen)).thenReturn(true);
            buttons.sync(screen, client, 30, 40, 176, 222, children::add);
            assertThat(children).containsExactlyElementsOf(original).allMatch(widget -> widget.visible);

            // 原版 resize/init 会清空 children；重新注册后仍只绑定原容器/玩家区域。
            children.clear();
            buttons.sync(screen, client, 30, 40, 176, 222, children::add);
            assertThat(children).hasSize(6).doesNotContainAnyElementsOf(original);
            actions.get(QuickDraggableButton.PositionKey.CONTAINER_SORT).onPress(null);
            actions.get(QuickDraggableButton.PositionKey.INVENTORY_SORT).onPress(null);
            actions.get(QuickDraggableButton.PositionKey.QUICK_STASH).onPress(null);
            actions.get(QuickDraggableButton.PositionKey.QUICK_RETRIEVE).onPress(null);
            actions.get(QuickDraggableButton.PositionKey.INVENTORY_LOCK).onPress(null);
            actions.get(QuickDraggableButton.PositionKey.CONTAINER_LOCK).onPress(null);
            sort.verify(() -> QuickSort.sortFromButton(screen, false));
            sort.verify(() -> QuickSort.sortFromButton(screen, true));
            stash.verify(() -> QuickStash.stashFromButton(screen));
            stash.verify(() -> QuickStash.retrieveFromButton(screen));
            locks.verify(() -> QuickContainerLock.togglePlayerInventoryLock(client));
            locks.verify(() -> QuickContainerLock.toggleCurrentScreenLock(client, screen));

            keys.when(QuickCraftKeyBindings::isAltDown).thenReturn(true);
            actions.get(QuickDraggableButton.PositionKey.QUICK_STASH).onPress(null);
            actions.get(QuickDraggableButton.PositionKey.QUICK_RETRIEVE).onPress(null);
            shulkerStash.verify(() -> QuickShulkerStash.stashContainer(screen));
            shulkerStash.verify(() -> QuickShulkerStash.stashInventory(screen));
            stash.verifyNoMoreInteractions();
        }
    }
}
