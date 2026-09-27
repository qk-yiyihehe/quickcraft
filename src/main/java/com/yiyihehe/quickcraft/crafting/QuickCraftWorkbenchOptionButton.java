package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.render.QuickCompactIconButton;
import net.minecraft.client.gui.DrawContext;
//#if MC>=12108
//$$ import net.minecraft.client.gl.RenderPipelines;
//#elseif MC>=12103
//$$ import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.BooleanSupplier;

/** 工作台模式与产物装盒共用的图标切换按钮。 */
public final class QuickCraftWorkbenchOptionButton extends QuickCompactIconButton {
    private final Identifier offIcon;
    private final Identifier onIcon;
    private final BooleanSupplier iconState;
    private final BooleanSupplier selectedState;

    //#if MC>=12111
    //$$ public QuickCraftWorkbenchOptionButton(int x, int y, net.minecraft.text.Text label, PressAction onPress,
    //#else
    public QuickCraftWorkbenchOptionButton(int x, int y, Text label, PressAction onPress,
    //#endif
                                           PositionKey positionKey, Identifier offIcon, Identifier onIcon,
                                           BooleanSupplier iconState, BooleanSupplier selectedState) {
        super(x, y, label, onPress, positionKey);
        this.offIcon = offIcon;
        this.onIcon = onIcon;
        this.iconState = iconState;
        this.selectedState = selectedState;
    }

    @Override
    protected boolean isToggleSelected() {
        return this.selectedState.getAsBoolean();
    }

    @Override
    protected void drawIcon(DrawContext context, int x, int y, boolean hovered) {
        Identifier icon = this.iconState.getAsBoolean() ? this.onIcon : this.offIcon;
        //#if MC>=12108
        //$$ context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x, y, 0, 0, 10, 10, 10, 10);
        //#elseif MC>=12103
        //$$ context.drawTexture(RenderLayer::getGuiTextured, icon, x, y, 0, 0, 10, 10, 10, 10);
        //#else
        context.drawTexture(icon, x, y, 0, 0, 10, 10, 10, 10);
        //#endif
    }
}
