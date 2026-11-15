package com.yiyihehe.quickcraft;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickClientScreenDraggingTest {
    @Test
    void interceptedButtonReleaseClearsVanillaDraggingStateAndIsIdempotent() {
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class, CALLS_REAL_METHODS);
        screen.setDragging(true);
        assertThat(screen.isDragging()).isTrue();
        QuickClientScreenAccess.clearDraggingState(screen);
        assertThat(screen.isDragging()).isFalse();
        QuickClientScreenAccess.clearDraggingState(screen);
        assertThat(screen.isDragging()).isFalse();
    }
}
