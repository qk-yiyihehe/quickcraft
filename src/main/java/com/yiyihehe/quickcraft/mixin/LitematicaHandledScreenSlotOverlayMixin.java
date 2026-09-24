package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaVerifierPalette;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.GhostItemDraw;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatchStatus;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 给容器校验中的槽位绘制底色、边框和缺失物品虚影。
 * 用于在打开容器时直观看到少了什么、错了什么。
 */
@Mixin(HandledScreen.class)
public abstract class LitematicaHandledScreenSlotOverlayMixin<T extends ScreenHandler> {
    @Shadow
    protected Slot focusedSlot;

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

    /**
     * 1.21 的 {@link HandledScreen#render} 在所有槽位之后调用 drawForeground，随后才绘制光标物品。
     * 在该调用前合成批次，既保留原有层级，也避免虚影覆盖玩家手持的物品。
     */
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawForeground(Lnet/minecraft/client/gui/DrawContext;II)V"
            )
    )
    private void quickcraft$finishContainerVerifierGhostBatch(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        List<GhostItemDraw> ghostItems = null;
        List<SlotOverlay> ghostOverlays = null;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (!slot.isEnabled()) {
                continue;
            }

            SlotOverlay overlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(screen, slot);
            if (overlay == null
                    || overlay.status() != SlotMismatchStatus.MISSING
                    || !slot.getStack().isEmpty()
                    || overlay.expectedStack().isEmpty()) {
                continue;
            }

            if (ghostItems == null) {
                ghostItems = new ArrayList<>();
                ghostOverlays = new ArrayList<>();
            }
            ghostItems.add(new GhostItemDraw(
                    overlay.expectedStack(),
                    slot.x,
                    slot.y
            ));
            ghostOverlays.add(overlay);
        }

        if (ghostItems == null) {
            return;
        }

        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickLitematicaContainerVerifier.drawGhostItems(
                context,
                client,
                ghostItems,
                accessor.quickcraft$getGuiLeft(),
                accessor.quickcraft$getGuiTop(),
                QuickLitematicaVerifierPalette.ghostItemAlpha()
        );

        for (int index = 0; index < ghostItems.size(); index++) {
            GhostItemDraw item = ghostItems.get(index);
            SlotOverlay overlay = ghostOverlays.get(index);
            context.fill(item.x(), item.y(), item.x() + 16, item.y() + 16, overlay.ghostMaskColor());
            quickcraft$drawSlotOutline(context, item.x(), item.y(), overlay.borderColor());
        }

        Slot focused = this.focusedSlot;
        if (focused != null && focused.canBeHighlighted()) {
            SlotOverlay focusedOverlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(screen, focused);
            if (focusedOverlay != null
                    && focusedOverlay.status() == SlotMismatchStatus.MISSING
                    && focused.getStack().isEmpty()
                    && !focusedOverlay.expectedStack().isEmpty()) {
                HandledScreen.drawSlotHighlight(context, focused.x, focused.y, 0);
            }
        }
    }

    @Inject(method = "drawMouseoverTooltip", at = @At("RETURN"))
    private void quickcraft$drawContainerVerifierMissingGhostTooltip(
            DrawContext context,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        Slot slot = this.focusedSlot;
        if (slot == null
                || !screen.getScreenHandler().getCursorStack().isEmpty()
                || !slot.getStack().isEmpty()) {
            return;
        }

        SlotOverlay overlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(screen, slot);
        if (overlay == null
                || overlay.status() != SlotMismatchStatus.MISSING
                || overlay.expectedStack().isEmpty()) {
            return;
        }

        context.drawItemTooltip(
                MinecraftClient.getInstance().textRenderer,
                overlay.expectedStack(),
                mouseX,
                mouseY
        );
    }

    private static void quickcraft$drawSlotOutline(DrawContext context, Slot slot, int color) {
        quickcraft$drawSlotOutline(context, slot.x, slot.y, color);
    }

    @Unique
    private static void quickcraft$drawSlotOutline(DrawContext context, int x, int y, int color) {
        context.fill(x, y, x + 16, y + 1, color);
        context.fill(x, y + 15, x + 16, y + 16, color);
        context.fill(x, y + 1, x + 1, y + 15, color);
        context.fill(x + 15, y + 1, x + 16, y + 15, color);
    }
}
