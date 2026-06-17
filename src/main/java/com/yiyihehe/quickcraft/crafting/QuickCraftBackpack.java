package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * QuickCraftBackpack - 玩家背包快速合成客户端类
 * 
 * 功能概述：
 * - 在玩家背包界面（2×2合成网格）提供单次快速合成功能（按V键触发）
 * - 提供连续喷射合成功能（按Alt+C键触发/停止）
 * 
 * 核心机制：
 * - 采用"同tick直接完成操作"的策略，每个游戏tick内执行多次合成尝试
 * - 通过智能处理输出槽阻塞、丢弃产物/原料来维持高速合成
 * - 使用原料喷射门控防止过度丢弃原料
 * - 使用假进展机制允许有限的"丢原料但未立即成功"操作
 * 
 * 适用场景：
 * - 在玩家背包界面下使用
 * - 适用于需要大量合成的2×2配方物品（如木板、工作台、棍子等）
 */
public class QuickCraftBackpack implements ClientModInitializer {
    private static QuickCraftBackpack INSTANCE;

    // =========================
    // 速度 / 行为硬编码参数
    // =========================

    /** 连续合成时每个tick之间的间隔（tick数） */
    private static final int RAPID_INTERVAL = 1;
    
    /** 丢弃物品后尝试取输出的次数 */
    private static final int OUTPUT_TAKE_ATTEMPTS_AFTER_DROP = 2;
    
    /** 连续失败多少次后自动停止合成 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    
    /** 无进展持续多少tick后自动停止 */
    private static final int MAX_NO_PROGRESS_TICKS = 3;
    
    /** 玩家背包2×2合成网格的输出槽槽位ID（0号槽位） */
    private static final int OUTPUT_SLOT = 0;
    
    /** 玩家背包2×2合成网格的输入槽数量（4个槽位） */
    private static final int CRAFTING_GRID_SIZE = 4;
    
    /** 玩家背包2×2合成网格的宽度 */
    private static final int CRAFTING_GRID_WIDTH = 2;
    
    /** 玩家背包2×2合成网格的高度 */
    private static final int CRAFTING_GRID_HEIGHT = 2;

    // =========================
    // 运行时状态变量
    // =========================
    
    /** 上一帧V键的按下状态（用于检测按键按下瞬间） */
    private boolean lastVDown = false;
    
    /** 上一帧Alt+C组合键的按下状态（用于检测按键按下瞬间） */
    private boolean lastAltCDown = false;
    
    /** 连续合成是否处于激活状态 */
    private boolean rapidCraftingActive = false;
    private boolean rapidCraftStartedByButton = false;
    
    /** 连续合成的tick冷却计数器 */
    private int rapidCooldown = 0;
    
    /** 连续失败计数器（达到阈值则自动停止） */
    private int consecutiveFailures = 0;
    
    /** 当前锁定的合成配方（连续合成使用同一个配方） */
    private RecipeEntry<CraftingRecipe> lockedRecipe = null;
    
    /** 特殊配方无法走配方书补货时，保留用户手摆的合成格快照 */
    private List<ItemStack> lockedCraftingPattern = new ArrayList<>();

    /** 无进展tick计数器（用于检测是否卡住） */
    private int noProgressTicks = 0;
    
    /** 上次进度快照时的产物总数量（用于检测进展） */
    private int lastResultCount = -1;
    
    /** 上次进度快照时的空槽位数量（用于检测进展） */
    private int lastEmptySlots = -1;
    
    /** 
     * 原料喷射门控标志
     * true表示当前输出槽阻塞周期内已经喷过原料，不再重复丢弃原料
     * 只有当输出槽清空或内容变化时才重置为false
     */
    private boolean ingredientDropLocked = false;
    
    /** 上次观察到的输出槽特征值（用于检测输出槽内容是否变化） */
    private int lastObservedOutputSignature = 0;
    
    /** 
     * 假进展计数器
     * 记录"丢原料但未立即成功取输出"的次数
     * 限制次数避免无限循环丢弃原料
     */
    private int fakeProgressTicks = 0;
    
    /** 允许的最大假进展次数 */
    private static final int MAX_FAKE_PROGRESS = 3;

    /**
     * 模组初始化入口方法
     * 在客户端启动时调用，注册tick事件监听器
     */
    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean handleBackpackCraftButton(boolean rapidCraft) {
        if (INSTANCE == null) {
            return false;
        }
        return INSTANCE.handleCraftButton(MinecraftClient.getInstance(), rapidCraft);
    }

