package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.gui.GuiMainMenu.ButtonListenerChangeMenu.ButtonType;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Litematica 主菜单的原理图文件夹快捷入口。
 *
 * <p>{@link GuiMainMenu} 属于 Litematica/malilib 界面，不能 remap。按钮宽度按原菜单按钮动态计算，
 * 避免翻译文本或选择模式名称变长后右列按钮宽度不一致。</p>
 */
@Mixin(value = GuiMainMenu.class, remap = false)
public abstract class LitematicaGuiMainMenuMixin extends GuiBase {
    private static final String OPEN_SCHEMATIC_FOLDER_KEY = "quickcraft.litematica.button.open_schematic_folder";
    // 74 是 Litematica 1.21-1.21.1 主菜单右列第一行按钮的 y 坐标。
    private static final int RIGHT_COLUMN_Y = 74;

    protected LitematicaGuiMainMenuMixin() {
        super();
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void quickcraft$addOpenSchematicFolderButton(CallbackInfo ci) {
        String label = StringUtils.translate(OPEN_SCHEMATIC_FOLDER_KEY);
        int menuButtonWidth = this.quickcraft$getMenuButtonWidth();
        int x = 12 + menuButtonWidth + 20;

        ButtonGeneric button = new ButtonGeneric(x, RIGHT_COLUMN_Y, menuButtonWidth, 20, label);
        this.addButton(button, (clickedButton, mouseButton) ->
                Util.getOperatingSystem().open(DataManager.getSchematicsBaseDirectory()));
    }

    private int quickcraft$getMenuButtonWidth() {
        int width = this.getStringWidth(StringUtils.translate(OPEN_SCHEMATIC_FOLDER_KEY)) + 10;

        for (ButtonType type : ButtonType.values()) {
            width = Math.max(width, this.getStringWidth(type.getDisplayName()) + 30);
        }

        for (SelectionMode mode : SelectionMode.values()) {
            String label = StringUtils.translate("litematica.gui.button.area_selection_mode", mode.getDisplayName());
            width = Math.max(width, this.getStringWidth(label) + 10);
        }

        return width;
    }
}
