package com.yiyihehe.quickcraft.portablestorage;

import org.lwjgl.glfw.GLFW;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import com.yiyihehe.quickcraft.mixin.RecipeBookScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.Text;

final class QuickPortableStorageUiAccess {
    private QuickPortableStorageUiAccess() {}

    static TextFieldWidget searchBox(TextRenderer textRenderer, Text label) {
        return new TextFieldWidget(textRenderer, 0, 0, 0, 14, label);
    }

    static boolean spaceDown() {
        return InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow(), GLFW.GLFW_KEY_SPACE);
    }

    static boolean recipeBookOpen(InventoryScreen screen) {
        return ((RecipeBookScreenAccessor) (Object) screen).quickcraft$getRecipeBook().isOpen();
    }

    static int selectedSlot(PlayerInventory inventory) {
        return inventory.getSelectedSlot();
    }

    static boolean swapHandKey(int key, int scancode, int modifiers) {
        return MinecraftClient.getInstance().options.swapHandsKey.matchesKey(new KeyInput(key, scancode, modifiers));
    }

    static void searchKey(TextFieldWidget search, int key, int scancode, int modifiers) {
        search.keyPressed(new KeyInput(key, scancode, modifiers));
    }

    static void cancelQuickCraft(HandledScreen<?> screen) {
        ((HandledScreenAccessor) screen).quickcraft$setPortableStorageQuickCrafting(false);
        screen.setDragging(false);
    }
}
