package com.yiyihehe.quickcraft.malilib;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import fi.dy.masa.malilib.gui.GuiBase;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screen.Screen;

final class QuickCraftGuiButtonInput {
    private QuickCraftGuiButtonInput() {}

    static void initialize() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof GuiBase gui) {
                var editor = new QuickCraftMovableButton.Editor(gui);
                ScreenMouseEvents.allowMouseClick(screen).register(
                        (shown, x, y, mouse) -> !editor.click(
                                x,
                                y,
                                mouse,
                                Screen.hasControlDown(),
                                button -> button.onMouseClicked((int) x, (int) y, mouse)
                        )
                );
                ScreenMouseEvents.allowMouseRelease(screen).register(
                        (shown, x, y, button) -> !editor.release(x, y, button)
                );
                ScreenEvents.beforeRender(screen).register((shown, graphics, x, y, delta) -> {
                    if (editor.isDragging()) {
                        editor.drag(x, y, QuickCraftKeyBindings.isLeftMouseButtonDown(client));
                    }
                });
                ScreenEvents.remove(screen).register(removed -> editor.cancel());
            }
        });
    }
}
