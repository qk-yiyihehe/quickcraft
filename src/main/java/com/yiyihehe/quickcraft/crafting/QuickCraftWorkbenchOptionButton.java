package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.render.QuickCompactIconButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.BooleanSupplier;

/** 工作台模式与产物装盒共用的图标切换按钮。 */
public final class QuickCraftWorkbenchOptionButton extends QuickCompactIconButton {
    private final Identifier offIcon;
    private final Identifier onIcon;
    private final BooleanSupplier iconState;
    private final BooleanSupplier selectedState;

    public QuickCraftWorkbenchOptionButton(int x, int y, Text label, PressAction onPress,
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
        context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x, y, 0, 0, 10, 10, 10, 10);
    }
}
