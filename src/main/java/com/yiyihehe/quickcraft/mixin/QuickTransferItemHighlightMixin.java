package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTransfer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在快速转移的同类物品槽位下方绘制轻量底色，帮助确认一次转移会影响哪些格子。
 */
@Mixin(HandledScreen.class)
public abstract class QuickTransferItemHighlightMixin<T extends ScreenHandler> {
    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void quickcraft$drawMatchingTransferHighlight(
            DrawContext context,
            Slot slot,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        if (QuickTransfer.shouldHighlightMatchingSlot(screen, slot)) {
            context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x553F6FFF);
        }
    }
}
