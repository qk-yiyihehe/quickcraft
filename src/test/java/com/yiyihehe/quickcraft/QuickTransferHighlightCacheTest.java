package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
            verify(f.client.mouse, times(1)).getX();
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
            verify(f.client.mouse, times(1)).getX();
            clearInvocations(f.client.mouse);
            QuickCraftConfigs.ContainerTools.SHOW_MATCHING_TRANSFER_HIGHLIGHT.setBooleanValue(false);
            assertThat(QuickTransfer.resolveHighlightSlot(f.screen)).isNull();
            verifyNoInteractions(f.client.mouse);
        }
    }

    @Test
    void componentMismatchAndOccupiedCursorKeepHighlightOff() throws ReflectiveOperationException {
        try (var f = new Fixture()) {
            f.mouse(0, 0);
            var hovered = QuickTransfer.resolveHighlightSlot(f.screen);
            f.second.getStack().set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("other"));
            assertThat(QuickTransfer.shouldHighlightMatchingSlot(f.screen, f.second, hovered)).isFalse();
            f.handler.setCursorStack(new ItemStack(Items.STONE));
            assertThat(QuickTransfer.shouldHighlightMatchingSlot(f.screen, f.first, hovered)).isFalse();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final boolean transfer = QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRANSFER.getBooleanValue();
        private final boolean highlight = QuickCraftConfigs.ContainerTools.SHOW_MATCHING_TRANSFER_HIGHLIGHT.getBooleanValue();
        private final org.mockito.MockedStatic<MinecraftClient> minecraft = mockStatic(MinecraftClient.class);
        private final MinecraftClient client = mock(MinecraftClient.class, RETURNS_DEEP_STUBS);
        private final SimpleInventory inventory = new SimpleInventory(2);
        private final Slot first = new Slot(inventory, 0, 0, 0);
        private final Slot second = new Slot(inventory, 1, 18, 0);
        private final TestHandler handler = new TestHandler(List.of(first, second));
        private final HandledScreen<?> screen = mock(HandledScreen.class, withSettings().extraInterfaces(HandledScreenAccessor.class));

        private Fixture() throws ReflectiveOperationException {
            inventory.setStack(0, new ItemStack(Items.STONE, 2));
            inventory.setStack(1, new ItemStack(Items.STONE));
            client.player = mock(net.minecraft.client.network.ClientPlayerEntity.class);
            client.interactionManager = mock(net.minecraft.client.network.ClientPlayerInteractionManager.class);
            var mouseField = MinecraftClient.class.getDeclaredField("mouse");
            mouseField.setAccessible(true);
            mouseField.set(client, mock(net.minecraft.client.Mouse.class));
            when(client.getWindow().getScaledWidth()).thenReturn(100);
            when(client.getWindow().getScaledHeight()).thenReturn(100);
            when(client.getWindow().getWidth()).thenReturn(100);
            when(client.getWindow().getHeight()).thenReturn(100);
            when(screen.getScreenHandler()).thenReturn(handler);
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRANSFER.setBooleanValue(true);
            QuickCraftConfigs.ContainerTools.SHOW_MATCHING_TRANSFER_HIGHLIGHT.setBooleanValue(true);
        }

        private void mouse(double x, double y) {
            when(client.mouse.getX()).thenReturn(x);
            when(client.mouse.getY()).thenReturn(y);
        }

        @Override
        public void close() {
            minecraft.close();
            QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRANSFER.setBooleanValue(transfer);
            QuickCraftConfigs.ContainerTools.SHOW_MATCHING_TRANSFER_HIGHLIGHT.setBooleanValue(highlight);
        }
    }

    private static final class TestHandler extends ScreenHandler {
        private TestHandler(List<Slot> slots) {
            super(null, 0);
            slots.forEach(this::addSlot);
        }

        @Override
        public boolean canUse(PlayerEntity player) { return true; }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
    }
}