    /**
     * 客户端tick事件处理方法（每帧调用）
     * 
     * 主要职责：
     * 1. 验证当前上下文是否有效（是否在玩家背包界面）
     * 2. 更新原料喷射门控状态
     * 3. 处理热键输入
     * 4. 执行连续合成逻辑
     * 
     * @param client Minecraft客户端实例
     */
    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isBackpackQuickCraftEnabled()) {
            resetAll();
            return;
        }

        // 验证上下文：必须在玩家背包界面且玩家存在
        if (!isCraftingContextValid(client)) {
            resetAll();
            return;
        }

        PlayerScreenHandler handler = (PlayerScreenHandler) client.player.currentScreenHandler;

        // 更新原料喷射门控状态（检测输出槽是否变化）
        updateIngredientDropLock(handler);

        // 处理热键输入
        handleHotkeys(client, handler);

        if (rapidCraftingActive && rapidCraftStartedByButton && !isCraftButtonRapidModeHeld(client)) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.backpack.stopped"));
        }

        // 执行连续合成
        if (rapidCraftingActive && lockedRecipe != null) {
            rapidCooldown++;
            if (rapidCooldown >= RAPID_INTERVAL) {
                rapidCooldown = 0;
                processRapidCraftTick(client, handler, lockedRecipe);
            }
        }
    }

    // =========================
    // 主循环逻辑
    // =========================

    /**
     * 处理单个tick的连续合成逻辑
     * 
     * 执行流程：
     * 1. 在一个tick内执行多次合成子循环（CRAFT_LOOPS_PER_TICK次）
     * 2. 每次子循环尝试完成一次"点击配方→取输出"的完整操作
     * 3. 如果子循环失败，触发fallback机制解决输出槽阻塞
     * 4. 统计进展并检测是否需要停止合成
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     * @param recipe 锁定的合成配方
     */
    private void processRapidCraftTick(MinecraftClient client,
                                       PlayerScreenHandler handler,
                                       RecipeEntry<CraftingRecipe> recipe) {
        boolean anyProgress = false;
        int craftLoopsPerTick = QuickCraftConfigs.getCraftLoopsPerTick();

        // 在一个tick内执行多次子循环
        for (int loop = 0; loop < craftLoopsPerTick; loop++) {
            boolean progressed = runOneCraftSubLoop(client, handler, recipe);
            if (progressed) {
                anyProgress = true;
            } else {
                // 子循环失败，尝试解决输出槽阻塞问题
                boolean fallbackSuccess = resolveOutputSlotBlockageStrict(
                    client, 
                    handler, 
                    getRecipeResultStack(client, recipe), 
                    recipe
                );
                if (fallbackSuccess) {
                    anyProgress = true;
                }
            }
        }

        // 根据是否有进展更新状态
        if (anyProgress) {
            consecutiveFailures = 0;
            noProgressTicks = 0;
            refreshProgressSnapshot(client, recipe);
        } else {
            consecutiveFailures++;
            detectNoProgressAndMaybeStop(client, recipe);

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                stopRapidCraft(client, Text.translatable("quickcraft.message.backpack.no_progress"));
            }
        }
    }

    /**
     * 单个"喷射子循环" - 核心合成逻辑
     * 
     * 执行策略（按优先级排序）：
     * Step 1: 输出槽有物品 → 优先尝试取出（Shift+点击）
     * Step 2: 取出失败 → 丢弃背包中的产物腾空间 → 立即重试取出
     * Step 3: 没有产物可丢 → 丢弃原料腾空间（受假进展限制）
     * Step 4: 输出槽为空 → 点击配方填充材料 → 立即尝试取出
     * 
     * 假进展机制：
     * - "丢原料但未立即成功"算作假进展
     * - 允许最多MAX_FAKE_PROGRESS次假进展
     * - 防止因网络延迟等原因过早停止合成
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     * @param recipe 合成配方
     * @return true表示本次子循环有实质性进展
     */
    private boolean runOneCraftSubLoop(MinecraftClient client,
                                       PlayerScreenHandler handler,
                                       RecipeEntry<CraftingRecipe> recipe) {
        
        if (client.player == null || client.interactionManager == null || client.world == null) {
            return false;
        }

        // 获取配方产物模板
        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (resultTemplate.isEmpty() && handler.getSlot(OUTPUT_SLOT).hasStack()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        }

        // ========================================
        // Step 1: 输出槽有物品 → 优先取出
        // ========================================
        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            if (shouldManualRestock(recipe) && isManualPatternMissingItems(handler)) {
                if (restockCraftingGridFromPattern(client, handler)) {
                    tryTakeOutputForRecipe(client, handler, recipe);
                    fakeProgressTicks = 0;
                    return true;
                }
                return false;
            }

            if (tryTakeOutputForRecipe(client, handler, recipe)) {
                // 成功取出，重置假进展计数
                fakeProgressTicks = 0;
                return true;
            }

            // ========================================
            // Step 2: 取出失败 → 丢产物腾空间 → 重试
            // ========================================
            if (!resultTemplate.isEmpty()) {
                int droppedOutput = dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate, 1);
                if (droppedOutput > 0) {
                    // 丢弃后立即尝试取出（同tick内完成）
                    if (tryTakeOutputForRecipe(client, handler, recipe)) {
                        fakeProgressTicks = 0;
                        return true;
                    }
                }
            }

            // ========================================
            // Step 3: 无产物可丢 → 丢原料（受假进展限制）
            // ========================================
            if (!hasMatchingItemInInventory(client.player.getInventory(), resultTemplate)) {
                // 检查假进展限制
                if (fakeProgressTicks >= MAX_FAKE_PROGRESS) {
                    return false;  // 超过限制，停止假进展
                }

                // 丢弃1组原料
                int droppedIng = dropIngredientBurst(client, handler, recipe, 1);
                if (droppedIng > 0) {
                    // 丢弃后立即尝试取出
                    if (tryTakeOutputForRecipe(client, handler, recipe)) {
                        fakeProgressTicks = 0;
                        return true;
                    } else {
                        // 标记为假进展（丢弃了原料但没立即成功）
                        fakeProgressTicks++;
                        return true;  // 限次数内仍算作进展
                    }
                }
            }

            return false;  // 所有方法都失败
        }

        // ========================================
        // Step 4: 输出槽为空 → 点击配方 → 立即取出
        // ========================================
        if (restockCraftingGrid(client, handler, recipe)) {
            // 立即尝试取出（同tick内完成）
            tryTakeOutputForRecipe(client, handler, recipe);
            fakeProgressTicks = 0;
            return true;
        }

        return false;
    }

    /**
     * 检查背包中是否有匹配指定模板的物品
     * 
     * @param inventory 玩家背包
     * @param template 物品模板
     * @return true表示背包中至少有一个匹配物品
     */
    private boolean hasMatchingItemInInventory(PlayerInventory inventory, ItemStack template) {
        if (template.isEmpty()) {
            return false;
        }

        for (ItemStack stack : inventory.main) {
            if (stack.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解决输出槽阻塞问题（严格模式）
     * 
     * 当输出槽有物品但无法取出时调用（通常因为背包满）
     * 
     * 处理策略：
     * 1. 先尝试直接取出（可能网络延迟导致之前失败）
     * 2. 优先丢弃背包中的产物（Ctrl+Q整组丢弃）然后重试取出
     * 3. 如果背包没有产物，丢弃原料腾空间（受门控限制，每个阻塞周期只丢一次）
     * 
     * 原料喷射门控的作用：
     * - 防止在同一个输出槽阻塞周期内反复丢弃原料
     * - 只有当输出槽清空或内容变化时才允许再次丢弃原料
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     * @param resultTemplate 产物模板
     * @param recipe 合成配方
     * @return true表示成功解决了阻塞
     */
    private boolean resolveOutputSlotBlockageStrict(MinecraftClient client,
                                                    PlayerScreenHandler handler,
                                                    ItemStack resultTemplate,
                                                    RecipeEntry<CraftingRecipe> recipe) {

        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        // 1. 先尝试直接取出
        if (tryTakeOutputForRecipe(client, handler, recipe)) {
            ingredientDropLocked = false;
            return true;
        }

        // 输出槽已空，阻塞解除
        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            ingredientDropLocked = false;
            return true;
        }

        // 2. 优先丢产物（不消耗原料）
        if (dropOutputsBeforeTakingAndTryTake(client, handler, resultTemplate, OUTPUT_TAKE_ATTEMPTS_AFTER_DROP, recipe)) {
            if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                ingredientDropLocked = false;
            }
            return true;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            ingredientDropLocked = false;
            return true;
        }

        // 3. 没有产物时，考虑丢原料（受门控限制）
        if (!ingredientDropLocked) {
            // 直接丢1组原料
            int droppedIng = dropIngredientBurst(client, handler, recipe, 1);
            if (droppedIng > 0) {
                ingredientDropLocked = true;  // 标记已丢弃，本周期不再丢弃

                // 丢弃后尝试取出
                boolean tookOutput = dropOutputsBeforeTakingAndTryTake(
                        client,
                        handler,
                        resultTemplate,
                        OUTPUT_TAKE_ATTEMPTS_AFTER_DROP,
                        recipe
                );
                if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                    ingredientDropLocked = false;
                }
                return tookOutput || droppedIng > 0;
            }
        }

        // 4. 已经喷过原料但输出槽还没清掉，等待后续循环处理
        return false;
    }


    // =========================
    // 单次合成（V键触发）
    // =========================

    /**
     * 处理单次合成请求（V键触发）
     * 
     * 执行一次完整的合成子循环，适用于手动控制合成节奏的场景
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     */
    private void handleSingleCraft(MinecraftClient client, PlayerScreenHandler handler) {
        // 获取当前配方
        RecipeEntry<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null) {
            lockCurrentRecipe(currentRecipe, handler);
        }

        if (lockedRecipe == null) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_recipe"));
            return;
        }

        // 执行一次合成子循环
        boolean success = runOneCraftSubLoop(client, handler, lockedRecipe);
        if (!success) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.single_no_progress"));
        }
    }

    private boolean handleCraftButton(MinecraftClient client, boolean rapidCraft) {
        if (!isCraftingContextValid(client)) {
            return false;
        }

        PlayerScreenHandler handler = (PlayerScreenHandler) client.player.currentScreenHandler;
        if (rapidCraft) {
            return startRapidCraft(client, handler, true);
        }

        handleSingleCraft(client, handler);
        return true;
    }

    // =========================
    // 底层操作函数
    // =========================

    /**
     * 点击配方按钮
     * 将配方所需材料从背包移动到2×2合成网格
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     * @param recipe 要应用的配方
     * @return true表示点击成功
     */
    private boolean clickRecipe(MinecraftClient client,
                                PlayerScreenHandler handler,
                                RecipeEntry<CraftingRecipe> recipe) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        try {
            client.interactionManager.clickRecipe(handler.syncId, recipe, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 补货分支入口：普通配方走配方书，特殊配方按用户手摆快照补格子。
     */
    private boolean restockCraftingGrid(MinecraftClient client,
                                        PlayerScreenHandler handler,
                                        RecipeEntry<CraftingRecipe> recipe) {
        if (!shouldManualRestock(recipe)) {
            return clickRecipe(client, handler, recipe);
        }

        return restockCraftingGridFromPattern(client, handler);
    }

    /**
     * 特殊配方不会进入配方书，只能按锁定时的格子形状手动补一格材料堆。
     */
    private boolean restockCraftingGridFromPattern(MinecraftClient client,
                                                   PlayerScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        if (!handler.getCursorStack().isEmpty() || lockedCraftingPattern.isEmpty()) {
            return false;
        }
        if (!hasItemsForMissingPatternSlots(client.player.getInventory(), handler)) {
            return false;
        }

        boolean hasPattern = false;
        boolean changed = false;
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            hasPattern = true;
            int gridSlot = 1 + i;
            ItemStack existing = handler.getSlot(gridSlot).getStack();
            if (!existing.isEmpty()) {
                if (!ItemStack.areItemsAndComponentsEqual(existing, template)) {
                    return false;
                }
                continue;
            }

            int sourceSlot = findMatchingPlayerInventoryHandlerSlot(
                    client.player.getInventory(),
                    handler,
                    template
            );
            int sameMissingSlots = 1 + countMissingPatternSlots(handler, i + 1, template);
            if (sourceSlot == -1 || !moveIngredientStackToGridSlot(client, handler, sourceSlot, gridSlot, template, sameMissingSlots)) {
                return false;
            }
            changed = true;
        }

        return hasPattern && (changed || handler.getSlot(OUTPUT_SLOT).hasStack());
    }

    private boolean moveIngredientStackToGridSlot(MinecraftClient client,
                                                  PlayerScreenHandler handler,
                                                  int sourceSlot,
                                                  int gridSlot,
                                                  ItemStack template,
                                                  int sameMissingSlots) {
        int sourceCount = handler.getSlot(sourceSlot).getStack().getCount();
        if (sourceCount >= template.getMaxCount() && canFillSamePatternSlotsWithFullStacks(handler, template)) {
            return moveFullStackToGridSlot(client, handler, sourceSlot, gridSlot, template);
        }

        if (sourceCount <= sameMissingSlots
                || (sameMissingSlots > 1 && sourceCount < 2 * (sameMissingSlots - 1))) {
            return moveOneItemToGridSlot(client, handler, sourceSlot, gridSlot, template);
        }

        // 同款材料还要分到后面的格子时，右键拿半组并确保后续格子仍有料可补。
        int pickupButton = sameMissingSlots > 1 ? 1 : 0;
        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlot,
                pickupButton,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                gridSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        return handler.getCursorStack().isEmpty()
                && handler.getSlot(gridSlot).hasStack()
                && ItemStack.areItemsAndComponentsEqual(handler.getSlot(gridSlot).getStack(), template);
    }

    private boolean canFillSamePatternSlotsWithFullStacks(PlayerScreenHandler handler,
                                                          ItemStack template) {
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getStack();
            if (existing.isEmpty()) {
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, template)
                    || existing.getCount() < template.getMaxCount()) {
                return false;
            }
        }
        return true;
    }

    private boolean moveFullStackToGridSlot(MinecraftClient client,
                                            PlayerScreenHandler handler,
                                            int sourceSlot,
                                            int gridSlot,
                                            ItemStack template) {
        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                gridSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        return handler.getCursorStack().isEmpty()
                && handler.getSlot(gridSlot).hasStack()
                && ItemStack.areItemsAndComponentsEqual(handler.getSlot(gridSlot).getStack(), template);
    }

    private boolean moveOneItemToGridSlot(MinecraftClient client,
                                          PlayerScreenHandler handler,
                                          int sourceSlot,
                                          int gridSlot,
                                          ItemStack template) {
        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                gridSlot,
                1,
                SlotActionType.PICKUP,
                client.player
        );

        boolean placed = handler.getSlot(gridSlot).hasStack()
                && ItemStack.areItemsAndComponentsEqual(handler.getSlot(gridSlot).getStack(), template);
        boolean cursorReturned = returnCursorStack(client, handler, sourceSlot);
        return placed && cursorReturned;
    }

    private boolean returnCursorStack(MinecraftClient client,
                                      PlayerScreenHandler handler,
                                      int preferredSlot) {
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }

        if (canAcceptStack(handler.getSlot(preferredSlot).getStack(), handler.getCursorStack())) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    preferredSlot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
        }
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }

        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(client.player.getInventory(), handler, handler.getCursorStack());
        if (returnSlot == -1) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                returnSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        return handler.getCursorStack().isEmpty();
    }

    private boolean canAcceptStack(ItemStack targetStack, ItemStack cursorStack) {
        return targetStack.isEmpty()
                || (ItemStack.areItemsAndComponentsEqual(targetStack, cursorStack)
                && targetStack.getCount() + cursorStack.getCount() <= targetStack.getMaxCount());
    }

    private int countMissingPatternSlots(PlayerScreenHandler handler,
                                         int startPatternIndex,
                                         ItemStack template) {
        int total = 0;
        for (int i = startPatternIndex; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getStack();
            if (existing.isEmpty()) {
                total++;
            }
        }
        return total;
    }

    private boolean hasItemsForMissingPatternSlots(PlayerInventory inventory,
                                                   PlayerScreenHandler handler) {
        List<ItemStack> availableStacks = new ArrayList<>();
        for (ItemStack stack : inventory.main) {
            if (!stack.isEmpty()) {
                availableStacks.add(stack.copy());
            }
        }

        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getStack();
            if (!existing.isEmpty()) {
                if (!ItemStack.areItemsAndComponentsEqual(existing, template)) {
                    return false;
                }
                continue;
            }

            int availableIndex = findMatchingStackIndex(availableStacks, template);
            if (availableIndex == -1) {
                return false;
            }
            availableStacks.get(availableIndex).decrement(1);
        }

        return true;
    }

    private boolean isManualPatternMissingItems(PlayerScreenHandler handler) {
        if (lockedCraftingPattern.isEmpty()) {
            return false;
        }

        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getStack();
            if (existing.isEmpty()) {
                return true;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, template)) {
                return false;
            }
        }

        return false;
    }

    private int findMatchingStackIndex(List<ItemStack> stacks, ItemStack template) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingPlayerInventoryHandlerSlot(PlayerInventory inventory,
                                                       PlayerScreenHandler handler,
                                                       ItemStack template) {
        int bestSlot = -1;
        int bestCount = -1;
        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, template)) {
                continue;
            }

            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot != -1 && handler.getSlot(handlerSlot).hasStack()) {
                int stackCount = handler.getSlot(handlerSlot).getStack().getCount();
                if (stackCount > bestCount) {
                    bestCount = stackCount;
                    bestSlot = handlerSlot;
                }
            }
        }
        return bestSlot;
    }

    private int findAcceptingPlayerInventoryHandlerSlot(PlayerInventory inventory,
                                                        PlayerScreenHandler handler,
                                                        ItemStack cursorStack) {
        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) {
                continue;
            }

            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (canAcceptStack(stack, cursorStack)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    private boolean shouldManualRestock(RecipeEntry<CraftingRecipe> recipe) {
        try {
            return recipe != null && recipe.value().isIgnoredInRecipeBook();
        } catch (Throwable t) {
            return false;
        }
    }

    private void lockCurrentRecipe(RecipeEntry<CraftingRecipe> recipe,
                                   PlayerScreenHandler handler) {
        lockedRecipe = recipe;
        lockedCraftingPattern = snapshotCraftingGrid(handler);
    }

    private List<ItemStack> snapshotCraftingGrid(PlayerScreenHandler handler) {
        List<ItemStack> pattern = new ArrayList<>();
        for (int i = 1; i <= CRAFTING_GRID_SIZE; i++) {
            ItemStack stack = handler.getSlot(i).getStack().copy();
            if (!stack.isEmpty()) {
                stack.setCount(1);
            }
            pattern.add(stack);
        }
        return pattern;
    }

    /**
     * 尝试快速移动输出槽物品（Shift+点击）
     * 将输出槽的物品移动到背包
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     * @return true表示成功移动了物品
     */
    private boolean tryQuickMoveOutput(MinecraftClient client, PlayerScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getStack().copy();

        // 执行Shift+点击操作
        client.interactionManager.clickSlot(
                handler.syncId,
                OUTPUT_SLOT,
                0,                      // button 0 = 左键
                SlotActionType.QUICK_MOVE,  // QUICK_MOVE = Shift+点击
                client.player
        );

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getStack();

        // 判断是否成功：槽位变空、物品变化、或数量变化
        return after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(before, after)
                || after.getCount() != before.getCount();
    }

    private boolean tryTakeOutputForRecipe(MinecraftClient client,
                                           PlayerScreenHandler handler,
                                           RecipeEntry<CraftingRecipe> recipe) {
        if (shouldManualRestock(recipe) && isManualPatternMissingItems(handler)) {
            return false;
        }
        if (shouldManualRestock(recipe) && hasUnevenManualPatternStacks(handler)) {
            return tryTakeOneOutput(client, handler);
        }
        return tryQuickMoveOutput(client, handler);
    }

    private boolean tryTakeOneOutput(MinecraftClient client, PlayerScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(
                client.player.getInventory(),
                handler,
                before
        );
        if (returnSlot == -1) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                OUTPUT_SLOT,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        boolean pickedOutput = !handler.getCursorStack().isEmpty()
                && ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), before);
        if (pickedOutput) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    returnSlot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
        }

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getStack();
        return handler.getCursorStack().isEmpty()
                && (after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(before, after)
                || after.getCount() != before.getCount());
    }

    private boolean hasUnevenManualPatternStacks(PlayerScreenHandler handler) {
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            int count = getPatternSlotCount(handler, i, template);
            if (count <= 0) {
                continue;
            }

            for (int j = i + 1; j < lockedCraftingPattern.size(); j++) {
                ItemStack otherTemplate = lockedCraftingPattern.get(j);
                if (otherTemplate.isEmpty() || !ItemStack.areItemsAndComponentsEqual(template, otherTemplate)) {
                    continue;
                }
                int otherCount = getPatternSlotCount(handler, j, otherTemplate);
                if (otherCount > 0 && otherCount != count) {
                    return true;
                }
            }
        }
        return false;
    }

    private int getPatternSlotCount(PlayerScreenHandler handler, int patternIndex, ItemStack template) {
        ItemStack stack = handler.getSlot(1 + patternIndex).getStack();
        if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, template)) {
            return 0;
        }
        return stack.getCount();
    }
    
    /**
     * 计算输出槽的"特征值"
     * 用于检测输出槽内容是否发生变化
     * 
     * 特征值基于：
     * - 物品类型
     * - 物品数量
     * - 物品组件（NBT数据）
     * 
     * @param handler 玩家背包屏幕处理器
     * @return 特征值（0表示输出槽为空）
     */
    private int getOutputSignature(PlayerScreenHandler handler) {
        if (handler == null || !handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return 0;
        }

        ItemStack stack = handler.getSlot(OUTPUT_SLOT).getStack();
        int hash = 17;
        hash = 31 * hash + System.identityHashCode(stack.getItem());

        try {
            hash = 31 * hash + stack.getCount();
            hash = 31 * hash + stack.getComponents().hashCode();
        } catch (Throwable ignored) {
        }

        return hash;
    }

    /**
     * 更新原料喷射门控状态
     * 
     * 门控规则：
     * - 输出槽变空 → 重置门控（允许下次喷原料）
     * - 输出槽内容变化（物品类型/数量/组件变化）→ 重置门控
     * - 输出槽内容不变 → 保持门控状态
     * 
     * @param handler 玩家背包屏幕处理器
     */
    private void updateIngredientDropLock(PlayerScreenHandler handler) {
        int currentSignature = getOutputSignature(handler);

        // 输出槽空了 → 阻塞解除
        if (currentSignature == 0) {
            ingredientDropLocked = false;
            lastObservedOutputSignature = 0;
            return;
        }

        // 输出槽内容变化 → 新阻塞周期开始
        if (lastObservedOutputSignature != 0 && lastObservedOutputSignature != currentSignature) {
            ingredientDropLocked = false;
        }

        lastObservedOutputSignature = currentSignature;
    }

    /**
     * 丢弃产物并尝试取出输出槽物品
     * 
     * 流程：
     * 1. 丢弃背包中匹配的产物（整组丢弃）
     * 2. 多次尝试取出输出槽物品
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     * @param resultTemplate 产物模板
     * @param takeAttemptsAfterDrop 丢弃后尝试取出的次数
     * @return true表示有进展（丢弃了物品或成功取出）
     */
    private boolean dropOutputsBeforeTakingAndTryTake(MinecraftClient client,
                                                      PlayerScreenHandler handler,
                                                      ItemStack resultTemplate,
                                                      int takeAttemptsAfterDrop,
                                                      RecipeEntry<CraftingRecipe> recipe) {
        boolean progressed = false;

        // 先丢弃产物
        if (!resultTemplate.isEmpty()) {
            int droppedOutput = dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate, 1);
            if (droppedOutput > 0) {
                progressed = true;
            }
        }

        // 然后多次尝试取出
        for (int i = 0; i < takeAttemptsAfterDrop; i++) {
            if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                break;
            }
            if (!tryTakeOutputForRecipe(client, handler, recipe)) {
                continue;
            }
            progressed = true;
            if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                break;
            }
        }

        return progressed;
    }

    /**
     * 整组丢弃背包中匹配的产物
     * 
     * 使用 Ctrl+Q（整组丢弃）操作
     * 遍历背包主区域，丢弃所有匹配产物模板的物品
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     * @param resultTemplate 产物模板
     * @param burstCount 执行轮数（每轮遍历一次背包）
     * @return 实际丢弃的槽位数
     */
    private int dropMatchingItemsFromInventoryBurst(MinecraftClient client,
                                                    PlayerScreenHandler handler,
                                                    ItemStack resultTemplate,
                                                    int burstCount) {
        if (client.player == null || client.interactionManager == null || resultTemplate.isEmpty()) {
            return 0;
        }

        int droppedSlots = 0;

        for (int round = 0; round < burstCount; round++) {
            boolean anyDroppedInRound = false;
            PlayerInventory inventory = client.player.getInventory();

            for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
                ItemStack stack = inventory.main.get(invIndex);
                if (stack.isEmpty()) continue;
                if (!ItemStack.areItemsAndComponentsEqual(stack, resultTemplate)) continue;

                int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
                if (handlerSlot == -1) continue;
                if (!handler.getSlot(handlerSlot).hasStack()) continue;

                // Button 1 = 整组丢弃（Ctrl+Q）
                client.interactionManager.clickSlot(
                        handler.syncId,
                        handlerSlot,
                        1, 
                        SlotActionType.THROW,
                        client.player
                );
                anyDroppedInRound = true;
                droppedSlots++;
            }

            if (!anyDroppedInRound) {
                break;  // 本轮没有丢弃任何物品，提前结束
            }
        }

        return droppedSlots;
    }

    /**
     * 整组丢弃原料
     * 
     * 选择堆叠数量最大的原料进行丢弃
     * 优先丢弃数量>1的堆叠，保留单个物品
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     * @param recipe 合成配方（用于确定哪些是原料）
     * @param maxDrops 最大丢弃次数
     * @return 实际丢弃的次数
     */
    private int dropIngredientBurst(MinecraftClient client,
                                    PlayerScreenHandler handler,
                                    RecipeEntry<CraftingRecipe> recipe,
                                    int maxDrops) {
        if (client.player == null || client.interactionManager == null || maxDrops <= 0) {
            return 0;
        }

        int dropped = 0;

        for (int i = 0; i < maxDrops; i++) {
            // 找到最适合丢弃的原料槽位
            int invIndex = findBestDroppableIngredientSlot(client.player.getInventory(), recipe);
            if (invIndex == -1) {
                break;
            }

            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) {
                break;
            }

            if (!handler.getSlot(handlerSlot).hasStack()) {
                break;
            }

            // Button 1 = 整组丢弃（Ctrl+Q）
            client.interactionManager.clickSlot(
                    handler.syncId,
                    handlerSlot,
                    1, 
                    SlotActionType.THROW,
                    client.player
            );
            dropped++;
        }

        return dropped;
    }

    /**
     * 找到最适合丢弃的原料槽位
     * 
     * 选择标准：
     * - 必须是配方所需的原料
     * - 堆叠数量 > 1（保留单个物品）
     * - 选择堆叠数量最大的
     * 
     * @param inventory 玩家背包
     * @param recipe 合成配方
     * @return 背包索引，-1表示没有合适的
     */
    private int findBestDroppableIngredientSlot(PlayerInventory inventory,
                                                RecipeEntry<CraftingRecipe> recipe) {
        if (shouldManualRestock(recipe) && !lockedCraftingPattern.isEmpty()) {
            return findBestDroppablePatternIngredientSlot(inventory);
        }

        List<Ingredient> ingredients = recipe.value().getIngredients();

        int bestIndex = -1;
        int bestCount = -1;

        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty()) continue;
            // 只丢弃堆叠较大的（保留单个物品）
            if (stack.getCount() <= 1) continue;

            if (!matchesAnyIngredient(stack, ingredients)) continue;

            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private int findBestDroppablePatternIngredientSlot(PlayerInventory inventory) {
        int bestIndex = -1;
        int bestCount = -1;

        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty()) continue;
            if (stack.getCount() <= 1) continue;
            if (!matchesAnyPatternIngredient(stack)) continue;

            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private boolean matchesAnyPatternIngredient(ItemStack stack) {
        for (ItemStack template : lockedCraftingPattern) {
            if (template.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查物品是否匹配任意一个原料
     * 
     * @param stack 要检查的物品
     * @param ingredients 原料列表
     * @return true表示匹配
     */
    private boolean matchesAnyIngredient(ItemStack stack, List<Ingredient> ingredients) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            if (ingredient.test(stack)) return true;
        }
        return false;
    }

    // =========================
    // 配方 / 结果 / 槽位映射
    // =========================

    /**
     * 获取配方的产物物品
     * 
     * @param client Minecraft客户端实例
     * @param recipe 配方
     * @return 产物物品的副本
     */
    private ItemStack getRecipeResultStack(MinecraftClient client, RecipeEntry<CraftingRecipe> recipe) {
        try {
            DynamicRegistryManager registryManager = client.world.getRegistryManager();
            return recipe.value().getResult(registryManager).copy();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 获取当前2×2合成网格的合成配方
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     * @return 当前配方，如果没有则返回null
     */
    private RecipeEntry<CraftingRecipe> getCurrentCraftingRecipe(MinecraftClient client, PlayerScreenHandler handler) {
        if (client.world == null) {
            return null;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return null;
        }

        return tryFindCurrentRecipe(client.world, handler);
    }

    /**
     * 尝试查找当前2×2合成网格对应的配方
     * 
     * @param world 世界实例
     * @param handler 玩家背包屏幕处理器
     * @return 匹配的配方，如果没有则返回null
     */
    private RecipeEntry<CraftingRecipe> tryFindCurrentRecipe(World world, PlayerScreenHandler handler) {
        try {
            // 获取2×2合成网格的物品（槽位1-4）
            List<ItemStack> inputStacks = new ArrayList<>();
            for (int i = 1; i <= CRAFTING_GRID_SIZE; i++) {
                inputStacks.add(handler.getSlot(i).getStack().copy());
            }

            // 使用2×2的输入尺寸
            CraftingRecipeInput input = CraftingRecipeInput.create(
                CRAFTING_GRID_WIDTH, 
                CRAFTING_GRID_HEIGHT, 
                inputStacks
            );

            RecipeManager recipeManager = world.getRecipeManager();
            Optional<RecipeEntry<CraftingRecipe>> match = recipeManager.getFirstMatch(
                    RecipeType.CRAFTING,
                    input,
                    world
            );

            return match.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 将玩家背包索引转换为屏幕处理器槽位ID
     * 
     * 玩家背包界面的槽位布局（PlayerScreenHandler）：
     * - 输出槽：0
     * - 合成网格输入槽：1-4（2×2）
     * - 装备槽：5-8
     * - 主背包：9-35（3行×9列）
     * - 快捷栏：36-44
     * - 副手槽：45
     * 
     * @param invIndex 玩家背包索引（0-35，其中0-8是快捷栏，9-35是主背包）
     * @return 屏幕处理器槽位ID，-1表示无效
     */
    private int playerInventoryIndexToHandlerSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            return 36 + invIndex;  // 快捷栏
        }
        if (invIndex >= 9 && invIndex <= 35) {
            return 9 + (invIndex - 9);  // 主背包
        }
        return -1;
    }

    // =========================
    // 热键 / 状态管理
    // =========================

    /**
     * 处理热键输入
     * 
     * V键：单次合成
     * Alt+C：启动/停止连续合成
     * 
     * @param client Minecraft客户端实例
     * @param handler 玩家背包屏幕处理器
     */
    private void handleHotkeys(MinecraftClient client, PlayerScreenHandler handler) {
        // 检测按键状态
        boolean vDown = QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld();
        boolean rapidDown = QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();

        // V键按下（单次合成）
        if (vDown && !lastVDown) {
            handleSingleCraft(client, handler);
        }

        // 连续合成键按下（启动/停止连续合成）
        if (rapidDown && !lastAltCDown) {
            startRapidCraft(client, handler, false);
        }

        // 连续合成键释放（停止连续合成）
        if (!rapidDown && rapidCraftingActive && !rapidCraftStartedByButton) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.backpack.stopped"));
        }

        // 更新按键状态
        lastVDown = vDown;
        lastAltCDown = rapidDown;
    }

    private boolean startRapidCraft(MinecraftClient client,
                                    PlayerScreenHandler handler,
                                    boolean fromButton) {
        RecipeEntry<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null) {
            lockCurrentRecipe(currentRecipe, handler);
        }

        if (lockedRecipe == null) {
            rapidCraftingActive = false;
            rapidCraftStartedByButton = false;
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_recipe"));
            return false;
        }

        rapidCraftingActive = true;
        rapidCraftStartedByButton = fromButton;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        noProgressTicks = 0;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        fakeProgressTicks = 0;

        refreshProgressSnapshot(client, lockedRecipe);
        sendStatusMessage(client, Text.translatable("quickcraft.message.backpack.started"));
        return true;
    }

    /**
     * 检查当前上下文是否有效
     * 必须满足：
     * 1. 玩家存在且世界存在
     * 2. 当前屏幕是玩家背包界面
     * 3. 屏幕处理器是玩家背包处理器
     * 
     * @param client Minecraft客户端实例
     * @return true表示上下文有效
     */
    private boolean isCraftingContextValid(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return false;
        }

        if (!(client.currentScreen instanceof InventoryScreen)) {
            return false;
        }

        return client.player.currentScreenHandler instanceof PlayerScreenHandler;
    }

    /**
     * 刷新进度快照
     * 记录当前的产物数量和空槽位数，用于后续检测是否有进展
     * 
     * @param client Minecraft客户端实例
     * @param recipe 当前配方
     */
    private void refreshProgressSnapshot(MinecraftClient client, RecipeEntry<CraftingRecipe> recipe) {
        if (client.player == null) {
            lastResultCount = -1;
            lastEmptySlots = -1;
            return;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        PlayerInventory inventory = client.player.getInventory();

        lastResultCount = countMatchingItems(inventory, resultTemplate);
        lastEmptySlots = countEmptyMainSlots(inventory);
    }

    /**
     * 检测是否有进展，如果没有则可能停止合成
     * 
     * 进展定义：
     * - 产物数量增加
     * - 空槽位数量增加
     * 
     * @param client Minecraft客户端实例
     * @param recipe 当前配方
     */
    private void detectNoProgressAndMaybeStop(MinecraftClient client,
                                              RecipeEntry<CraftingRecipe> recipe) {
        if (client.player == null) {
            return;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        PlayerInventory inventory = client.player.getInventory();

        int currentResultCount = countMatchingItems(inventory, resultTemplate);
        int currentEmptySlots = countEmptyMainSlots(inventory);

        boolean progressed = false;

        // 检查产物数量是否增加
        if (lastResultCount >= 0 && currentResultCount > lastResultCount) {
            progressed = true;
        }
        // 检查空槽位是否增加
        if (lastEmptySlots >= 0 && currentEmptySlots > lastEmptySlots) {
            progressed = true;
        }

        // 更新快照
        lastResultCount = currentResultCount;
        lastEmptySlots = currentEmptySlots;

        if (progressed) {
            noProgressTicks = 0;
            return;
        }

        // 无进展计数增加
        noProgressTicks++;
        if (noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.backpack.no_progress"));
        }
    }

    /**
     * 统计背包中匹配指定模板的物品总数量
     * 
     * @param inventory 玩家背包
     * @param template 物品模板
     * @return 总数量
     */
    private int countMatchingItems(PlayerInventory inventory, ItemStack template) {
        if (template.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (ItemStack stack : inventory.main) {
            if (stack.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 统计背包主区域的空槽位数量
     * 
     * @param inventory 玩家背包
     * @return 空槽位数
     */
    private int countEmptyMainSlots(PlayerInventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.main) {
            if (stack.isEmpty()) {
                total++;
            }
        }
        return total;
    }

    /**
     * 发送聊天消息
     * 
     * @param client Minecraft客户端实例
     * @param message 消息内容
     */
    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    /**
     * 停止连续合成
     * 
     * @param client Minecraft客户端实例
     * @param message 停止原因消息
     */
    private void stopRapidCraft(MinecraftClient client, Text message) {
        if (lockedRecipe != null && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            dropCraftResultsAfterStop(client, (PlayerScreenHandler) client.player.currentScreenHandler, lockedRecipe);
        }

        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        noProgressTicks = 0;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        sendStatusMessage(client, message);
    }

    /**
     * 重置所有状态
     * 在离开玩家背包界面或上下文失效时调用
     */
    private void resetAll() {
        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        lockedRecipe = null;
        lockedCraftingPattern.clear();
        noProgressTicks = 0;
        lastResultCount = -1;
        lastEmptySlots = -1;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        fakeProgressTicks = 0;
        lastVDown = false;
        lastAltCDown = false;
    }

    private boolean isAltDown(MinecraftClient client) {
        long windowHandle = client.getWindow().getHandle();
        return GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    private boolean isCraftButtonRapidModeHeld(MinecraftClient client) {
        long windowHandle = client.getWindow().getHandle();
        return isAltDown(client)
                && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private void dropCraftResultsAfterStop(MinecraftClient client,
                                           PlayerScreenHandler handler,
                                           RecipeEntry<CraftingRecipe> recipe) {
        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (!resultTemplate.isEmpty()) {
            dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate, 1);
        }
    }
}
