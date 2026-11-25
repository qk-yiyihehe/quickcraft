package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTransfer;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HandledScreen.class)
public abstract class QuickTransferItemHighlightMixin<T extends ScreenHandler> {
    // 悬停快照只属于本次槽位绘制调用；嵌套绘制、异常或取消不会遗留静态屏幕状态。
    @WrapOperation(
            method = { "render", "drawSlots" },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawSlot(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/screen/slot/Slot;)V"))
    private void quickcraft$drawMatchingTransferHighlight(
            HandledScreen<?> screen,
            DrawContext context,
            Slot slot,
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
        original.call(screen, context, slot);
    }
}
