package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;

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
        return QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled()
                ? QuickCraftWorkbenchShulkerCraft.shouldSuppressRecipeGhostSlots()
                : QuickCraftWorkbench.shouldSuppressRecipeGhostSlots();
    }
}
