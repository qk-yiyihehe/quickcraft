package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaVerifierPalette;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatchStatus;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给容器校验中的槽位绘制底色、边框和缺失物品虚影。
 * 用于在打开容器时直观看到少了什么、错了什么。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class LitematicaHandledScreenSlotOverlayMixin<T extends AbstractContainerMenu> {
    private SlotOverlay quickcraft$currentSlotOverlay;
    private boolean quickcraft$ghostSlotRendering;
    private int quickcraft$ghostSlotBorderColor;

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void quickcraft$drawContainerVerifierSlotBackground(GuiGraphicsExtractor context, Slot slot, int x, int y, CallbackInfo ci) {
        this.quickcraft$currentSlotOverlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(
                (AbstractContainerScreen<?>) (Object) this,
                slot
        );
        SlotOverlay overlay = this.quickcraft$currentSlotOverlay;

        if (overlay == null) {
            return;
        }

        context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, overlay.fillColor());
        quickcraft$drawSlotOutline(context, slot, overlay.borderColor());

        if (overlay.status() != SlotMismatchStatus.MISSING
                || !slot.getItem().isEmpty()
                || overlay.expectedStack().isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (QuickLitematicaContainerVerifier.beginHandledScreenGhostRender(context, client)) {
            this.quickcraft$ghostSlotBorderColor = overlay.borderColor();
            this.quickcraft$ghostSlotRendering = true;
        }
    }

    @Redirect(
            method = "extractSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;",
                    ordinal = 0
            )
    )
    private net.minecraft.world.item.ItemStack quickcraft$replaceRenderedSlotStack(Slot slot) {
        if (this.quickcraft$ghostSlotRendering
                && this.quickcraft$currentSlotOverlay != null
                && this.quickcraft$currentSlotOverlay.status() == SlotMismatchStatus.MISSING) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }

        return slot.getItem();
    }

    @Inject(method = "extractSlot", at = @At("RETURN"))
    private void quickcraft$drawContainerVerifierMissingGhost(GuiGraphicsExtractor context, Slot slot, int x, int y, CallbackInfo ci) {
        if (!this.quickcraft$ghostSlotRendering) {
            this.quickcraft$currentSlotOverlay = null;
            return;
        }

        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        this.quickcraft$ghostSlotRendering = false;
        QuickLitematicaContainerVerifier.drawGhostItem(
                context,
                Minecraft.getInstance(),
                this.quickcraft$currentSlotOverlay.expectedStack(),
                slot.x,
                slot.y,
                accessor.quickcraft$getGuiLeft(),
                accessor.quickcraft$getGuiTop(),
                QuickLitematicaVerifierPalette.ghostItemAlpha()
        );
        quickcraft$drawSlotOutline(context, slot, this.quickcraft$ghostSlotBorderColor);
        this.quickcraft$currentSlotOverlay = null;
    }

    @Inject(method = "extractTooltip", at = @At("RETURN"))
    private void quickcraft$drawContainerVerifierMissingGhostTooltip(
            GuiGraphicsExtractor context,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        Slot slot = this.hoveredSlot;
        if (slot == null
                || !screen.getMenu().getCarried().isEmpty()
                || !slot.getItem().isEmpty()) {
            return;
        }

        SlotOverlay overlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(screen, slot);
        if (overlay == null
                || overlay.status() != SlotMismatchStatus.MISSING
                || overlay.expectedStack().isEmpty()) {
            return;
        }

        context.setTooltipForNextFrame(
                Minecraft.getInstance().font,
                overlay.expectedStack(),
                mouseX,
                mouseY
        );
    }

    private static void quickcraft$drawSlotOutline(GuiGraphicsExtractor context, Slot slot, int color) {
        int x = slot.x;
        int y = slot.y;
        context.fill(x, y, x + 16, y + 1, color);
        context.fill(x, y + 15, x + 16, y + 16, color);
        context.fill(x, y + 1, x + 1, y + 15, color);
        context.fill(x + 15, y + 1, x + 16, y + 15, color);
    }
}
