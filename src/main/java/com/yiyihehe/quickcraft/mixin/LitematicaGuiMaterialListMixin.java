package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 在 Litematica 文件版和放置版材料表完成原生布局后追加容器页面入口。
 * TAIL 注入依赖原生按钮坐标来避让控件；目标布局变化时只会影响按钮位置，不改变材料表数据。
 */
@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class LitematicaGuiMaterialListMixin {
    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void quickcraft$addContainerNavigationButtons(CallbackInfo ci) {
        GuiMaterialList gui = (GuiMaterialList) (Object) this;
        Object source = gui.getMaterialList();

        if (!QuickLitematicaContainerMaterials.shouldShowButton()
                || source instanceof QuickLitematicaContainerMaterials.ContainerMaterialRequestSource
                || !(source instanceof MaterialListPlacement || source instanceof MaterialListSchematic)) {
            return;
        }

        String materialLabel = StringUtils.translate(QuickLitematicaContainerMaterials.BUTTON_KEY);
        String detailsLabel = StringUtils.translate("quickcraft.litematica.button.container_material_details");
        int gap = 1;
        int materialWidth = gui.getStringWidth(materialLabel) + 10;
        int detailsWidth = gui.getStringWidth(detailsLabel) + 10;
        int multiplierWidth = gui.getStringWidth(
                StringUtils.translate("litematica.gui.label.material_list.multiplier")
        );
        int topRowLimit = gui.getScreenWidth() - multiplierWidth - 60;
        List<ButtonBase> buttons = ((GuiBaseAccessor) gui).quickcraft$getButtons();
        int x = 12;

        for (ButtonBase button : buttons) {
            if (button.getY() == 24) {
                x = Math.max(x, button.getX() + button.getWidth() + gap);
            }
        }

        int y = 24;
        if (x + materialWidth + gap + detailsWidth > topRowLimit) {
            x = 12;
            y = Math.max(24, gui.getScreenHeight() - 58);
        }

        ButtonGeneric materialButton = new ButtonGeneric(x, y, materialWidth, 20, materialLabel);
        materialButton.setHoverStrings(StringUtils.translate(QuickLitematicaContainerMaterials.BUTTON_HOVER_KEY));
        gui.addButton(materialButton, (button, mouseButton) -> this.quickcraft$openContainerScreen(gui, false));
        x += materialWidth + gap;

        ButtonGeneric detailsButton = new ButtonGeneric(x, y, detailsWidth, 20, detailsLabel);
        gui.addButton(detailsButton, (button, mouseButton) -> this.quickcraft$openContainerScreen(gui, true));
    }

    private void quickcraft$openContainerScreen(GuiMaterialList gui, boolean details) {
        if (gui.getMaterialList() instanceof MaterialListPlacement
                && gui.getMaterialList() instanceof LitematicaMaterialListPlacementAccessor accessor) {
            if (details) {
                QuickLitematicaContainerMaterials.openDetailsForPlacement(accessor.quickcraft$getPlacement(), gui);
            } else {
                QuickLitematicaContainerMaterials.openForPlacement(accessor.quickcraft$getPlacement(), gui);
            }
        } else if (gui.getMaterialList() instanceof MaterialListSchematic
                && gui.getMaterialList() instanceof LitematicaMaterialListSchematicAccessor accessor) {
            if (details) {
                QuickLitematicaContainerMaterials.openDetailsForSchematic(
                        accessor.quickcraft$getSchematic(),
                        accessor.quickcraft$getRegions(),
                        gui
                );
            } else {
                QuickLitematicaContainerMaterials.openForSchematic(
                        accessor.quickcraft$getSchematic(),
                        accessor.quickcraft$getRegions(),
                        gui
                );
            }
        }
    }
}
