package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickTransferHighlightCacheTest {
    @Test
    void oneResolutionIsReusedByHighlightConsumers() throws ReflectiveOperationException {
        try (var f = new Fixture()) {
            f.mouse(0.25, 0.25);
            var hovered = QuickTransfer.resolveHighlightSlot(f.screen);
            assertThat(hovered).isSameAs(f.first);
            assertThat(QuickTransfer.shouldHighlightMatchingSlot(f.screen, f.first, hovered)).isTrue();
            assertThat(QuickTransfer.shouldHighlightMatchingSlot(f.screen, f.second, hovered)).isTrue();
            verify(f.client.mouseHandler, times(1)).getScaledXPos(f.client.getWindow());
            f.mouse(18.25, 0.25);
            assertThat(QuickTransfer.resolveHighlightSlot(f.screen)).isSameAs(f.second);
        }
    }

    @Test
    void strictBoundsArePreservedInsteadOfVanillaPaddedHover() throws ReflectiveOperationException {
        try (var f = new Fixture()) {
            for (double x : new double[] {-0.25, 36.0, 37.75}) {
                f.mouse(x, 0.25);
                assertThat(QuickTransfer.resolveHighlightSlot(f.screen)).as("mouse x=%s", x).isNull();
            }
            f.mouse(17.75, 0.25);
            assertThat(QuickTransfer.resolveHighlightSlot(f.screen)).isSameAs(f.first);
            f.mouse(18.25, 0.25);
            assertThat(QuickTransfer.resolveHighlightSlot(f.screen)).isSameAs(f.second);
        }
    }

    @Test
    void noHoverDoesNotCauseConsumersToRescanAndDisabledHighlightSkipsResolution() throws ReflectiveOperationException {
        try (var f = new Fixture()) {
            f.mouse(36, 0);
            var hovered = QuickTransfer.resolveHighlightSlot(f.screen);
            assertThat(hovered).isNull();
            assertThat(QuickTransfer.shouldHighlightMatchingSlot(f.screen, f.first, hovered)).isFalse();
            assertThat(QuickTransfer.shouldHighlightMatchingSlot(f.screen, f.second, hovered)).isFalse();
            verify(f.client.mouseHandler, times(1)).getScaledXPos(f.client.getWindow());
            clearInvocations(f.client.mouseHandler);
            QuickCraftConfigs.ContainerTools.SHOW_MATCHING_TRANSFER_HIGHLIGHT.setBooleanValue(false);
            assertThat(QuickTransfer.resolveHighlightSlot(f.screen)).isNull();
            verifyNoInteractions(f.client.mouseHandler);
        }
    }

    @Test
    void componentMismatchAndOccupiedCursorKeepHighlightOff() throws ReflectiveOperationException {
        try (var f = new Fixture()) {
            f.mouse(0, 0);
            var hovered = QuickTransfer.resolveHighlightSlot(f.screen);
            f.second.getItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("other"));
            assertThat(QuickTransfer.shouldHighlightMatchingSlot(f.screen, f.second, hovered)).isFalse();
            f.handler.setCarried(new ItemStack(Items.STONE));
            assertThat(QuickTransfer.shouldHighlightMatchingSlot(f.screen, f.first, hovered)).isFalse();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final boolean transfer = QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRANSFER.getBooleanValue();
        private final boolean highlight = QuickCraftConfigs.ContainerTools.SHOW_MATCHING_TRANSFER_HIGHLIGHT.getBooleanValue();
        private final org.mockito.MockedStatic<Minecraft> minecraft = mockStatic(Minecraft.class);
        private final Minecraft client = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        private final SimpleContainer inventory = new SimpleContainer(2);
        private final Slot first = new Slot(inventory, 0, 0, 0);
        private final Slot second = new Slot(inventory, 1, 18, 0);
        private final TestHandler handler = new TestHandler(List.of(first, second));
        private final AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class, withSettings().extraInterfaces(HandledScreenAccessor.class));

        private Fixture() throws ReflectiveOperationException {
            inventory.setItem(0, new ItemStack(Items.STONE, 2));
            inventory.setItem(1, new ItemStack(Items.STONE));
            client.player = mock(net.minecraft.client.player.LocalPlayer.class);
            client.gameMode = mock(net.minecraft.client.multiplayer.MultiPlayerGameMode.class);
            var mouseField = Minecraft.class.getDeclaredField("mouseHandler");
            mouseField.setAccessible(true);
            mouseField.set(client, mock(net.minecraft.client.MouseHandler.class));
            when(screen.getMenu()).thenReturn(handler);
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRANSFER.setBooleanValue(true);
            QuickCraftConfigs.ContainerTools.SHOW_MATCHING_TRANSFER_HIGHLIGHT.setBooleanValue(true);
        }

        private void mouse(double x, double y) {
            when(client.mouseHandler.getScaledXPos(client.getWindow())).thenReturn(x);
            when(client.mouseHandler.getScaledYPos(client.getWindow())).thenReturn(y);
        }

        @Override
        public void close() {
            minecraft.close();
            QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRANSFER.setBooleanValue(transfer);
            QuickCraftConfigs.ContainerTools.SHOW_MATCHING_TRANSFER_HIGHLIGHT.setBooleanValue(highlight);
        }
    }

    private static final class TestHandler extends AbstractContainerMenu {
        private TestHandler(List<Slot> slots) {
            super(null, 0);
            slots.forEach(this::addSlot);
        }

        @Override
        public boolean stillValid(Player player) { return true; }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    }
}
