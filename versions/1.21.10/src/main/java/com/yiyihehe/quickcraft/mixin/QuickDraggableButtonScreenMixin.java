package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import com.yiyihehe.quickcraft.portablestorage.QuickPortableStoragePanel;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.10+ 转发 HandledScreen 预留给槽位拖拽的拖拽生命周期事件（Click 对象）。
 */
@Mixin(HandledScreen.class)
public abstract class QuickDraggableButtonScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$clickPortableStorage(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (QuickPortableStoragePanel.click((HandledScreen<?>) (Object) this, click.x(), click.y(), click.button())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void quickcraft$keyPortableStorage(KeyInput event, CallbackInfoReturnable<Boolean> cir) {
        if (QuickPortableStoragePanel.key((HandledScreen<?>) (Object) this, event.key(), event.scancode(), event.modifiers())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void quickcraft$dragActionButton(Click click, double deltaX, double deltaY,
                                             CallbackInfoReturnable<Boolean> cir) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        if (screen.getFocused() instanceof QuickDraggableButton actionButton
                && actionButton.isPositionDragging()) {
            cir.setReturnValue(actionButton.mouseDragged(click, deltaX, deltaY));
        } else if (QuickPortableStoragePanel.drag(screen, click.x(), click.y(), click.button())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void quickcraft$releaseActionButton(Click click, CallbackInfoReturnable<Boolean> cir) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        if (!(screen.getFocused() instanceof QuickDraggableButton actionButton)) {
            if (QuickPortableStoragePanel.release(screen, click.x(), click.y(), click.button())) {
                cir.setReturnValue(true);
            }
            return;
        }
        int button = click.button();
        if (button == 0 && actionButton.isPositionDragging()) {
            boolean handled = actionButton.mouseReleased(click);
            screen.setDragging(false);
            cir.setReturnValue(handled);
        } else if ((button == 1 && actionButton.consumeRightRelease())
                || QuickPortableStoragePanel.release(screen, click.x(), click.y(), click.button())) {
            cir.setReturnValue(true);
        }
    }
}
