package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forwards drag lifecycle events that AbstractContainerScreen otherwise reserves for slot dragging. */
@Mixin(AbstractContainerScreen.class)
public abstract class QuickDraggableButtonScreenMixin {
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void quickcraft$dragActionButton(MouseButtonEvent event, double deltaX, double deltaY,
                                             CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (screen.getFocused() instanceof QuickDraggableButton actionButton
                && actionButton.isPositionDragging()) {
            cir.setReturnValue(actionButton.mouseDragged(event, deltaX, deltaY));
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void quickcraft$releaseActionButton(MouseButtonEvent event,
                                                CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (!(screen.getFocused() instanceof QuickDraggableButton actionButton)) {
            return;
        }
        if (event.button() == 0 && actionButton.isPositionDragging()) {
            boolean handled = actionButton.mouseReleased(event);
            screen.clearDraggingState();
            cir.setReturnValue(handled);
        } else if (event.button() == 1 && actionButton.consumeRightRelease()) {
            cir.setReturnValue(true);
        }
    }
}
