package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** 将容器内的物品取回玩家物品栏的图标按钮。 */
public final class QuickRetrieveButton extends QuickCompactIconButton {
    private static final Identifier ICON = Identifier.of("quickcraft", "textures/gui/quick_retrieve.png");

    public QuickRetrieveButton(
            int x,
            int y,
            PressAction onPress,
            PositionKey positionKey,
            net.minecraft.text.Text tooltip
    ) {
        super(x, y, tooltip, onPress, positionKey);
    }

    @Override
    protected void drawIcon(DrawContext context, int x, int y, boolean hovered) {
        QuickButtonAccess.drawButtonIcon(context, ICON, x, y, 10, 10);
    }
}
