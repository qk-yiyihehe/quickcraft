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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 打开容器时的校验槽位覆盖层。
 *
 * <p>{@code drawSlot} 入口先画底色和边框，让原版物品仍能盖在背景上；
 * 返回点再画缺失物品虚影，确保空槽能显示预期物品。两个注入点的顺序不能合并，
 * 否则真实物品、虚影和高亮遮罩会互相压错层级。</p>
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

    @Inject(method = "drawSlot", at = @At("RETURN"))
    private void quickcraft$drawContainerVerifierMissingGhost(DrawContext context, Slot slot, CallbackInfo ci) {
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

        MinecraftClient client = MinecraftClient.getInstance();
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickLitematicaContainerVerifier.drawGhostItem(
                context,
                client,
                overlay.expectedStack(),
                slot.x,
                slot.y,
                accessor.quickcraft$getGuiLeft(),
                accessor.quickcraft$getGuiTop(),
                QuickLitematicaVerifierPalette.ghostItemAlpha()
        );
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
