package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Litematica 加载原理图页面追加“容器材料列表”入口按钮。
 * 点击后把当前选中的原理图交给 QuickLitematicaContainerMaterials 打开。
 */
@Mixin(value = GuiSchematicLoad.class, remap = false)
public abstract class LitematicaSchematicLoadMixin extends GuiSchematicBrowserBase {
    protected LitematicaSchematicLoadMixin() {
        super(12, 24);
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void quickcraft$addContainerMaterialButtonOnInit(CallbackInfo ci) {
        this.quickcraft$addContainerMaterialButton();
    }

    /**
     * 某些 Litematica 版本选择文件时只重建按钮，不再调用 initGui；0.28.3 没有这个回调。
     * 可选注入在回调存在时恢复入口，在 0.28.3 上则由 initGui 注入负责。
     */
    @Inject(
            method = "onSelectionChange(Lfi/dy/masa/malilib/gui/widgets/WidgetFileBrowserBase$DirectoryEntry;)V",
            at = @At("TAIL"),
            remap = false,
            require = 0
    )
    private void quickcraft$addContainerMaterialButtonAfterSelection(DirectoryEntry entry, CallbackInfo ci) {
        this.quickcraft$addContainerMaterialButton();
    }

    @Unique
    private void quickcraft$addContainerMaterialButton() {
        if (!QuickLitematicaContainerMaterials.shouldShowButton()) {
            return;
        }

        GuiSchematicLoad gui = (GuiSchematicLoad) (Object) this;
        String label = StringUtils.translate(QuickLitematicaContainerMaterials.BUTTON_KEY);
        int buttonWidth = gui.getStringWidth(label) + 10;
        QuickLitematicaContainerMaterials.ButtonPlacement placement =
                QuickLitematicaContainerMaterials.getButtonPlacement(gui, buttonWidth);
        ButtonGeneric button = new ButtonGeneric(placement.x(), placement.y(), buttonWidth, 20, label);
        button.setHoverStrings(StringUtils.translate(QuickLitematicaContainerMaterials.BUTTON_HOVER_KEY));

        gui.addButton(button, (clickedButton, mouseButton) -> {
            WidgetSchematicBrowser listWidget = this.getListWidget();
            QuickLitematicaContainerMaterials.openForEntry(
                    gui,
                    listWidget != null ? listWidget.getLastSelectedEntry() : null
            );
        });
    }
}
