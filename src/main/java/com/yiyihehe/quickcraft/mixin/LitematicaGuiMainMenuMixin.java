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
 * 在 Litematica 主菜单追加打开原理图文件夹的快捷入口。
 */
@Mixin(value = GuiMainMenu.class, remap = false)
public abstract class LitematicaGuiMainMenuMixin extends GuiBase {
    private static final String OPEN_SCHEMATIC_FOLDER_KEY = "quickcraft.litematica.button.open_schematic_folder";
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
                Util.getPlatform().openPath(DataManager.getSchematicsBaseDirectory()));
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
