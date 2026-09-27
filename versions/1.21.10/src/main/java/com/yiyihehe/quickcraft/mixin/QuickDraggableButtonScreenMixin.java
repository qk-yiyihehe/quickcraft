package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forwards drag lifecycle events that HandledScreen otherwise reserves for slot dragging. */
@Mixin(HandledScreen.class)
public abstract class QuickDraggableButtonScreenMixin {
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void quickcraft$dragActionButton(Click click, double deltaX, double deltaY,
                                             CallbackInfoReturnable<Boolean> cir) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        if (screen.getFocused() instanceof QuickDraggableButton actionButton
                && actionButton.isPositionDragging()) {
            cir.setReturnValue(actionButton.mouseDragged(click, deltaX, deltaY));
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void quickcraft$releaseActionButton(Click click, CallbackInfoReturnable<Boolean> cir) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        if (!(screen.getFocused() instanceof QuickDraggableButton actionButton)) {
            return;
        }
        if (click.button() == 0 && actionButton.isPositionDragging()) {
            boolean handled = actionButton.mouseReleased(click);
            screen.setDragging(false);
            cir.setReturnValue(handled);
        } else if (click.button() == 1 && actionButton.consumeRightRelease()) {
            cir.setReturnValue(true);
        }
    }
}
