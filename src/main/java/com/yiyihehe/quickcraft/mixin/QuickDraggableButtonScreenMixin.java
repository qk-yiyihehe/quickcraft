package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.render.QuickDraggableButton;
//#if MC>=12110
//$$ import net.minecraft.client.gui.Click;
//#endif
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forwards drag lifecycle events that HandledScreen otherwise reserves for slot dragging. */
@Mixin(HandledScreen.class)
public abstract class QuickDraggableButtonScreenMixin {
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    //#if MC>=12110
    //$$ private void quickcraft$dragActionButton(Click click, double deltaX, double deltaY,
    //#else
    private void quickcraft$dragActionButton(double mouseX, double mouseY, int button,
                                             double deltaX, double deltaY,
    //#endif
                                             CallbackInfoReturnable<Boolean> cir) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        if (screen.getFocused() instanceof QuickDraggableButton actionButton
                && actionButton.isPositionDragging()) {
            //#if MC>=12110
            //$$ cir.setReturnValue(actionButton.mouseDragged(click, deltaX, deltaY));
            //#else
            cir.setReturnValue(actionButton.mouseDragged(mouseX, mouseY, button, deltaX, deltaY));
            //#endif
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    //#if MC>=12110
    //$$ private void quickcraft$releaseActionButton(Click click, CallbackInfoReturnable<Boolean> cir) {
    //#else
    private void quickcraft$releaseActionButton(double mouseX, double mouseY, int button,
                                                CallbackInfoReturnable<Boolean> cir) {
    //#endif
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        if (!(screen.getFocused() instanceof QuickDraggableButton actionButton)) {
            return;
        }
        //#if MC>=12110
        //$$ int button = click.button();
        //#endif
        if (button == 0 && actionButton.isPositionDragging()) {
            //#if MC>=12110
            //$$ boolean handled = actionButton.mouseReleased(click);
            //#else
            boolean handled = actionButton.mouseReleased(mouseX, mouseY, button);
            //#endif
            screen.setDragging(false);
            cir.setReturnValue(handled);
        } else if (button == 1 && actionButton.consumeRightRelease()) {
            cir.setReturnValue(true);
        }
    }
}
