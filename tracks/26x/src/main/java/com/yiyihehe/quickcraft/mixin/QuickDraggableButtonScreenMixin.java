package com.yiyihehe.quickcraft.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import com.yiyihehe.quickcraft.portablestorage.QuickPortableStoragePanel;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forwards drag lifecycle events that AbstractContainerScreen otherwise reserves for slot dragging. */
@Mixin(AbstractContainerScreen.class)
public abstract class QuickDraggableButtonScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$clickPortableStorage(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (QuickPortableStoragePanel.click((AbstractContainerScreen<?>) (Object) this, event.x(), event.y(), event.button())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void quickcraft$keyPortableStorage(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (QuickPortableStoragePanel.key((AbstractContainerScreen<?>) (Object) this, event)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void quickcraft$dragActionButton(MouseButtonEvent event, double deltaX, double deltaY,
                                             CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (screen.getFocused() instanceof QuickDraggableButton actionButton
                && actionButton.isPositionDragging()) {
            cir.setReturnValue(actionButton.mouseDragged(event, deltaX, deltaY));
        } else if (QuickPortableStoragePanel.drag(screen, event.x(), event.y(), event.button())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void quickcraft$releaseActionButton(MouseButtonEvent event,
                                                CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (!(screen.getFocused() instanceof QuickDraggableButton actionButton)) {
            if (QuickPortableStoragePanel.release(screen, event.x(), event.y(), event.button())) {
                cir.setReturnValue(true);
            }
            return;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && actionButton.isPositionDragging()) {
            boolean handled = actionButton.mouseReleased(event);
            QuickClientScreenAccess.clearDraggingState(screen);
            cir.setReturnValue(handled);
        } else if ((event.button() == InputConstants.MOUSE_BUTTON_RIGHT && actionButton.consumeRightRelease())
                || QuickPortableStoragePanel.release(screen, event.x(), event.y(), event.button())) {
            cir.setReturnValue(true);
        }
    }
}
