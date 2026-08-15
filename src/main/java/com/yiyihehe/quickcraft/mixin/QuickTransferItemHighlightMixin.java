package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTransfer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在快速转移的同类物品槽位下方绘制轻量底色，帮助确认一次转移会影响哪些格子。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class QuickTransferItemHighlightMixin<T extends AbstractContainerMenu> {
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void quickcraft$drawMatchingTransferHighlight(
            GuiGraphicsExtractor context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (QuickTransfer.shouldHighlightMatchingSlot(screen, slot)) {
            context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x553F6FFF);
        }
    }
}
