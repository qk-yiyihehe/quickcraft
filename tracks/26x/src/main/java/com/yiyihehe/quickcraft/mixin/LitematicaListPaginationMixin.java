package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaListPagination;
import fi.dy.masa.malilib.gui.GuiScrollBar;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = WidgetListBase.class, remap = false)
public abstract class LitematicaListPaginationMixin<T> implements QuickLitematicaListPagination.PagedList {
    @Shadow @Final protected List<T> listContents;
    @Shadow @Final protected GuiScrollBar scrollBar;
    @Shadow protected int browserHeight;
    @Shadow protected int browserPaddingY;
    @Shadow protected int browserEntriesOffsetY;
    @Shadow protected abstract int getBrowserEntryHeightFor(T entry);

    @Unique private QuickLitematicaListPagination quickcraft$pagination;

    @Override
    public void quickcraft$setPagination(QuickLitematicaListPagination pagination) {
        this.quickcraft$pagination = pagination;
    }

    @Unique
    private boolean quickcraft$isPaged() {
        return this.quickcraft$pagination != null && QuickLitematicaListPagination.enabled();
    }

    @Inject(method = "reCreateListEntryWidgets", at = @At("HEAD"))
    private void quickcraft$alignPage(CallbackInfo ci) {
        if (!this.quickcraft$isPaged()) { return; }
        int available = this.browserHeight - this.browserPaddingY - this.browserEntriesOffsetY
                - this.getBrowserEntryHeightFor(null);
        int[] heights = this.listContents.stream().mapToInt(this::getBrowserEntryHeightFor).toArray();
        int first = this.quickcraft$pagination.prepare(this.scrollBar.getValue(), available, heights);
        this.scrollBar.setMaxValue(this.quickcraft$pagination.lastStart());
        this.scrollBar.setValue(first);
    }

    // 原生最大值会把不足一页的末页拉回上一页；分页必须允许停在末页首条。
    @ModifyArg(method = "reCreateListEntryWidgets", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/malilib/gui/GuiScrollBar;setMaxValue(I)V"), index = 0)
    private int quickcraft$pageScrollLimit(int original) {
        return this.quickcraft$isPaged() ? this.quickcraft$pagination.lastStart() : original;
    }

    @Inject(method = "offsetSelectionOrScrollbar", at = @At("HEAD"), cancellable = true)
    private void quickcraft$scrollPages(int amount, boolean changeSelection, CallbackInfo ci) {
        if (this.quickcraft$isPaged() && !changeSelection) {
            this.quickcraft$pagination.move(Integer.signum(amount));
            ci.cancel();
        }
    }
}
