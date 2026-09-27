package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 工作台入口路由。普通合成与潜影盒合成在启动时只选择一条执行链。
 */
public final class QuickCraftWorkbenchRouter {
    private QuickCraftWorkbenchRouter() {
    }

    public static boolean handleCraftButton(boolean rapidCraft) {
        return rapidCraft && QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled()
                ? QuickCraftWorkbenchShulkerCraft.handleWorkbenchCraftButton(rapidCraft)
                : QuickCraftWorkbench.handleWorkbenchCraftButton(rapidCraft);
    }

    public static boolean shouldSuppressRecipeGhostSlots() {
        return QuickCraftWorkbenchShulkerCraft.shouldSuppressRecipeGhostSlots()
                || QuickCraftWorkbench.shouldSuppressRecipeGhostSlots();
    }

    public static void toggleMode() {
        if (shouldSuppressRecipeGhostSlots()) {
            return;
        }
        boolean previousValue = QuickCraftConfigs.Crafting.ENABLE_WORKBENCH_QUICK_SHULKER.getBooleanValue();
        boolean useShulker = !QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled();
        QuickCraftConfigs.Crafting.ENABLE_WORKBENCH_QUICK_SHULKER.setBooleanValue(useShulker);
        if (useShulker && !QuickCraftWorkbenchShulker.isAvailable()) {
            QuickCraftConfigs.Crafting.ENABLE_WORKBENCH_QUICK_SHULKER.setBooleanValue(previousValue);
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.translatable("quickcraft.message.crafting.shulker_unavailable"));
            }
            return;
        }
        QuickCraftConfigs.saveToFile();
    }

    public static void toggleOutputToShulker() {
        if (!QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled()
                || shouldSuppressRecipeGhostSlots()) {
            return;
        }
        QuickCraftConfigs.Crafting.ENABLE_WORKBENCH_QUICK_SHULKER_OUTPUT.toggleBooleanValue();
        QuickCraftConfigs.saveToFile();
    }
}
