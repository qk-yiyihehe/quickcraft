package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Litematica 加载原理图页面的容器材料入口。
 *
 * <p>按钮只负责把当前选中的浏览器条目交给 {@link QuickLitematicaContainerMaterials}。
 * 位置计算留在业务类里，避免这里同时承担按钮布局和材料列表解析。注入点失效时，
 * 玩家只能少一个入口，不会影响 Litematica 原版加载流程。</p>
 */
@Mixin(value = GuiSchematicLoad.class, remap = false)
public abstract class LitematicaSchematicLoadMixin extends GuiSchematicBrowserBase {
    protected LitematicaSchematicLoadMixin() {
        super(12, 24);
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void quickcraft$addContainerMaterialButton(CallbackInfo ci) {
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
