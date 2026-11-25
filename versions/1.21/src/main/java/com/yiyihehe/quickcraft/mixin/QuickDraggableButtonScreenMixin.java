package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import com.yiyihehe.quickcraft.portablestorage.QuickPortableStoragePanel;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21–1.21.8 转发 HandledScreen 预留给槽位拖拽的拖拽生命周期事件。
 */
@Mixin(HandledScreen.class)
public abstract class QuickDraggableButtonScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$clickPortableStorage(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (QuickPortableStoragePanel.click((HandledScreen<?>) (Object) this, mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void quickcraft$keyPortableStorage(
            int key,
            int scancode,
            int modifiers,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (QuickPortableStoragePanel.key((HandledScreen<?>) (Object) this, key, scancode, modifiers)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void quickcraft$dragActionButton(
            double mouseX,
            double mouseY,
            int button,
            double deltaX,
            double deltaY,
            CallbackInfoReturnable<Boolean> cir
    ) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        if (screen.getFocused() instanceof QuickDraggableButton actionButton && actionButton.isPositionDragging()) {
            cir.setReturnValue(actionButton.mouseDragged(mouseX, mouseY, button, deltaX, deltaY));
        } else if (QuickPortableStoragePanel.drag(screen, mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void quickcraft$releaseActionButton(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> cir
    ) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        if (!(screen.getFocused() instanceof QuickDraggableButton actionButton)) {
            if (QuickPortableStoragePanel.release(screen, mouseX, mouseY, button)) {
                cir.setReturnValue(true);
            }
            return;
        }
        if (button == 0 && actionButton.isPositionDragging()) {
            boolean handled = actionButton.mouseReleased(mouseX, mouseY, button);
            screen.setDragging(false);
            cir.setReturnValue(handled);
        } else if ((button == 1 && actionButton.consumeRightRelease()) || QuickPortableStoragePanel.release(
                screen,
                mouseX,
                mouseY,
                button
        )) {
            cir.setReturnValue(true);
        }
    }
}
