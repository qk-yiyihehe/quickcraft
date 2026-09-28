package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatchStatus;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotOverlay;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaVerifierPalette;
//#if MC<12108
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.GhostItemDraw;
//#endif
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
//#if MC>=12108
//$$ import org.spongepowered.asm.mixin.injection.Redirect;
//#endif
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//#if MC<12108
import java.util.ArrayList;
import java.util.List;
//#endif

/**
 * 给容器校验中的槽位绘制底色、边框和缺失物品虚影。
 * 低版本在槽位批次结束后合成虚影，1.21.8+ 随单个槽位的绘制生命周期合成。
 */
@Mixin(HandledScreen.class)
public abstract class LitematicaHandledScreenSlotOverlayMixin<T extends ScreenHandler> {
    //#if MC>=12108
    //$$ private SlotOverlay quickcraft$currentSlotOverlay;
    //$$ private boolean quickcraft$ghostSlotRendering;
    //$$ private int quickcraft$ghostSlotBorderColor;
    //#endif

    @Shadow
    protected Slot focusedSlot;

    @Inject(method = "drawSlot", at = @At("HEAD"))
    //#if MC<12111
    private void quickcraft$drawContainerVerifierSlotBackground(DrawContext context, Slot slot, CallbackInfo ci) {
    //#else
    //$$ private void quickcraft$drawContainerVerifierSlotBackground(
    //$$         DrawContext context, Slot slot, int x, int y, CallbackInfo ci) {
    //#endif
        //#if MC<12108
        SlotOverlay overlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(
        //#else
        //$$ this.quickcraft$currentSlotOverlay = QuickLitematicaContainerVerifier.getSlotOverlayForScreen(
        //#endif
                (HandledScreen<?>) (Object) this,
                slot
        );
        //#if MC>=12108
        //$$ SlotOverlay overlay = this.quickcraft$currentSlotOverlay;
        //#endif

        if (overlay == null) {
            return;
        }

        context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, overlay.fillColor());
        quickcraft$drawSlotOutline(context, slot, overlay.borderColor());

        //#if MC>=12108
        //$$ if (overlay.status() != SlotMismatchStatus.MISSING
        //$$         || !slot.getStack().isEmpty()
        //$$         || overlay.expectedStack().isEmpty()) {
        //$$     return;
        //$$ }
        //$$
        //$$ MinecraftClient client = MinecraftClient.getInstance();
        //$$ if (QuickLitematicaContainerVerifier.beginHandledScreenGhostRender(context, client)) {
        //$$     this.quickcraft$ghostSlotBorderColor = overlay.borderColor();
        //$$     this.quickcraft$ghostSlotRendering = true;
        //$$ }
        //#endif
    }

    //#if MC<12108
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    //#if MC<12103
                    target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawForeground(Lnet/minecraft/client/gui/DrawContext;II)V"
                    //#else
                    //$$ target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawSlotHighlightFront(Lnet/minecraft/client/gui/DrawContext;)V"
                    //#endif
            )
    )
    private void quickcraft$drawContainerVerifierGhostBatch(
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
            ghostItems.add(new GhostItemDraw(overlay.expectedStack(), slot.x, slot.y));
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

        //#if MC<12103
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
        //#endif
    }
    //#endif

    //#if MC>=12108
    //$$ @Redirect(
    //$$         method = "drawSlot",
    //$$         at = @At(
    //$$                 value = "INVOKE",
    //$$                 target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;",
    //$$                 ordinal = 0
    //$$         )
    //$$ )
    //$$ private net.minecraft.item.ItemStack quickcraft$replaceRenderedSlotStack(Slot slot) {
    //$$     if (this.quickcraft$ghostSlotRendering
    //$$             && this.quickcraft$currentSlotOverlay != null
    //$$             && this.quickcraft$currentSlotOverlay.status() == SlotMismatchStatus.MISSING) {
    //$$         return net.minecraft.item.ItemStack.EMPTY;
    //$$     }
    //$$
    //$$     return slot.getStack();
    //$$ }
    //$$
    //$$ @Inject(method = "drawSlot", at = @At("RETURN"))
    //#endif
    //#if MC>=12108 && MC<12111
    //$$ private void quickcraft$drawContainerVerifierMissingGhost(DrawContext context, Slot slot, CallbackInfo ci) {
    //#endif
    //#if MC>=12111
    //$$ private void quickcraft$drawContainerVerifierMissingGhost(
    //$$         DrawContext context, Slot slot, int x, int y, CallbackInfo ci) {
    //#endif
    //#if MC>=12108
    //$$     if (!this.quickcraft$ghostSlotRendering) {
    //$$         this.quickcraft$currentSlotOverlay = null;
    //$$         return;
    //$$     }
    //$$
    //$$     HandledScreenAccessor accessor = (HandledScreenAccessor) this;
    //$$     this.quickcraft$ghostSlotRendering = false;
    //$$     QuickLitematicaContainerVerifier.drawGhostItem(
    //$$             context,
    //$$             MinecraftClient.getInstance(),
    //$$             this.quickcraft$currentSlotOverlay.expectedStack(),
    //$$             slot.x,
    //$$             slot.y,
    //$$             accessor.quickcraft$getGuiLeft(),
    //$$             accessor.quickcraft$getGuiTop(),
    //$$             QuickLitematicaVerifierPalette.ghostItemAlpha()
    //$$     );
    //$$     context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, this.quickcraft$currentSlotOverlay.ghostMaskColor());
    //$$     quickcraft$drawSlotOutline(context, slot, this.quickcraft$ghostSlotBorderColor);
    //$$     this.quickcraft$currentSlotOverlay = null;
    //$$ }
    //#endif

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
