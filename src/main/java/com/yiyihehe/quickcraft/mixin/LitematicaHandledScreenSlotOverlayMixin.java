package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaVerifierPalette;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatchStatus;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给容器校验中的槽位绘制底色、边框和缺失物品虚影。
 * 用于在打开容器时直观看到少了什么、错了什么。
 */
@Mixin(HandledScreen.class)
public abstract class LitematicaHandledScreenSlotOverlayMixin<T extends ScreenHandler> {
    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void quickcraft$drawContainerVerifierSlotBackground(DrawContext context, Slot slot, CallbackInfo ci) {
        SlotOverlay overlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(
                (HandledScreen<?>) (Object) this,
                slot
        );

        if (overlay == null) {
            return;
        }

        context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, overlay.fillColor());
        quickcraft$drawSlotOutline(context, slot, overlay.borderColor());
    }

    @Redirect(
            method = "drawSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;",
                    ordinal = 0
            )
    )
    private ItemStack quickcraft$replaceMissingSlotStackForGhostDraw(Slot slot) {
        return QuickLitematicaContainerVerifier.getSlotStackForGhostDraw(
                (HandledScreen<?>) (Object) this,
                slot
        );
    }

    @Inject(method = "drawSlot", at = @At("RETURN"))
    private void quickcraft$drawContainerVerifierMissingGhost(DrawContext context, Slot slot, CallbackInfo ci) {
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        boolean drewGhost = QuickLitematicaContainerVerifier.drawPreparedGhostItem(
                context,
                accessor.quickcraft$getGuiLeft(),
                accessor.quickcraft$getGuiTop(),
                QuickLitematicaVerifierPalette.ghostItemAlpha()
        );

        if (!drewGhost) {
            return;
        }

        SlotOverlay overlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(
                (HandledScreen<?>) (Object) this,
                slot
        );

        if (overlay == null) {
            return;
        }

        if (overlay.status() != SlotMismatchStatus.MISSING
                || !slot.getStack().isEmpty()
                || overlay.expectedStack().isEmpty()) {
            return;
        }

        context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, overlay.ghostMaskColor());
        quickcraft$drawSlotOutline(context, slot, overlay.borderColor());
    }

    private static void quickcraft$drawSlotOutline(DrawContext context, Slot slot, int color) {
        int x = slot.x;
        int y = slot.y;
        context.fill(x, y, x + 16, y + 1, color);
        context.fill(x, y + 15, x + 16, y + 16, color);
        context.fill(x, y + 1, x + 1, y + 15, color);
        context.fill(x + 15, y + 1, x + 16, y + 15, color);
    }
}
