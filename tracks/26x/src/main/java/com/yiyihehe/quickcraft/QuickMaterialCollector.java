package com.yiyihehe.quickcraft;

import static com.yiyihehe.quickcraft.QuickMaterialCollectorShulkerSelection.*;

import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动收集当前材料 HUD 缺失物品。
 * 所有搬运都通过原版槽位点击完成，避免直接改客户端背包造成幽灵物品。
 */
public final class QuickMaterialCollector implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int REOPEN_DELAY_TICKS = 2;
    private static final int LONG_PRESS_TICKS = 4;
    private static final int FALLBACK_SUCCESS_COLOR = 0x11FF11;
    private static final int FALLBACK_SHORTAGE_COLOR = 0xFF9100;
    // 缺失数量阈值余量默认值：0-10 +0，10-20 +1，20-50 +3，50-100 +5，100-500 +10，500+ +32。
    private static final int EXTRA_ALLOWANCE_LIMIT_10 = 10;
    private static final int EXTRA_ALLOWANCE_LIMIT_20 = 20;
    private static final int EXTRA_ALLOWANCE_LIMIT_50 = 50;
    private static final int EXTRA_ALLOWANCE_LIMIT_100 = 100;
    private static final int EXTRA_ALLOWANCE_LIMIT_500 = 500;

    private static CollectionTask activeTask;
    private static BlockPos completedTarget;
    private static boolean suppressUseUntilRelease;
    private static boolean longPressSessionActive;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isAutoCollectMaterialsEnabled()) {
            completedTarget = null;
            suppressUseUntilRelease = false;
            longPressSessionActive = false;
            if (activeTask == null) {
                return;
            }
            if (!activeTask.longPressActivated) {
                stopTask(client, true);
                return;
            }
            activeTask.stopRequested = true;
            processTask(client);
            return;
        }

        boolean useDown = client.player != null
                && client.level != null
                && QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.keyUse);
        if (!useDown) {
            if (activeTask != null) {
                activeTask.stopRequested = true;
            }
            completedTarget = null;
            suppressUseUntilRelease = false;
            longPressSessionActive = false;
        } else if (activeTask == null) {
            // 长按期间切换到新容器时，每个目标都重新发起一次交互。
            tryStartTask(client);
        }

        if (activeTask != null && !activeTask.longPressActivated) {
            if (!useDown) {
                sendStatusMessage(
                        client,
                        Component.translatable("quickcraft.message.material_collector.hold_to_collect")
                                .withStyle(ChatFormatting.RED)
                );
                stopTask(client, true);
                return;
            }
            if (++activeTask.holdTicks < LONG_PRESS_TICKS) {
                return;
            }
            activeTask.longPressActivated = true;
            longPressSessionActive = true;
        }
        processTask(client);
    }

    private void tryStartTask(Minecraft client) {
        var player = client.player;
        var interactionManager = client.gameMode;
        if (player == null
                || client.level == null
                || interactionManager == null
                || !hasVisibleMaterialLists(player)
                || !(client.hitResult instanceof BlockHitResult hitResult)
                || !isLookingAtSupportedBlock(client)) {
            return;
        }

        BlockPos target = hitResult.getBlockPos().immutable();
        if (target.equals(completedTarget)) {
            return;
        }

        AbstractContainerMenu openHandler = getOpenHandledContainer(client);
        if (openHandler != null) {
            if (!isSupportedHandler(openHandler)) {
                return;
            }
            activeTask = new CollectionTask(hitResult, target, CollectionStage.COLLECT_TARGET);
            activeTask.longPressActivated = longPressSessionActive;
            return;
        }

        if (getCurrentScreen(client) != null) {
            return;
        }

        activeTask = new CollectionTask(hitResult, target, CollectionStage.WAIT_TARGET);
        activeTask.longPressActivated = longPressSessionActive;
        interactionManager.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
    }

    private void processTask(Minecraft client) {
        if (activeTask == null) {
            return;
        }
        if (client.player == null || client.level == null || client.gameMode == null) {
            stopTask(client, false);
            return;
        }

        switch (activeTask.stage) {
            case WAIT_TARGET -> waitForTarget(client);
            case COLLECT_TARGET -> collectFromTarget(client);
            case WAIT_SHULKER -> waitForSourceShulker(client);
            case EXTRACT_SHULKER -> extractFromSourceShulker(client);
            case REOPEN_DELAY -> waitBeforeReopen(client);
            case WAIT_REOPEN -> waitForReopenedTarget(client);
            case RETURN_SHULKER -> returnSourceShulker(client);
        }
    }

    private void waitForTarget(Minecraft client) {
        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (handler != null) {
            if (!isSupportedHandler(handler)) {
                stopTask(client, true);
                return;
            }
            activeTask.stage = CollectionStage.COLLECT_TARGET;
            activeTask.ticks = 0;
            return;
        }

        if (++activeTask.ticks > OPEN_TIMEOUT_TICKS) {
            stopTask(client, false);
        }
    }

    private void collectFromTarget(Minecraft client) {
        Player player = client.player;
        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (player == null || handler == null || !isSupportedHandler(handler)) {
            stopTask(client, true);
            return;
        }

        if (activeTask.stopRequested && activeTask.sourcePlayerIndex < 0) {
            finishTarget(client);
            return;
        }

        if (activeTask.plan == null) {
            activeTask.plan = buildMaterialPlan(player);
        }

        int remainingBefore = getTotalRemaining(activeTask.plan.demands());
        if (shouldUseQuickShulker()) {
            MaterialPlan packingPlan = buildMaterialPlan(player);
            packPlayerTargetMaterialsIntoShulkers(handler, packingPlan.packDemands(), packingPlan.targetTemplates());
            collectToShulkersOrPlayer(handler, activeTask.plan.demands(), activeTask.plan.targetTemplates());
        } else {
            collectToPlayer(handler, activeTask.plan.demands(), activeTask.plan.targetTemplates());
        }

        if (activeTask.stopRequested || activeTask.plan.demands().stream().noneMatch(demand -> demand.remaining() > 0)) {
            finishTarget(client);
            return;
        }

        if (getTotalRemaining(activeTask.plan.demands()) < remainingBefore) {
            return;
        }

        // 最后一格需留给来源盒临时开盒；不能等它也被占满才收空盒。
        if (shouldUseQuickShulker()
                && countEmptyPlayerStorageSlots(handler) <= 1
                && takeEmptyContainerShulker(handler, activeTask.plan.targetTemplates())) {
            return;
        }

        Slot source = shouldUseQuickShulker() ? findExternalSourceShulker(handler, activeTask) : null;
        Slot destination = source != null ? findEmptyPlayerStorageSlot(handler) : null;
        if (source == null || destination == null) {
            finishTarget(client);
            return;
        }

        activeTask.processedSourceSlots.add(source.index);
        activeTask.sourceContainerSlotId = source.index;
        activeTask.sourcePlayerIndex = destination.getContainerSlot();
        clickSlot(handler, source.index, 0, ContainerInput.PICKUP);
        clickSlot(handler, destination.index, 0, ContainerInput.PICKUP);

        Slot moved = findPlayerStorageSlotByIndex(handler, activeTask.sourcePlayerIndex);
        if (moved == null || !moved.hasItem() || !isShulkerBox(moved.getItem())) {
            activeTask.stage = CollectionStage.RETURN_SHULKER;
            return;
        }

        if (!sendOpenQuickShulkerPacket(moved.index)) {
            activeTask.stage = CollectionStage.REOPEN_DELAY;
            activeTask.ticks = REOPEN_DELAY_TICKS;
            return;
        }

        activeTask.previousSyncId = handler.containerId;
        activeTask.stage = CollectionStage.WAIT_SHULKER;
        activeTask.ticks = 0;
    }

    private int getTotalRemaining(List<Demand> demands) {
        int total = 0;
        for (Demand demand : demands) {
            total += demand.remaining();
        }
        return total;
    }

    private boolean takeEmptyContainerShulker(AbstractContainerMenu handler, List<ItemStack> targetTemplates) {
        Slot shulkerSlot = findEmptyContainerShulker(handler);
        if (shulkerSlot == null || !handler.getCarried().isEmpty()) {
            return false;
        }

        Slot materialSlot = findPlayerTargetMaterialSlot(handler, targetTemplates, shulkerSlot.getItem());
        if (materialSlot == null) {
            return false;
        }

        ItemStack material = materialSlot.getItem().copy();
        clickSlot(handler, shulkerSlot.index, 0, ContainerInput.PICKUP);
        if (handler.getCarried().isEmpty() || !isShulkerBox(handler.getCarried())) {
            return false;
        }

        // 满包时先用空盒换出一组投影材料，再把光标材料塞回刚进入背包的盒子。
        clickSlot(handler, materialSlot.index, 0, ContainerInput.PICKUP);
        if (!materialSlot.hasItem()
                || !isShulkerBox(materialSlot.getItem())
                || handler.getCarried().isEmpty()
                || !stacksExactlyMatch(handler.getCarried(), material)) {
            if (materialSlot.hasItem()
                    && isShulkerBox(materialSlot.getItem())
                    && !handler.getCarried().isEmpty()
                    && !isShulkerBox(handler.getCarried())) {
                clickSlot(handler, materialSlot.index, 0, ContainerInput.PICKUP);
            }
            if (isShulkerBox(handler.getCarried()) && !shulkerSlot.hasItem()) {
                clickSlot(handler, shulkerSlot.index, 0, ContainerInput.PICKUP);
            }
            return false;
        }

        int previousCount;
        do {
            previousCount = handler.getCarried().getCount();
            clickSlot(handler, materialSlot.index, 1, ContainerInput.PICKUP);
        } while (!handler.getCarried().isEmpty()
                && handler.getCarried().getCount() < previousCount);

        if (!handler.getCarried().isEmpty()) {
            // Quick Shulker 未接管插入时恢复交换，避免材料或盒子留在光标上。
            clickSlot(handler, materialSlot.index, 0, ContainerInput.PICKUP);
            if (isShulkerBox(handler.getCarried()) && !shulkerSlot.hasItem()) {
                clickSlot(handler, shulkerSlot.index, 0, ContainerInput.PICKUP);
            }
            return false;
        }
        return materialSlot.hasItem() && isShulkerBox(materialSlot.getItem());
    }

    private Slot findPlayerTargetMaterialSlot(AbstractContainerMenu handler,
                                              List<ItemStack> targetTemplates,
                                              ItemStack shulker) {
        Slot best = null;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasItem()
                    || isShulkerBox(slot.getItem())
                    || !containsTarget(targetTemplates, slot.getItem())
                    || !slot.mayPlace(shulker)) {
                continue;
            }
            if (best == null || slot.getItem().getCount() > best.getItem().getCount()) {
                best = slot;
            }
        }
        return best;
    }

    private Slot findEmptyContainerShulker(AbstractContainerMenu handler) {
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.hasItem()
                    && slot.getItem().getCount() == 1
                    && isShulkerBox(slot.getItem())
                    && !hasStoredItems(slot.getItem())) {
                return slot;
            }
        }
        return null;
    }

    private void waitForSourceShulker(Minecraft client) {
        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (handler instanceof ShulkerBoxMenu && handler.containerId != activeTask.previousSyncId) {
            activeTask.stage = CollectionStage.EXTRACT_SHULKER;
            activeTask.ticks = 0;
            return;
        }

        if (++activeTask.ticks > OPEN_TIMEOUT_TICKS) {
            closeCurrentScreen(client);
            activeTask.stage = CollectionStage.REOPEN_DELAY;
            activeTask.ticks = 0;
        }
    }

    private void extractFromSourceShulker(Minecraft client) {
        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (!(handler instanceof ShulkerBoxMenu)) {
            activeTask.stage = CollectionStage.REOPEN_DELAY;
            activeTask.ticks = 0;
            return;
        }

        collectToPlayer(handler, activeTask.plan.demands(), activeTask.plan.targetTemplates());
        closeCurrentScreen(client);
        activeTask.stage = CollectionStage.REOPEN_DELAY;
        activeTask.ticks = 0;
    }

    private void waitBeforeReopen(Minecraft client) {
        if (++activeTask.ticks < REOPEN_DELAY_TICKS) {
            return;
        }
        if (client.player == null || client.gameMode == null) {
            stopTask(client, false);
            return;
        }

        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, activeTask.hitResult);
        activeTask.stage = CollectionStage.WAIT_REOPEN;
        activeTask.ticks = 0;
    }

    private void waitForReopenedTarget(Minecraft client) {
        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (handler != null) {
            if (!isSupportedHandler(handler)) {
                stopTask(client, true);
                return;
            }
            activeTask.stage = CollectionStage.RETURN_SHULKER;
            activeTask.ticks = 0;
            return;
        }

        if (++activeTask.ticks > OPEN_TIMEOUT_TICKS) {
            // 目标箱无法重开时让盒子安全留在玩家背包，不能为了“归还”把它丢出。
            stopTask(client, false);
        }
    }

    private void returnSourceShulker(Minecraft client) {
        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (handler == null || !isSupportedHandler(handler)) {
            stopTask(client, false);
            return;
        }

        Slot source = findPlayerStorageSlotByIndex(handler, activeTask.sourcePlayerIndex);
        if (source == null || !source.hasItem() || !isShulkerBox(source.getItem())) {
            clearReturnedSource(client, handler);
            return;
        }
        if (!handler.getCarried().isEmpty()) {
            if (++activeTask.returnTicks > OPEN_TIMEOUT_TICKS) {
                stopTask(client, true);
            }
            return;
        }

        Slot target = getReturnContainerSlot(handler, activeTask.sourceContainerSlotId);
        if (target == null) {
            if (++activeTask.returnTicks > OPEN_TIMEOUT_TICKS) {
                // 容器满时保留盒子在玩家背包，不丢弃也不清除未完成追踪。
                stopTask(client, true);
            }
            return;
        }

        clickSlot(handler, source.index, 0, ContainerInput.PICKUP);
        clickSlot(handler, target.index, 0, ContainerInput.PICKUP);
        if (handler.getCarried().isEmpty() && !source.hasItem()) {
            clearReturnedSource(client, handler);
        } else if (++activeTask.returnTicks > OPEN_TIMEOUT_TICKS) {
            stopTask(client, true);
        }
    }

    private void clearReturnedSource(Minecraft client, AbstractContainerMenu handler) {
        activeTask.sourceContainerSlotId = -1;
        activeTask.sourcePlayerIndex = -1;
        if (activeTask.stopRequested) {
            finishTarget(client);
            return;
        }

        // 抽出的材料此时已经脱离源盒，按需求量从大到小集中进玩家随身盒。
        Player player = client.player;
        if (player == null) {
            stopTask(client, true);
            return;
        }
        MaterialPlan packingPlan = buildMaterialPlan(player);
        if (shouldUseQuickShulker()) {
            packPlayerTargetMaterialsIntoShulkers(handler, packingPlan.packDemands(), packingPlan.targetTemplates());
        }
        activeTask.stage = CollectionStage.COLLECT_TARGET;
        activeTask.returnTicks = 0;
    }

    private Slot findExternalSourceShulker(AbstractContainerMenu handler, CollectionTask task) {
        Slot best = null;
        int bestContribution = 0;
        for (Slot slot : getContainerSlots(handler)) {
            if (task.processedSourceSlots.contains(slot.index) || !slot.hasItem() || !isShulkerBox(slot.getItem())) {
                continue;
            }

            int contribution = 0;
            for (ItemStack stored : getStoredStacks(slot.getItem())) {
                Demand demand = findDemand(task.plan.demands(), stored);
                if (demand != null && demand.remaining() > 0) {
                    contribution += Math.min(stored.getCount(), demand.remaining());
                }
            }
            if (contribution > bestContribution) {
                best = slot;
                bestContribution = contribution;
            }
        }
        return best;
    }

    private int countEmptyPlayerStorageSlots(AbstractContainerMenu handler) {
        int count = 0;
        ItemStack shulker = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasItem() && slot.mayPlace(shulker)) {
                count++;
            }
        }
        return count;
    }

    private Slot findEmptyPlayerStorageSlot(AbstractContainerMenu handler) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasItem() && slot.mayPlace(new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX))) {
                return slot;
            }
        }
        return null;
    }

    private Slot findPlayerStorageSlotByIndex(AbstractContainerMenu handler, int playerIndex) {
        for (Slot slot : handler.slots) {
            if (isPlayerStorageSlot(slot) && slot.getContainerSlot() == playerIndex) {
                return slot;
            }
        }
        return null;
    }

    private Slot getReturnContainerSlot(AbstractContainerMenu handler, int preferredSlotId) {
        if (preferredSlotId >= 0 && preferredSlotId < handler.slots.size()) {
            Slot preferred = handler.getSlot(preferredSlotId);
            if (!isPlayerStorageSlot(preferred) && !preferred.hasItem()) {
                return preferred;
            }
        }
        for (Slot slot : getContainerSlots(handler)) {
            if (!slot.hasItem()) {
                return slot;
            }
        }
        return null;
    }

    private AbstractContainerMenu getOpenHandledContainer(Minecraft client) {
        if (client == null || client.player == null) {
            return null;
        }
        if (getCurrentScreen(client) instanceof AbstractContainerScreen<?> screen) {
            return screen.getMenu();
        }
        AbstractContainerMenu handler = client.player.containerMenu;
        return handler == null || handler == client.player.inventoryMenu || handler.containerId == 0 ? null : handler;
    }

    private boolean sendOpenQuickShulkerPacket(int slotId) {
        return canOpenQuickShulker() && QuickShulkerConnection.sendOpen(slotId);
    }

    private boolean canOpenQuickShulker() {
        return shouldUseQuickShulker() && QuickShulkerConnection.canOpen();
    }

    private void finishTarget(Minecraft client) {
        if (activeTask != null && activeTask.longPressActivated && activeTask.plan != null) {
            sendCollectionResult(client, activeTask.plan);
        }
        completedTarget = activeTask != null ? activeTask.target : null;
        stopTask(client, true);
    }

    private void sendCollectionResult(Minecraft client, MaterialPlan plan) {
        if (client.player == null) {
            return;
        }

        MutableComponent details = Component.empty();
        int totalCollected = 0;
        int successColor = getMaterialStatusColor("getCollectorSuccessColor", FALLBACK_SUCCESS_COLOR);
        int shortageColor = getMaterialStatusColor("getCollectorShortageColor", FALLBACK_SHORTAGE_COLOR);
        for (Demand demand : plan.demands()) {
            int collected = demand.collected();
            if (collected <= 0) {
                continue;
            }

            totalCollected += collected;
            int available = countAvailableInPlayerInventory(client.player.getInventory(), demand.template());
            int shortage = Math.max(0, demand.missing() - available);
            int color = shortage > 0 ? shortageColor : successColor;
            if (totalCollected > collected) {
                details.append(Component.literal(" "));
            }
            MutableComponent entry = Component.literal(demand.template().getHoverName().getString())
                    .append(Component.literal("×" + formatCollectedAmount(collected, demand.template())))
                    .withStyle(style -> style.withColor(TextColor.fromRgb(color)));
            details.append(entry);
        }

        if (totalCollected > 0) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.material_collector.result", details));
        }
    }

    private String formatCollectedAmount(int count, ItemStack template) {
        int stackSize = Math.max(1, template.getMaxStackSize());
        if (stackSize <= 1 || count < stackSize) {
            return Integer.toString(count);
        }

        int groups = count / stackSize;
        int remainder = count % stackSize;
        return remainder == 0
                ? stackSize + "*" + groups
                : stackSize + "*" + groups + "+" + remainder;
    }

    private int getMaterialStatusColor(String methodName, int fallback) {
        try {
            Class<?> bridge = Class.forName("com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists");
            Method method = bridge.getMethod(methodName);
            Object value = method.invoke(null);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return fallback;
        }
    }

    private void sendStatusMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendOverlayMessage(message);
        }
    }

    private void stopTask(Minecraft client, boolean closeScreen) {
        boolean hasOpenHandler = getOpenHandledContainer(client) != null;
        suppressUseUntilRelease |= activeTask != null;
        activeTask = null;
        if (closeScreen || hasOpenHandler) {
            closeCurrentScreen(client);
        }
    }

    public static boolean shouldHideBackgroundHandledScreen() {
        return activeTask != null;
    }

    public static boolean shouldSuppressBackgroundHandledScreenOpen() {
        return activeTask != null || suppressUseUntilRelease;
    }

    public static boolean shouldSuppressUseInput() {
        return activeTask != null || suppressUseUntilRelease;
    }

    public static boolean shouldHandleCurrentTarget(Minecraft client) {
        if (!QuickCraftConfigs.isAutoCollectMaterialsEnabled()
                || client == null
                || client.player == null
                || client.level == null) {
            return false;
        }

        QuickMaterialCollector collector = new QuickMaterialCollector();
        return collector.isLookingAtSupportedBlock(client) && collector.hasVisibleMaterialLists(client.player);
    }

    private boolean isLookingAtSupportedBlock(Minecraft client) {
        HitResult hitResult = client.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.level == null) {
            return false;
        }

        Block block = client.level.getBlockState(blockHitResult.getBlockPos()).getBlock();
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof EnderChestBlock
                || block instanceof ShulkerBoxBlock;
    }

    private boolean isSupportedHandler(AbstractContainerMenu handler) {
        return handler instanceof ChestMenu || handler instanceof ShulkerBoxMenu;
    }

    private MaterialPlan buildMaterialPlan(Player player) {
        List<MaterialRequest> requests = getVisibleMaterialRequests(player);
        List<Demand> demands = new ArrayList<>();
        List<ItemStack> targetTemplates = new ArrayList<>();

        for (MaterialRequest request : requests) {
            if (request.stack().isEmpty()) {
                continue;
            }

            if (!containsTarget(targetTemplates, request.stack())) {
                ItemStack target = request.stack().copy();
                target.setCount(1);
                targetTemplates.add(target);
            }

            if (request.count() <= 0) {
                continue;
            }

            Demand demand = findDemand(demands, request.stack());
            if (demand == null) {
                ItemStack template = request.stack().copy();
                template.setCount(1);
                demands.add(new Demand(template, request.count()));
            } else {
                demand.addMissing(request.count());
            }
        }

        List<PackDemand> packDemands = buildPackDemands(player.getInventory(), demands);

        for (int i = demands.size() - 1; i >= 0; i--) {
            Demand demand = demands.get(i);
            int available = countAvailableInPlayerInventory(player.getInventory(), demand.template());
            int missing = Math.max(0, demand.missing() - available);
            int remaining = getTargetCollectCount(missing);
            if (remaining <= 0) {
                demands.remove(i);
                continue;
            }
            demand.setRemaining(remaining);
        }

        demands.sort(Comparator
                .comparingInt(Demand::remaining).reversed()
                .thenComparing(demand -> demand.template().getHoverName().getString()));
        return new MaterialPlan(demands, targetTemplates, packDemands);
    }

    private List<PackDemand> buildPackDemands(Inventory inventory, List<Demand> demands) {
        List<PackDemand> packDemands = new ArrayList<>();
        for (Demand demand : demands) {
            // 装盒上限必须跨收集轮次保持稳定，否则背包满足需求后会退回 0-10 档并漏掉此前多拿的余量。
            int desired = demand.missing() + getExtraAllowance(demand.missing());
            int alreadyBoxed = countStoredInPlayerShulkers(inventory, demand.template());
            int remaining = Math.max(0, desired - alreadyBoxed);
            if (remaining <= 0) {
                continue;
            }

            ItemStack template = demand.template().copy();
            template.setCount(1);
            packDemands.add(new PackDemand(template, remaining));
        }

        packDemands.sort(Comparator
                .comparingInt(PackDemand::remaining).reversed()
                .thenComparing(demand -> demand.template().getHoverName().getString()));
        return packDemands;
    }

    private boolean hasVisibleMaterialLists(Player player) {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return false;
        }

        try {
            Class<?> bridge = Class.forName("com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists");
            Method method = bridge.getMethod("hasVisibleMaterialLists", Player.class);
            return Boolean.TRUE.equals(method.invoke(null, player));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private List<MaterialRequest> getVisibleMaterialRequests(Player player) {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return List.of();
        }

        try {
            Class<?> bridge = Class.forName("com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists");
            Method method = bridge.getMethod("getVisibleMaterialRequests", Player.class);
            return (List<MaterialRequest>) method.invoke(null, player);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return List.of();
        }
    }

    private int getTargetCollectCount(int requestedCount) {
        return requestedCount + getExtraAllowance(requestedCount);
    }

    private int getExtraAllowance(int needed) {
        if (needed <= EXTRA_ALLOWANCE_LIMIT_10) {
            return QuickCraftConfigs.getMaterialCollectExtra0To10();
        }
        if (needed <= EXTRA_ALLOWANCE_LIMIT_20) {
            return QuickCraftConfigs.getMaterialCollectExtra10To20();
        }
        if (needed <= EXTRA_ALLOWANCE_LIMIT_50) {
            return QuickCraftConfigs.getMaterialCollectExtra20To50();
        }
        if (needed <= EXTRA_ALLOWANCE_LIMIT_100) {
            return QuickCraftConfigs.getMaterialCollectExtra50To100();
        }
        if (needed <= EXTRA_ALLOWANCE_LIMIT_500) {
            return QuickCraftConfigs.getMaterialCollectExtra100To500();
        }
        return QuickCraftConfigs.getMaterialCollectExtraOver500();
    }

    private void collectToPlayer(AbstractContainerMenu handler, List<Demand> demands, List<ItemStack> targetTemplates) {
        if (!handler.getCarried().isEmpty()) {
            return;
        }

        for (Demand demand : demands) {
            if (demand.remaining() <= 0) {
                continue;
            }

            for (Slot source : getContainerSlots(handler)) {
                if (demand.remaining() <= 0) {
                    break;
                }
                if (!source.hasItem()) {
                    continue;
                }

                ItemStack stack = source.getItem();
                if (isShulkerBox(stack)) {
                    moveWholeCleanShulkerIfUseful(handler, source, demands, targetTemplates);
                    continue;
                }
                if (!stacksMatch(stack, demand.template())) {
                    continue;
                }

                int amount = Math.min(stack.getCount(), demand.remaining());
                int moved = moveFromContainerToPlayer(handler, source.index, demand.template(), amount);
                demand.decrease(moved);
            }
        }
    }

    private void collectToShulkersOrPlayer(AbstractContainerMenu handler, List<Demand> demands, List<ItemStack> targetTemplates) {
        if (!handler.getCarried().isEmpty()) {
            return;
        }

        for (Demand demand : demands) {
            if (demand.remaining() <= 0) {
                continue;
            }

            for (Slot source : getContainerSlots(handler)) {
                if (demand.remaining() <= 0) {
                    break;
                }
                if (!source.hasItem()) {
                    continue;
                }

                ItemStack stack = source.getItem();
                if (isShulkerBox(stack)) {
                    moveWholeCleanShulkerIfUseful(handler, source, demands, targetTemplates);
                    continue;
                }
                if (!stacksMatch(stack, demand.template())) {
                    continue;
                }

                int amount = Math.min(stack.getCount(), demand.remaining());
                int moved = moveSlotAmountIntoShulkers(handler, source.index, demand.template(), amount, targetTemplates);
                demand.decrease(moved);

                int remainingAmount = amount - moved;
                if (remainingAmount > 0) {
                    moved = moveFromContainerToPlayer(handler, source.index, demand.template(), remainingAmount);
                    demand.decrease(moved);
                }
            }
        }
    }

    private int moveSlotAmountIntoShulkers(AbstractContainerMenu handler,
                                           int sourceSlotId,
                                           ItemStack template,
                                           int amount,
                                           List<ItemStack> targetTemplates) {
        Slot source = handler.getSlot(sourceSlotId);
        if (amount <= 0 || !source.hasItem() || isShulkerBox(source.getItem()) || !stacksMatch(source.getItem(), template)) {
            return 0;
        }

        int amountToPack = Math.min(amount, source.getItem().getCount());
        if (amountToPack < source.getItem().getCount() && hasQuickShulkerBundlingConflict(template)) {
            return moveQuickShulkerConflictAmount(
                    handler,
                    sourceSlotId,
                    template,
                    amountToPack,
                    targetTemplates,
                    true
            );
        }

        clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP);
        while (!handler.getCarried().isEmpty() && handler.getCarried().getCount() > amountToPack) {
            int before = handler.getCarried().getCount();
            // 超出需求的部分立即放回原容器槽，不借用箱子槽位临时存无关物品。
            clickSlot(handler, sourceSlotId, 1, ContainerInput.PICKUP);
            int after = handler.getCarried().isEmpty() ? 0 : handler.getCarried().getCount();
            if (after >= before) {
                break;
            }
        }

        int moved = packCursorIntoShulkers(handler, targetTemplates);

        if (!handler.getCarried().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP);
        }

        return moved;
    }

    private int moveFromContainerToPlayer(AbstractContainerMenu handler, int sourceSlotId, ItemStack template, int amount) {
        if (amount <= 0) {
            return 0;
        }

        Slot source = handler.getSlot(sourceSlotId);
        if (!source.hasItem() || !stacksMatch(source.getItem(), template)) {
            return 0;
        }

        ItemStack sourceTemplate = source.getItem().copy();
        sourceTemplate.setCount(1);
        int sourceCount = source.getItem().getCount();
        int moveAmount = Math.min(amount, sourceCount);
        if (!hasPlayerCapacity(handler, sourceTemplate, moveAmount)) {
            return 0;
        }

        if (moveAmount == sourceCount) {
            clickSlot(handler, sourceSlotId, 0, ContainerInput.QUICK_MOVE);
            return moveAmount;
        }
        if (hasQuickShulkerBundlingConflict(sourceTemplate)) {
            return moveQuickShulkerConflictAmount(
                    handler,
                    sourceSlotId,
                    sourceTemplate,
                    moveAmount,
                    List.of(),
                    false
            );
        }

        clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP);
        int deposited = depositCursorToPlayer(handler, sourceTemplate, moveAmount);

        if (!handler.getCarried().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP);
        }

        return deposited;
    }

    private int moveQuickShulkerConflictAmount(AbstractContainerMenu handler,
                                                int sourceSlotId,
                                                ItemStack template,
                                                int amount,
                                                List<ItemStack> targetTemplates,
                                                boolean intoShulkers) {
        if (!handler.getCarried().isEmpty()) {
            return 0;
        }

        Slot source = handler.getSlot(sourceSlotId);
        if (!source.hasItem() || !stacksMatch(source.getItem(), template)) {
            return 0;
        }

        int targetAmount = Math.min(amount, source.getItem().getCount());
        if (targetAmount <= 0 || targetAmount >= source.getItem().getCount()) {
            return 0;
        }

        Slot buffer = findEmptyTemporarySplitSlot(handler, sourceSlotId, template);
        if (buffer == null) {
            // 没有安全缓冲槽时跳过半组末影箱，不能退回多余数量时触发 Quick Shulker 装入自身。
            return 0;
        }

        int moved = 0;
        while (moved < targetAmount && source.hasItem() && handler.getCarried().isEmpty()) {
            int remaining = targetAmount - moved;
            if (source.getItem().getCount() <= remaining) {
                clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP);
            } else {
                // 空光标右键数量大于 1 的末影箱堆只会原版对半拆分，不会触发 Quick Shulker。
                clickSlot(handler, sourceSlotId, 1, ContainerInput.PICKUP);
                if (handler.getCarried().isEmpty() || !stacksMatch(handler.getCarried(), template)) {
                    break;
                }
                if (handler.getCarried().getCount() > remaining) {
                    clickSlot(handler, buffer.index, 0, ContainerInput.PICKUP);
                    if (!handler.getCarried().isEmpty()) {
                        break;
                    }
                    continue;
                }
            }

            if (handler.getCarried().isEmpty() || !stacksMatch(handler.getCarried(), template)) {
                break;
            }

            int before = handler.getCarried().getCount();
            int deposited = intoShulkers
                    ? packCursorIntoShulkers(handler, targetTemplates)
                    : depositCursorToPlayerWithLeftClicks(handler, template, buffer.index);
            moved += Math.min(before, Math.max(0, deposited));

            if (!handler.getCarried().isEmpty()) {
                clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP);
                break;
            }
        }

        restoreTemporarySplitBuffer(handler, sourceSlotId, buffer.index);
        return moved;
    }

    private int depositCursorToPlayerWithLeftClicks(AbstractContainerMenu handler,
                                                     ItemStack template,
                                                     int excludedSlotId) {
        int deposited = 0;
        while (!handler.getCarried().isEmpty()) {
            Slot target = findPlayerDepositSlot(handler, template, excludedSlotId);
            if (target == null) {
                break;
            }

            int before = handler.getCarried().getCount();
            clickSlot(handler, target.index, 0, ContainerInput.PICKUP);
            int after = handler.getCarried().isEmpty() ? 0 : handler.getCarried().getCount();
            if (after >= before) {
                break;
            }
            deposited += before - after;
        }
        return deposited;
    }

    private void restoreTemporarySplitBuffer(AbstractContainerMenu handler, int sourceSlotId, int bufferSlotId) {
        if (!handler.getCarried().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP);
        }
        Slot buffer = handler.getSlot(bufferSlotId);
        if (!handler.getCarried().isEmpty() || !buffer.hasItem()) {
            return;
        }

        clickSlot(handler, bufferSlotId, 0, ContainerInput.PICKUP);
        clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP);
        if (!handler.getCarried().isEmpty()) {
            clickSlot(handler, bufferSlotId, 0, ContainerInput.PICKUP);
        }
    }

    private int depositCursorToPlayer(AbstractContainerMenu handler, ItemStack template, int amount) {
        int deposited = 0;

        while (deposited < amount && !handler.getCarried().isEmpty()) {
            Slot target = findPlayerDepositSlot(handler, template);
            if (target == null) {
                break;
            }

            int before = handler.getCarried().getCount();
            clickSlot(handler, target.index, 1, ContainerInput.PICKUP);
            int after = handler.getCarried().isEmpty() ? 0 : handler.getCarried().getCount();
            if (after >= before) {
                break;
            }
            deposited += before - after;
        }

        return deposited;
    }

    private void moveWholeCleanShulkerIfUseful(AbstractContainerMenu handler,
                                               Slot source,
                                               List<Demand> demands,
                                               List<ItemStack> targetTemplates) {
        WholeShulkerCandidate bestCandidate = findBestWholeShulkerCandidate(handler, demands, targetTemplates);
        if (bestCandidate == null || bestCandidate.slot().index != source.index) {
            return;
        }

        ItemStack shulker = source.getItem();
        List<StoredCount> contents = getStoredTargetCounts(shulker, demands);
        if (contents.isEmpty() || !containsOnlyTargetMaterials(shulker, targetTemplates)) {
            return;
        }

        for (StoredCount content : contents) {
            if (content.count() > content.demand().remaining()) {
                return;
            }
        }
        if (!hasPlayerCapacity(handler, shulker, shulker.getCount())) {
            return;
        }

        clickSlot(handler, source.index, 0, ContainerInput.QUICK_MOVE);
        for (StoredCount content : contents) {
            content.demand().decrease(content.count());
        }
    }

    private void packPlayerTargetMaterialsIntoShulkers(AbstractContainerMenu handler,
                                                       List<PackDemand> packDemands,
                                                       List<ItemStack> targetTemplates) {
        if (!handler.getCarried().isEmpty()) {
            return;
        }

        for (PackDemand demand : packDemands) {
            if (demand.remaining() <= 0) {
                continue;
            }

            for (Slot source : getPlayerStorageSlots(handler)) {
                if (demand.remaining() <= 0) {
                    break;
                }
                if (!source.hasItem() || isShulkerBox(source.getItem()) || !stacksMatch(source.getItem(), demand.template())) {
                    continue;
                }

                int amount = Math.min(source.getItem().getCount(), demand.remaining());
                int moved = moveSlotAmountIntoShulkers(handler, source.index, demand.template(), amount, targetTemplates);
                demand.decrease(moved);
            }
        }
    }

    private int packCursorIntoShulkers(AbstractContainerMenu handler, List<ItemStack> targetTemplates) {
        int moved = 0;

        while (!handler.getCarried().isEmpty()) {
            Slot shulkerSlot = findDestinationShulkerSlot(handler, handler.getCarried(), targetTemplates);
            if (shulkerSlot == null) {
                break;
            }

            ItemStack beforeStack = handler.getCarried().copy();
            int before = handler.getCarried().getCount();
            // 右键潜影盒槽位，让 Quick Shulker 的服务端逻辑负责真实写入。
            clickSlot(handler, shulkerSlot.index, 1, ContainerInput.PICKUP);
            if (!handler.getCarried().isEmpty() && !stacksExactlyMatch(handler.getCarried(), beforeStack)) {
                clickSlot(handler, shulkerSlot.index, 0, ContainerInput.PICKUP);
                break;
            }
            int after = handler.getCarried().isEmpty() ? 0 : handler.getCarried().getCount();
            if (after < before) {
                moved += before - after;
                continue;
            }
            break;
        }

        return moved;
    }

    private boolean shouldUseQuickShulker() {
        return QuickCraftConfigs.isAutoCollectMaterialsWithQuickShulkerEnabled()
                && QuickShulkerConnection.canBundle();
    }

    private int countAvailableInPlayerInventory(Inventory inventory, ItemStack template) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }

            if (isShulkerBox(stack) && hasStoredItems(stack)) {
                for (ItemStack stored : getStoredStacks(stack)) {
                    if (stacksMatch(stored, template)) {
                        count += stored.getCount();
                    }
                }
            } else if (stacksMatch(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private Slot findPlayerDepositSlot(AbstractContainerMenu handler, ItemStack template) {
        return findPlayerDepositSlot(handler, template, -1);
    }

    private Slot findPlayerDepositSlot(AbstractContainerMenu handler, ItemStack template, int excludedSlotId) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.index == excludedSlotId || !slot.hasItem() || !slot.mayPlace(template)) {
                continue;
            }
            if (stacksExactlyMatch(slot.getItem(), template) && slot.getItem().getCount() < slot.getItem().getMaxStackSize()) {
                return slot;
            }
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.index != excludedSlotId && !slot.hasItem() && slot.mayPlace(template)) {
                return slot;
            }
        }

        return null;
    }

    private Slot findEmptyTemporarySplitSlot(AbstractContainerMenu handler, int sourceSlotId, ItemStack template) {
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.index != sourceSlotId && !slot.hasItem() && slot.mayPlace(template)) {
                return slot;
            }
        }
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.index != sourceSlotId && !slot.hasItem() && slot.mayPlace(template)) {
                return slot;
            }
        }
        return null;
    }

    private boolean hasQuickShulkerBundlingConflict(ItemStack stack) {
        return FabricLoader.getInstance().isModLoaded("quickshulker") && stack.is(Items.ENDER_CHEST);
    }

    private void clickSlot(AbstractContainerMenu handler, int slotId, int button, ContainerInput actionType) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private void closeCurrentScreen(Minecraft client) {
        if (client.player != null) {
            client.player.closeContainer();
        }
        if (getCurrentScreen(client) instanceof AbstractContainerScreen<?>) {
            clearCurrentScreen(client);
        }
    }

    private static Screen getCurrentScreen(Minecraft client) {
        return QuickClientScreenAccess.currentScreen(client);
    }

    private static void clearCurrentScreen(Minecraft client) {
        QuickClientScreenAccess.setScreen(client, null);
    }

    private enum CollectionStage {
        WAIT_TARGET,
        COLLECT_TARGET,
        WAIT_SHULKER,
        EXTRACT_SHULKER,
        REOPEN_DELAY,
        WAIT_REOPEN,
        RETURN_SHULKER
    }

    private static final class CollectionTask {
        private final BlockHitResult hitResult;
        private final BlockPos target;
        private final Set<Integer> processedSourceSlots = new HashSet<>();
        private CollectionStage stage;
        private MaterialPlan plan;
        private boolean stopRequested;
        private boolean longPressActivated;
        private int holdTicks;
        private int ticks;
        private int returnTicks;
        private int previousSyncId = -1;
        private int sourceContainerSlotId = -1;
        private int sourcePlayerIndex = -1;

        private CollectionTask(BlockHitResult hitResult, BlockPos target, CollectionStage stage) {
            this.hitResult = hitResult;
            this.target = target;
            this.stage = stage;
        }
    }

    public record MaterialRequest(ItemStack stack, int count) {
    }

    private record MaterialPlan(List<Demand> demands, List<ItemStack> targetTemplates, List<PackDemand> packDemands) {
    }

    static final class Demand {
        private final ItemStack template;
        private int missing;
        private int remaining;
        private int collected;

        Demand(ItemStack template, int missing) {
            this.template = template;
            this.missing = missing;
        }

        ItemStack template() {
            return template;
        }

        int missing() {
            return missing;
        }

        int remaining() {
            return remaining;
        }

        int collected() {
            return collected;
        }

        void addMissing(int count) {
            this.missing += count;
        }

        void setRemaining(int remaining) {
            this.remaining = remaining;
        }

        void decrease(int count) {
            int moved = Math.min(this.remaining, Math.max(0, count));
            this.remaining -= moved;
            this.collected += moved;
        }
    }

    static final class StoredCount {
        private final Demand demand;
        private int count;

        StoredCount(Demand demand, int count) {
            this.demand = demand;
            this.count = count;
        }

        Demand demand() {
            return demand;
        }

        int count() {
            return count;
        }

        void add(int count) {
            this.count += count;
        }
    }

    private static final class PackDemand {
        private final ItemStack template;
        private int remaining;

        private PackDemand(ItemStack template, int remaining) {
            this.template = template;
            this.remaining = remaining;
        }

        private ItemStack template() {
            return template;
        }

        private int remaining() {
            return remaining;
        }

        private void decrease(int count) {
            this.remaining = Math.max(0, this.remaining - count);
        }
    }
}
