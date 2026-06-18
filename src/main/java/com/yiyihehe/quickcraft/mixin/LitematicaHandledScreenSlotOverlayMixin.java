package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaVerifierPalette;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatchStatus;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
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
    private SlotOverlay quickcraft$currentSlotOverlay;
    private boolean quickcraft$ghostSlotRendering;
    private int quickcraft$ghostSlotBorderColor;

    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void quickcraft$drawContainerVerifierSlotBackground(DrawContext context, Slot slot, CallbackInfo ci) {
        this.quickcraft$currentSlotOverlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(
                (HandledScreen<?>) (Object) this,
                slot
        );
        SlotOverlay overlay = this.quickcraft$currentSlotOverlay;

        if (overlay == null) {
            return;
        }

        context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, overlay.fillColor());
        quickcraft$drawSlotOutline(context, slot, overlay.borderColor());

        if (overlay.status() != SlotMismatchStatus.MISSING
                || !slot.getStack().isEmpty()
                || overlay.expectedStack().isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (QuickLitematicaContainerVerifier.beginHandledScreenGhostRender(context, client)) {
            this.quickcraft$ghostSlotBorderColor = overlay.borderColor();
            this.quickcraft$ghostSlotRendering = true;
        }
    }

    @Redirect(
            method = "drawSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;",
                    ordinal = 0
            )
    )
    private net.minecraft.item.ItemStack quickcraft$replaceRenderedSlotStack(Slot slot) {
        if (this.quickcraft$ghostSlotRendering
                && this.quickcraft$currentSlotOverlay != null
                && this.quickcraft$currentSlotOverlay.status() == SlotMismatchStatus.MISSING) {
            return this.quickcraft$currentSlotOverlay.expectedStack();
        }

        return slot.getStack();
    }

    @Inject(method = "drawSlot", at = @At("RETURN"))
    private void quickcraft$drawContainerVerifierMissingGhost(DrawContext context, Slot slot, CallbackInfo ci) {
        if (!this.quickcraft$ghostSlotRendering) {
            this.quickcraft$currentSlotOverlay = null;
            return;
        }

        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        this.quickcraft$ghostSlotRendering = false;
        QuickLitematicaContainerVerifier.endHandledScreenGhostRender(
                context,
                accessor.quickcraft$getGuiLeft(),
                accessor.quickcraft$getGuiTop(),
                QuickLitematicaVerifierPalette.ghostItemAlpha()
        );
        quickcraft$drawSlotOutline(context, slot, this.quickcraft$ghostSlotBorderColor);
        this.quickcraft$currentSlotOverlay = null;
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
