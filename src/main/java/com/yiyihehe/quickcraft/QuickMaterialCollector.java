package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

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
    private static final int VANILLA_SHULKER_SLOTS = 27;
    private static final int FALLBACK_SUCCESS_COLOR = 0x11FF11;
    private static final int FALLBACK_SHORTAGE_COLOR = 0xFF9100;
    // 缺失数量阈值余量默认值：0-10 +0，10-20 +1，20-50 +3，50-100 +5，100-500 +10，500+ +32。
    private static final int EXTRA_ALLOWANCE_LIMIT_10 = 10;
    private static final int EXTRA_ALLOWANCE_LIMIT_20 = 20;
    private static final int EXTRA_ALLOWANCE_LIMIT_50 = 50;
    private static final int EXTRA_ALLOWANCE_LIMIT_100 = 100;
    private static final int EXTRA_ALLOWANCE_LIMIT_500 = 500;
    private static final Identifier QUICK_SHULKER_BUNDLE_PACKET = Identifier.of("quickshulker", "quick_bundleheld_packet");
    private static final Identifier QUICK_SHULKER_OPEN_PACKET = Identifier.of("quickshulker", "open_shulker_packet");

    private static CollectionTask activeTask;
    private static BlockPos completedTarget;
    private static boolean suppressUseUntilRelease;
    private static boolean longPressSessionActive;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
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
                && client.world != null
                && QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.useKey);
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
                        Text.translatable("quickcraft.message.material_collector.hold_to_collect")
                                .formatted(Formatting.RED)
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

    private void tryStartTask(MinecraftClient client) {
        var player = client.player;
        var interactionManager = client.interactionManager;
        if (player == null
                || client.world == null
                || interactionManager == null
                || !hasVisibleMaterialLists(player)
                || !(client.crosshairTarget instanceof BlockHitResult hitResult)
                || !isLookingAtSupportedBlock(client)) {
            return;
        }

        BlockPos target = hitResult.getBlockPos().toImmutable();
        if (target.equals(completedTarget)) {
            return;
        }

        ScreenHandler openHandler = getOpenHandledContainer(client);
        if (openHandler != null) {
            if (!isSupportedHandler(openHandler)) {
                return;
            }
            activeTask = new CollectionTask(hitResult, target, CollectionStage.COLLECT_TARGET);
            activeTask.longPressActivated = longPressSessionActive;
            return;
        }

        if (client.currentScreen != null) {
            return;
        }

        activeTask = new CollectionTask(hitResult, target, CollectionStage.WAIT_TARGET);
        activeTask.longPressActivated = longPressSessionActive;
        interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
    }

    private void processTask(MinecraftClient client) {
        if (activeTask == null) {
            return;
        }
        if (client.player == null || client.world == null || client.interactionManager == null) {
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

    private void waitForTarget(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
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

    private void collectFromTarget(MinecraftClient client) {
        PlayerEntity player = client.player;
        ScreenHandler handler = getOpenHandledContainer(client);
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

        if (shouldUseQuickShulker()
                && findEmptyPlayerStorageSlot(handler) == null
                && takeEmptyContainerShulker(handler, activeTask.plan.targetTemplates())) {
            return;
        }

        Slot source = shouldUseQuickShulker() ? findExternalSourceShulker(handler, activeTask) : null;
        Slot destination = source != null ? findEmptyPlayerStorageSlot(handler) : null;
        if (source == null || destination == null) {
            finishTarget(client);
            return;
        }

        activeTask.processedSourceSlots.add(source.id);
        activeTask.sourceContainerSlotId = source.id;
        activeTask.sourcePlayerIndex = destination.getIndex();
        clickSlot(handler, source.id, 0, SlotActionType.PICKUP);
        clickSlot(handler, destination.id, 0, SlotActionType.PICKUP);

        Slot moved = findPlayerStorageSlotByIndex(handler, activeTask.sourcePlayerIndex);
        if (moved == null || !moved.hasStack() || !isShulkerBox(moved.getStack())) {
            activeTask.stage = CollectionStage.RETURN_SHULKER;
            return;
        }

        if (!sendOpenQuickShulkerPacket(moved.id)) {
            activeTask.stage = CollectionStage.REOPEN_DELAY;
            activeTask.ticks = REOPEN_DELAY_TICKS;
            return;
        }

        activeTask.previousSyncId = handler.syncId;
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

    private boolean takeEmptyContainerShulker(ScreenHandler handler, List<ItemStack> targetTemplates) {
        Slot shulkerSlot = findEmptyContainerShulker(handler);
        if (shulkerSlot == null || !handler.getCursorStack().isEmpty()) {
            return false;
        }

        Slot materialSlot = findPlayerTargetMaterialSlot(handler, targetTemplates, shulkerSlot.getStack());
        if (materialSlot == null) {
            return false;
        }

        ItemStack material = materialSlot.getStack().copy();
        clickSlot(handler, shulkerSlot.id, 0, SlotActionType.PICKUP);
        if (handler.getCursorStack().isEmpty() || !isShulkerBox(handler.getCursorStack())) {
            return false;
        }

        // 满包时先用空盒换出一组投影材料，再把光标材料塞回刚进入背包的盒子。
        clickSlot(handler, materialSlot.id, 0, SlotActionType.PICKUP);
        if (!materialSlot.hasStack()
                || !isShulkerBox(materialSlot.getStack())
                || handler.getCursorStack().isEmpty()
                || !stacksExactlyMatch(handler.getCursorStack(), material)) {
            if (materialSlot.hasStack()
                    && isShulkerBox(materialSlot.getStack())
                    && !handler.getCursorStack().isEmpty()
                    && !isShulkerBox(handler.getCursorStack())) {
                clickSlot(handler, materialSlot.id, 0, SlotActionType.PICKUP);
            }
            if (isShulkerBox(handler.getCursorStack()) && !shulkerSlot.hasStack()) {
                clickSlot(handler, shulkerSlot.id, 0, SlotActionType.PICKUP);
            }
            return false;
        }

        int previousCount;
        do {
            previousCount = handler.getCursorStack().getCount();
            clickSlot(handler, materialSlot.id, 1, SlotActionType.PICKUP);
        } while (!handler.getCursorStack().isEmpty()
                && handler.getCursorStack().getCount() < previousCount);

        if (!handler.getCursorStack().isEmpty()) {
            // Quick Shulker 未接管插入时恢复交换，避免材料或盒子留在光标上。
            clickSlot(handler, materialSlot.id, 0, SlotActionType.PICKUP);
            if (isShulkerBox(handler.getCursorStack()) && !shulkerSlot.hasStack()) {
                clickSlot(handler, shulkerSlot.id, 0, SlotActionType.PICKUP);
            }
            return false;
        }
        return materialSlot.hasStack() && isShulkerBox(materialSlot.getStack());
    }

    private Slot findPlayerTargetMaterialSlot(ScreenHandler handler,
                                              List<ItemStack> targetTemplates,
                                              ItemStack shulker) {
        Slot best = null;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack()
                    || isShulkerBox(slot.getStack())
                    || !containsTarget(targetTemplates, slot.getStack())
                    || !slot.canInsert(shulker)) {
                continue;
            }
            if (best == null || slot.getStack().getCount() > best.getStack().getCount()) {
                best = slot;
            }
        }
        return best;
    }

    private Slot findEmptyContainerShulker(ScreenHandler handler) {
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.hasStack()
                    && slot.getStack().getCount() == 1
                    && isShulkerBox(slot.getStack())
                    && !hasStoredItems(slot.getStack())) {
                return slot;
            }
        }
        return null;
    }

    private void waitForSourceShulker(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler instanceof ShulkerBoxScreenHandler && handler.syncId != activeTask.previousSyncId) {
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

    private void extractFromSourceShulker(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
        if (!(handler instanceof ShulkerBoxScreenHandler)) {
            activeTask.stage = CollectionStage.REOPEN_DELAY;
            activeTask.ticks = 0;
            return;
        }

        collectToPlayer(handler, activeTask.plan.demands(), activeTask.plan.targetTemplates());
        closeCurrentScreen(client);
        activeTask.stage = CollectionStage.REOPEN_DELAY;
        activeTask.ticks = 0;
    }

    private void waitBeforeReopen(MinecraftClient client) {
        if (++activeTask.ticks < REOPEN_DELAY_TICKS) {
            return;
        }
        if (client.player == null || client.interactionManager == null) {
            stopTask(client, false);
            return;
        }

        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, activeTask.hitResult);
        activeTask.stage = CollectionStage.WAIT_REOPEN;
        activeTask.ticks = 0;
    }

    private void waitForReopenedTarget(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
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

    private void returnSourceShulker(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler == null || !isSupportedHandler(handler)) {
            stopTask(client, false);
            return;
        }

        Slot source = findPlayerStorageSlotByIndex(handler, activeTask.sourcePlayerIndex);
        if (source == null || !source.hasStack() || !isShulkerBox(source.getStack())) {
            clearReturnedSource(client, handler);
            return;
        }
        if (!handler.getCursorStack().isEmpty()) {
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

        clickSlot(handler, source.id, 0, SlotActionType.PICKUP);
        clickSlot(handler, target.id, 0, SlotActionType.PICKUP);
        if (handler.getCursorStack().isEmpty() && !source.hasStack()) {
            clearReturnedSource(client, handler);
        } else if (++activeTask.returnTicks > OPEN_TIMEOUT_TICKS) {
            stopTask(client, true);
        }
    }

    private void clearReturnedSource(MinecraftClient client, ScreenHandler handler) {
        activeTask.sourceContainerSlotId = -1;
        activeTask.sourcePlayerIndex = -1;
        if (activeTask.stopRequested) {
            finishTarget(client);
            return;
        }

        // 抽出的材料此时已经脱离源盒，按需求量从大到小集中进玩家随身盒。
        PlayerEntity player = client.player;
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

    private Slot findExternalSourceShulker(ScreenHandler handler, CollectionTask task) {
        Slot best = null;
        int bestContribution = 0;
        for (Slot slot : getContainerSlots(handler)) {
            if (task.processedSourceSlots.contains(slot.id) || !slot.hasStack() || !isShulkerBox(slot.getStack())) {
                continue;
            }

            int contribution = 0;
            for (ItemStack stored : getStoredStacks(slot.getStack())) {
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

    private Slot findEmptyPlayerStorageSlot(ScreenHandler handler) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack() && slot.canInsert(new ItemStack(net.minecraft.item.Items.SHULKER_BOX))) {
                return slot;
            }
        }
        return null;
    }

    private Slot findPlayerStorageSlotByIndex(ScreenHandler handler, int playerIndex) {
        for (Slot slot : handler.slots) {
            if (isPlayerStorageSlot(slot) && slot.getIndex() == playerIndex) {
                return slot;
            }
        }
        return null;
    }

    private Slot getReturnContainerSlot(ScreenHandler handler, int preferredSlotId) {
        if (preferredSlotId >= 0 && preferredSlotId < handler.slots.size()) {
            Slot preferred = handler.getSlot(preferredSlotId);
            if (!isPlayerStorageSlot(preferred) && !preferred.hasStack()) {
                return preferred;
            }
        }
        for (Slot slot : getContainerSlots(handler)) {
            if (!slot.hasStack()) {
                return slot;
            }
        }
        return null;
    }

    private ScreenHandler getOpenHandledContainer(MinecraftClient client) {
        if (client == null || client.player == null) {
            return null;
        }
        if (client.currentScreen instanceof HandledScreen<?> screen) {
            return screen.getScreenHandler();
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        return handler == null || handler == client.player.playerScreenHandler || handler.syncId == 0 ? null : handler;
    }

    private boolean sendOpenQuickShulkerPacket(int slotId) {
        if (!canOpenQuickShulker()) {
            return false;
        }
        try {
            Class<?> packetClass = Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            Object packet = packetClass.getConstructor(int.class).newInstance(slotId);
            ClientPlayNetworking.send((CustomPayload) packet);
            return true;
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return false;
        }
    }

    private boolean canOpenQuickShulker() {
        if (!shouldUseQuickShulker()) {
            return false;
        }
        try {
            return ClientPlayNetworking.canSend(QUICK_SHULKER_OPEN_PACKET);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void finishTarget(MinecraftClient client) {
        if (activeTask != null && activeTask.longPressActivated && activeTask.plan != null) {
            sendCollectionResult(client, activeTask.plan);
        }
        completedTarget = activeTask != null ? activeTask.target : null;
        stopTask(client, true);
    }

    private void sendCollectionResult(MinecraftClient client, MaterialPlan plan) {
        if (client.player == null) {
            return;
        }

        MutableText details = Text.empty();
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
                details.append(Text.literal(" "));
            }
            MutableText entry = Text.literal(demand.template().getName().getString())
                    .append(Text.literal("×" + formatCollectedAmount(collected, demand.template())))
                    .styled(style -> style.withColor(TextColor.fromRgb(color)));
            details.append(entry);
        }

        if (totalCollected > 0) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.material_collector.result", details));
        }
    }

    private String formatCollectedAmount(int count, ItemStack template) {
        int stackSize = Math.max(1, template.getMaxCount());
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

    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    private void stopTask(MinecraftClient client, boolean closeScreen) {
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

    public static boolean shouldHandleCurrentTarget(MinecraftClient client) {
        if (!QuickCraftConfigs.isAutoCollectMaterialsEnabled()
                || client == null
                || client.player == null
                || client.world == null) {
            return false;
        }

        QuickMaterialCollector collector = new QuickMaterialCollector();
        return collector.isLookingAtSupportedBlock(client) && collector.hasVisibleMaterialLists(client.player);
    }

    private boolean isLookingAtSupportedBlock(MinecraftClient client) {
        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.world == null) {
            return false;
        }

        Block block = client.world.getBlockState(blockHitResult.getBlockPos()).getBlock();
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof EnderChestBlock
                || block instanceof ShulkerBoxBlock;
    }

    private boolean isSupportedHandler(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler || handler instanceof ShulkerBoxScreenHandler;
    }

    private MaterialPlan buildMaterialPlan(PlayerEntity player) {
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
                .thenComparing(demand -> demand.template().getName().getString()));
        return new MaterialPlan(demands, targetTemplates, packDemands);
    }

    private List<PackDemand> buildPackDemands(PlayerInventory inventory, List<Demand> demands) {
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
                .thenComparing(demand -> demand.template().getName().getString()));
        return packDemands;
    }

    private boolean hasVisibleMaterialLists(PlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return false;
        }

        try {
            Class<?> bridge = Class.forName("com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists");
            Method method = bridge.getMethod("hasVisibleMaterialLists", PlayerEntity.class);
            return Boolean.TRUE.equals(method.invoke(null, player));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private List<MaterialRequest> getVisibleMaterialRequests(PlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return List.of();
        }

        try {
            Class<?> bridge = Class.forName("com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists");
            Method method = bridge.getMethod("getVisibleMaterialRequests", PlayerEntity.class);
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

    private void collectToPlayer(ScreenHandler handler, List<Demand> demands, List<ItemStack> targetTemplates) {
        if (!handler.getCursorStack().isEmpty()) {
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
                if (!source.hasStack()) {
                    continue;
                }

                ItemStack stack = source.getStack();
                if (isShulkerBox(stack)) {
                    moveWholeCleanShulkerIfUseful(handler, source, demands, targetTemplates);
                    continue;
                }
                if (!stacksMatch(stack, demand.template())) {
                    continue;
                }

                int amount = Math.min(stack.getCount(), demand.remaining());
                int moved = moveFromContainerToPlayer(handler, source.id, demand.template(), amount);
                demand.decrease(moved);
            }
        }
    }

    private void collectToShulkersOrPlayer(ScreenHandler handler, List<Demand> demands, List<ItemStack> targetTemplates) {
        if (!handler.getCursorStack().isEmpty()) {
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
                if (!source.hasStack()) {
                    continue;
                }

                ItemStack stack = source.getStack();
                if (isShulkerBox(stack)) {
                    moveWholeCleanShulkerIfUseful(handler, source, demands, targetTemplates);
                    continue;
                }
                if (!stacksMatch(stack, demand.template())) {
                    continue;
                }

                int amount = Math.min(stack.getCount(), demand.remaining());
                int moved = moveSlotAmountIntoShulkers(handler, source.id, demand.template(), amount, targetTemplates);
                demand.decrease(moved);

                int remainingAmount = amount - moved;
                if (remainingAmount > 0) {
                    moved = moveFromContainerToPlayer(handler, source.id, demand.template(), remainingAmount);
                    demand.decrease(moved);
                }
            }
        }
    }

    private int moveSlotAmountIntoShulkers(ScreenHandler handler,
                                           int sourceSlotId,
                                           ItemStack template,
                                           int amount,
                                           List<ItemStack> targetTemplates) {
        Slot source = handler.getSlot(sourceSlotId);
        if (amount <= 0 || !source.hasStack() || isShulkerBox(source.getStack()) || !stacksMatch(source.getStack(), template)) {
            return 0;
        }

        int amountToPack = Math.min(amount, source.getStack().getCount());
        if (amountToPack < source.getStack().getCount() && hasQuickShulkerBundlingConflict(template)) {
            return moveQuickShulkerConflictAmount(
                    handler,
                    sourceSlotId,
                    template,
                    amountToPack,
                    targetTemplates,
                    true
            );
        }

        clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        while (!handler.getCursorStack().isEmpty() && handler.getCursorStack().getCount() > amountToPack) {
            int before = handler.getCursorStack().getCount();
            // 超出需求的部分立即放回原容器槽，不借用箱子槽位临时存无关物品。
            clickSlot(handler, sourceSlotId, 1, SlotActionType.PICKUP);
            int after = handler.getCursorStack().isEmpty() ? 0 : handler.getCursorStack().getCount();
            if (after >= before) {
                break;
            }
        }

        int moved = packCursorIntoShulkers(handler, targetTemplates);

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        }

        return moved;
    }

    private int moveFromContainerToPlayer(ScreenHandler handler, int sourceSlotId, ItemStack template, int amount) {
        if (amount <= 0) {
            return 0;
        }

        Slot source = handler.getSlot(sourceSlotId);
        if (!source.hasStack() || !stacksMatch(source.getStack(), template)) {
            return 0;
        }

        ItemStack sourceTemplate = source.getStack().copy();
        sourceTemplate.setCount(1);
        int sourceCount = source.getStack().getCount();
        int moveAmount = Math.min(amount, sourceCount);
        if (!hasPlayerCapacity(handler, sourceTemplate, moveAmount)) {
            return 0;
        }

        if (moveAmount == sourceCount) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.QUICK_MOVE);
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

        clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        int deposited = depositCursorToPlayer(handler, sourceTemplate, moveAmount);

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        }

        return deposited;
    }

    private int moveQuickShulkerConflictAmount(ScreenHandler handler,
                                                int sourceSlotId,
                                                ItemStack template,
                                                int amount,
                                                List<ItemStack> targetTemplates,
                                                boolean intoShulkers) {
        if (!handler.getCursorStack().isEmpty()) {
            return 0;
        }

        Slot source = handler.getSlot(sourceSlotId);
        if (!source.hasStack() || !stacksMatch(source.getStack(), template)) {
            return 0;
        }

        int targetAmount = Math.min(amount, source.getStack().getCount());
        if (targetAmount <= 0 || targetAmount >= source.getStack().getCount()) {
            return 0;
        }

        Slot buffer = findEmptyTemporarySplitSlot(handler, sourceSlotId, template);
        if (buffer == null) {
            // 没有安全缓冲槽时跳过半组末影箱，不能退回多余数量时触发 Quick Shulker 装入自身。
            return 0;
        }

        int moved = 0;
        while (moved < targetAmount && source.hasStack() && handler.getCursorStack().isEmpty()) {
            int remaining = targetAmount - moved;
            if (source.getStack().getCount() <= remaining) {
                clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
            } else {
                // 空光标右键数量大于 1 的末影箱堆只会原版对半拆分，不会触发 Quick Shulker。
                clickSlot(handler, sourceSlotId, 1, SlotActionType.PICKUP);
                if (handler.getCursorStack().isEmpty() || !stacksMatch(handler.getCursorStack(), template)) {
                    break;
                }
                if (handler.getCursorStack().getCount() > remaining) {
                    clickSlot(handler, buffer.id, 0, SlotActionType.PICKUP);
                    if (!handler.getCursorStack().isEmpty()) {
                        break;
                    }
                    continue;
                }
            }

            if (handler.getCursorStack().isEmpty() || !stacksMatch(handler.getCursorStack(), template)) {
                break;
            }

            int before = handler.getCursorStack().getCount();
            int deposited = intoShulkers
                    ? packCursorIntoShulkers(handler, targetTemplates)
                    : depositCursorToPlayerWithLeftClicks(handler, template, buffer.id);
            moved += Math.min(before, Math.max(0, deposited));

            if (!handler.getCursorStack().isEmpty()) {
                clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
                break;
            }
        }

        restoreTemporarySplitBuffer(handler, sourceSlotId, buffer.id);
        return moved;
    }

    private int depositCursorToPlayerWithLeftClicks(ScreenHandler handler,
                                                     ItemStack template,
                                                     int excludedSlotId) {
        int deposited = 0;
        while (!handler.getCursorStack().isEmpty()) {
            Slot target = findPlayerDepositSlot(handler, template, excludedSlotId);
            if (target == null) {
                break;
            }

            int before = handler.getCursorStack().getCount();
            clickSlot(handler, target.id, 0, SlotActionType.PICKUP);
            int after = handler.getCursorStack().isEmpty() ? 0 : handler.getCursorStack().getCount();
            if (after >= before) {
                break;
            }
            deposited += before - after;
        }
        return deposited;
    }

    private void restoreTemporarySplitBuffer(ScreenHandler handler, int sourceSlotId, int bufferSlotId) {
        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        }
        Slot buffer = handler.getSlot(bufferSlotId);
        if (!handler.getCursorStack().isEmpty() || !buffer.hasStack()) {
            return;
        }

        clickSlot(handler, bufferSlotId, 0, SlotActionType.PICKUP);
        clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, bufferSlotId, 0, SlotActionType.PICKUP);
        }
    }

    private int depositCursorToPlayer(ScreenHandler handler, ItemStack template, int amount) {
        int deposited = 0;

        while (deposited < amount && !handler.getCursorStack().isEmpty()) {
            Slot target = findPlayerDepositSlot(handler, template);
            if (target == null) {
                break;
            }

            int before = handler.getCursorStack().getCount();
            clickSlot(handler, target.id, 1, SlotActionType.PICKUP);
            int after = handler.getCursorStack().isEmpty() ? 0 : handler.getCursorStack().getCount();
            if (after >= before) {
                break;
            }
            deposited += before - after;
        }

        return deposited;
    }

    private void moveWholeCleanShulkerIfUseful(ScreenHandler handler,
                                               Slot source,
                                               List<Demand> demands,
                                               List<ItemStack> targetTemplates) {
        WholeShulkerCandidate bestCandidate = findBestWholeShulkerCandidate(handler, demands, targetTemplates);
        if (bestCandidate == null || bestCandidate.slot().id != source.id) {
            return;
        }

        ItemStack shulker = source.getStack();
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

        clickSlot(handler, source.id, 0, SlotActionType.QUICK_MOVE);
        for (StoredCount content : contents) {
            content.demand().decrease(content.count());
        }
    }

    private void packPlayerTargetMaterialsIntoShulkers(ScreenHandler handler,
                                                       List<PackDemand> packDemands,
                                                       List<ItemStack> targetTemplates) {
        if (!handler.getCursorStack().isEmpty()) {
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
                if (!source.hasStack() || isShulkerBox(source.getStack()) || !stacksMatch(source.getStack(), demand.template())) {
                    continue;
                }

                int amount = Math.min(source.getStack().getCount(), demand.remaining());
                int moved = moveSlotAmountIntoShulkers(handler, source.id, demand.template(), amount, targetTemplates);
                demand.decrease(moved);
            }
        }
    }

    private int packCursorIntoShulkers(ScreenHandler handler, List<ItemStack> targetTemplates) {
        int moved = 0;

        while (!handler.getCursorStack().isEmpty()) {
            Slot shulkerSlot = findDestinationShulkerSlot(handler, handler.getCursorStack(), targetTemplates);
            if (shulkerSlot == null) {
                break;
            }

            ItemStack beforeStack = handler.getCursorStack().copy();
            int before = handler.getCursorStack().getCount();
            // 右键潜影盒槽位，让 Quick Shulker 的服务端逻辑负责真实写入。
            clickSlot(handler, shulkerSlot.id, 1, SlotActionType.PICKUP);
            if (!handler.getCursorStack().isEmpty() && !stacksExactlyMatch(handler.getCursorStack(), beforeStack)) {
                clickSlot(handler, shulkerSlot.id, 0, SlotActionType.PICKUP);
                break;
            }
            int after = handler.getCursorStack().isEmpty() ? 0 : handler.getCursorStack().getCount();
            if (after < before) {
                moved += before - after;
                continue;
            }
            break;
        }

        return moved;
    }

    private Slot findDestinationShulkerSlot(ScreenHandler handler, ItemStack insertStack, List<ItemStack> targetTemplates) {
        DestinationShulkerCandidate bestCandidate = null;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            DestinationShulkerCandidate candidate = createDestinationShulkerCandidate(slot, insertStack, targetTemplates);
            if (candidate == null) {
                continue;
            }
            if (bestCandidate == null || candidate.isBetterThan(bestCandidate)) {
                bestCandidate = candidate;
            }
        }

        return bestCandidate != null ? bestCandidate.slot() : null;
    }

    private boolean shouldUseQuickShulker() {
        if (!QuickCraftConfigs.isAutoCollectMaterialsWithQuickShulkerEnabled()
                || !FabricLoader.getInstance().isModLoaded("quickshulker")) {
            return false;
        }

        try {
            return ClientPlayNetworking.canSend(QUICK_SHULKER_BUNDLE_PACKET);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isUsableDestinationShulker(ItemStack stack, List<ItemStack> targetTemplates) {
        return isShulkerBox(stack) && containsOnlyTargetMaterials(stack, targetTemplates);
    }

    private DestinationShulkerCandidate createDestinationShulkerCandidate(Slot slot,
                                                                          ItemStack insertStack,
                                                                          List<ItemStack> targetTemplates) {
        if (!slot.hasStack() || !isShulkerBox(slot.getStack())) {
            return null;
        }

        ItemStack shulker = slot.getStack();
        int totalCapacity = getShulkerCapacityFor(shulker, insertStack);
        if (totalCapacity <= 0) {
            return null;
        }

        return new DestinationShulkerCandidate(
                slot,
                containsStoredMaterial(shulker, insertStack),
                containsAnyTargetMaterial(shulker, targetTemplates),
                getShulkerMatchingCapacity(shulker, insertStack),
                isUsableDestinationShulker(shulker, targetTemplates),
                totalCapacity
        );
    }

    private boolean containsOnlyTargetMaterials(ItemStack shulker, List<ItemStack> targetTemplates) {
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (!containsTarget(targetTemplates, stored)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAnyTargetMaterial(ItemStack shulker, List<ItemStack> targetTemplates) {
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (containsTarget(targetTemplates, stored)) {
                return true;
            }
        }
        return false;
    }

    private List<StoredCount> getStoredTargetCounts(ItemStack shulker, List<Demand> demands) {
        List<StoredCount> counts = new ArrayList<>();
        for (ItemStack stored : getStoredStacks(shulker)) {
            Demand demand = findDemand(demands, stored);
            if (demand == null) {
                continue;
            }

            StoredCount count = findStoredCount(counts, demand);
            if (count == null) {
                counts.add(new StoredCount(demand, stored.getCount()));
            } else {
                count.add(stored.getCount());
            }
        }
        return counts;
    }

    private WholeShulkerCandidate findBestWholeShulkerCandidate(ScreenHandler handler,
                                                                List<Demand> demands,
                                                                List<ItemStack> targetTemplates) {
        WholeShulkerCandidate bestCandidate = null;
        for (Slot slot : getContainerSlots(handler)) {
            WholeShulkerCandidate candidate = createWholeShulkerCandidate(handler, slot, demands, targetTemplates);
            if (candidate == null) {
                continue;
            }
            if (bestCandidate == null || candidate.isBetterThan(bestCandidate)) {
                bestCandidate = candidate;
            }
        }

        return bestCandidate;
    }

    private WholeShulkerCandidate createWholeShulkerCandidate(ScreenHandler handler,
                                                              Slot source,
                                                              List<Demand> demands,
                                                              List<ItemStack> targetTemplates) {
        if (!source.hasStack() || !isShulkerBox(source.getStack())) {
            return null;
        }

        ItemStack shulker = source.getStack();
        if (!containsOnlyTargetMaterials(shulker, targetTemplates) || !hasPlayerCapacity(handler, shulker, shulker.getCount())) {
            return null;
        }

        List<StoredCount> contents = getStoredTargetCounts(shulker, demands);
        if (contents.isEmpty()) {
            return null;
        }

        int contribution = 0;
        for (StoredCount content : contents) {
            if (content.count() > content.demand().remaining()) {
                return null;
            }
            contribution += content.count();
        }

        return new WholeShulkerCandidate(source, contribution, contents.size());
    }

    private boolean containsStoredMaterial(ItemStack shulker, ItemStack template) {
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (stacksMatch(stored, template)) {
                return true;
            }
        }
        return false;
    }

    private int getShulkerMatchingCapacity(ItemStack shulker, ItemStack insertStack) {
        int capacity = 0;
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (stacksExactlyMatch(stored, insertStack)) {
                capacity += Math.max(0, stored.getMaxCount() - stored.getCount());
            }
        }
        return capacity;
    }

    private int getShulkerCapacityFor(ItemStack shulker, ItemStack insertStack) {
        if (!isShulkerBox(shulker) || isShulkerBox(insertStack)) {
            return 0;
        }

        int usedSlots = 0;
        int capacity = 0;
        for (ItemStack stored : getStoredStacks(shulker)) {
            usedSlots++;
            if (stacksExactlyMatch(stored, insertStack)) {
                capacity += Math.max(0, stored.getMaxCount() - stored.getCount());
            }
        }

        int emptySlots = Math.max(0, VANILLA_SHULKER_SLOTS - usedSlots);
        capacity += emptySlots * insertStack.getMaxCount();
        return capacity;
    }

    private int countAvailableInPlayerInventory(PlayerInventory inventory, ItemStack template) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
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

    private int countStoredInPlayerShulkers(PlayerInventory inventory, ItemStack template) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!isShulkerBox(stack) || !hasStoredItems(stack)) {
                continue;
            }

            for (ItemStack stored : getStoredStacks(stack)) {
                if (stacksMatch(stored, template)) {
                    count += stored.getCount();
                }
            }
        }
        return count;
    }

    private List<ItemStack> getStoredStacks(ItemStack shulker) {
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : container.iterateNonEmpty()) {
            stacks.add(stack);
        }
        return stacks;
    }

    private boolean hasStoredItems(ItemStack shulker) {
        return getStoredStacks(shulker).isEmpty() == false;
    }

    private Slot findPlayerDepositSlot(ScreenHandler handler, ItemStack template) {
        return findPlayerDepositSlot(handler, template, -1);
    }

    private Slot findPlayerDepositSlot(ScreenHandler handler, ItemStack template, int excludedSlotId) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.id == excludedSlotId || !slot.hasStack() || !slot.canInsert(template)) {
                continue;
            }
            if (stacksExactlyMatch(slot.getStack(), template) && slot.getStack().getCount() < slot.getStack().getMaxCount()) {
                return slot;
            }
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.id != excludedSlotId && !slot.hasStack() && slot.canInsert(template)) {
                return slot;
            }
        }

        return null;
    }

    private Slot findEmptyTemporarySplitSlot(ScreenHandler handler, int sourceSlotId, ItemStack template) {
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.id != sourceSlotId && !slot.hasStack() && slot.canInsert(template)) {
                return slot;
            }
        }
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.id != sourceSlotId && !slot.hasStack() && slot.canInsert(template)) {
                return slot;
            }
        }
        return null;
    }

    private boolean hasPlayerCapacity(ScreenHandler handler, ItemStack template, int amount) {
        return getPlayerCapacity(handler, template, amount) >= amount;
    }

    private int getPlayerCapacity(ScreenHandler handler, ItemStack template, int maxAmount) {
        int capacity = 0;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.canInsert(template)) {
                continue;
            }
            if (!slot.hasStack()) {
                capacity += template.getMaxCount();
            } else if (stacksExactlyMatch(slot.getStack(), template)) {
                capacity += Math.max(0, slot.getStack().getMaxCount() - slot.getStack().getCount());
            }
            if (capacity >= maxAmount) {
                return maxAmount;
            }
        }
        return capacity;
    }

    private List<Slot> getContainerSlots(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isVisibleSlot(slot)
                    && !isPlayerStorageSlot(slot)
                    && !QuickContainerLock.isLockedSlot(handler, slot)) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator
                .comparingInt((Slot slot) -> slot.y)
                .thenComparingInt(slot -> slot.x)
                .thenComparingInt(slot -> slot.id));
        return slots;
    }

    private List<Slot> getPlayerStorageSlots(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isVisibleSlot(slot)
                    && isPlayerStorageSlot(slot)
                    && !QuickContainerLock.isLockedSlot(handler, slot)) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator
                .comparingInt((Slot slot) -> slot.getIndex() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getIndex)
                .thenComparingInt(slot -> slot.id));
        return slots;
    }

    private Demand findDemand(List<Demand> demands, ItemStack stack) {
        for (Demand demand : demands) {
            if (stacksMatch(stack, demand.template())) {
                return demand;
            }
        }
        return null;
    }

    private boolean containsTarget(List<ItemStack> targetTemplates, ItemStack stack) {
        for (ItemStack target : targetTemplates) {
            if (stacksMatch(stack, target)) {
                return true;
            }
        }
        return false;
    }

    private StoredCount findStoredCount(List<StoredCount> counts, Demand demand) {
        for (StoredCount count : counts) {
            if (count.demand() == demand) {
                return count;
            }
        }
        return null;
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean hasQuickShulkerBundlingConflict(ItemStack stack) {
        return FabricLoader.getInstance().isModLoaded("quickshulker") && stack.isOf(Items.ENDER_CHEST);
    }

    private boolean stacksMatch(ItemStack a, ItemStack b) {
        // Litematica 的材料表按 ItemType(stack, true, false) 统计，这里同样只按物品类型匹配。
        return !a.isEmpty() && !b.isEmpty() && ItemStack.areItemsEqual(a, b);
    }

    private boolean stacksExactlyMatch(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.areItemsAndComponentsEqual(a, b);
    }

    private boolean isPlayerStorageSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory
                && slot.getIndex() >= 0
                && slot.getIndex() < 36;
    }

    private boolean isVisibleSlot(Slot slot) {
        return slot.isEnabled() && slot.x >= 0 && slot.y >= 0;
    }

    private void clickSlot(ScreenHandler handler, int slotId, int button, SlotActionType actionType) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private void closeCurrentScreen(MinecraftClient client) {
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
        if (client.currentScreen instanceof HandledScreen<?>) {
            client.setScreen(null);
        }
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

    private static final class Demand {
        private final ItemStack template;
        private int missing;
        private int remaining;
        private int collected;

        private Demand(ItemStack template, int missing) {
            this.template = template;
            this.missing = missing;
        }

        private ItemStack template() {
            return template;
        }

        private int missing() {
            return missing;
        }

        private int remaining() {
            return remaining;
        }

        private int collected() {
            return collected;
        }

        private void addMissing(int count) {
            this.missing += count;
        }

        private void setRemaining(int remaining) {
            this.remaining = remaining;
        }

        private void decrease(int count) {
            int moved = Math.min(this.remaining, Math.max(0, count));
            this.remaining -= moved;
            this.collected += moved;
        }
    }

    private static final class StoredCount {
        private final Demand demand;
        private int count;

        private StoredCount(Demand demand, int count) {
            this.demand = demand;
            this.count = count;
        }

        private Demand demand() {
            return demand;
        }

        private int count() {
            return count;
        }

        private void add(int count) {
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

    private record WholeShulkerCandidate(Slot slot, int contribution, int matchedDemandTypes) {
        private boolean isBetterThan(WholeShulkerCandidate other) {
            return contribution > other.contribution
                    || (contribution == other.contribution && matchedDemandTypes > other.matchedDemandTypes)
                    || (contribution == other.contribution
                    && matchedDemandTypes == other.matchedDemandTypes
                    && slot.id < other.slot.id);
        }
    }

    private record DestinationShulkerCandidate(Slot slot,
                                               boolean hasMatchingMaterial,
                                               boolean hasStoredTargetMaterial,
                                               int matchingCapacity,
                                               boolean targetOnly,
                                               int totalCapacity) {
        private boolean isBetterThan(DestinationShulkerCandidate other) {
            // 先续装同类，再复用已经承担本次材料任务的盒子，最后才启用空盒，避免材料散落。
            return compareTrueFirst(hasMatchingMaterial, other.hasMatchingMaterial)
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && compareTrueFirst(hasStoredTargetMaterial, other.hasStoredTargetMaterial))
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && hasStoredTargetMaterial == other.hasStoredTargetMaterial
                    && matchingCapacity > other.matchingCapacity)
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && hasStoredTargetMaterial == other.hasStoredTargetMaterial
                    && matchingCapacity == other.matchingCapacity
                    && compareTrueFirst(targetOnly, other.targetOnly))
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && hasStoredTargetMaterial == other.hasStoredTargetMaterial
                    && matchingCapacity == other.matchingCapacity
                    && targetOnly == other.targetOnly
                    && totalCapacity > other.totalCapacity)
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && hasStoredTargetMaterial == other.hasStoredTargetMaterial
                    && matchingCapacity == other.matchingCapacity
                    && targetOnly == other.targetOnly
                    && totalCapacity == other.totalCapacity
                    && slot.id < other.slot.id);
        }
    }

    private static boolean compareTrueFirst(boolean current, boolean other) {
        return current && !other;
    }

}
