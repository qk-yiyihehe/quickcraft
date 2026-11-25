package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTransfer;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractContainerScreen.class)
public abstract class QuickTransferItemHighlightMixin<T extends AbstractContainerMenu> {
    // 悬停快照只属于本次槽位绘制调用；嵌套绘制、异常或取消不会遗留静态屏幕状态。
    @WrapOperation(
            method = "extractSlots",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V"))
    private void quickcraft$drawMatchingTransferHighlight(
            AbstractContainerScreen<?> screen,
            GuiGraphicsExtractor context,
            Slot slot,
            int mouseX,
            int mouseY,
            Operation<Void> original,
            @Share("matchingTransferResolved") LocalBooleanRef resolved,
            @Share("matchingTransferHovered") LocalRef<Slot> hovered
    ) {
        if (!resolved.get()) {
            hovered.set(QuickTransfer.resolveHighlightSlot(screen));
            resolved.set(true);
        }
        if (QuickTransfer.shouldHighlightMatchingSlot(screen, slot, hovered.get())) {
            context.fill(
                    slot.x,
                    slot.y,
                    slot.x + 16,
                    slot.y + 16,
                    QuickCraftConfigs.ContainerTools.MATCHING_TRANSFER_HIGHLIGHT_COLOR.getIntegerValue()
            );
        }
        original.call(screen, context, slot, mouseX, mouseY);
    }
}
