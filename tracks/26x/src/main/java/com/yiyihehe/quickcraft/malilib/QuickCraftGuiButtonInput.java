package com.yiyihehe.quickcraft.malilib;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import fi.dy.masa.malilib.gui.GuiBase;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

final class QuickCraftGuiButtonInput {
    private QuickCraftGuiButtonInput() {}

    static void initialize() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof GuiBase gui) {
                var editor = new QuickCraftMovableButton.Editor(gui);
                ScreenMouseEvents.allowMouseClick(screen).register(
                        (shown, event) -> !editor.click(
                                event.x(),
                                event.y(),
                                event.button(),
                                client.hasControlDown(),
                                button -> button.onMouseClicked(event, false)
                        )
                );
                ScreenMouseEvents.allowMouseRelease(screen).register(
                        (shown, event) -> !editor.release(event.x(), event.y(), event.button())
                );
                ScreenEvents.beforeExtract(screen).register((shown, graphics, x, y, delta) -> {
                    if (editor.isDragging()) {
                        editor.drag(x, y, QuickCraftKeyBindings.isLeftMouseButtonDown(client));
                    }
                });
                ScreenEvents.remove(screen).register(removed -> editor.cancel());
            }
        });
    }
}
