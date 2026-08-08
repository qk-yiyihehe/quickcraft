package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 自动收集当前材料 HUD 缺失物品。
 * 所有搬运都通过原版槽位点击完成，避免直接改客户端背包造成幽灵物品。
 */
public final class QuickMaterialCollector implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int VANILLA_SHULKER_SLOTS = 27;
    // 缺失数量阈值余量默认值：0-10 +0，10-20 +1，20-50 +3，50-100 +5，100-500 +10，500+ +32。
    private static final int EXTRA_ALLOWANCE_LIMIT_10 = 10;
    private static final int EXTRA_ALLOWANCE_LIMIT_20 = 20;
    private static final int EXTRA_ALLOWANCE_LIMIT_50 = 50;
    private static final int EXTRA_ALLOWANCE_LIMIT_100 = 100;
    private static final int EXTRA_ALLOWANCE_LIMIT_500 = 500;
    private static final Identifier QUICK_SHULKER_BUNDLE_PACKET = Identifier.fromNamespaceAndPath("quickshulker", "quick_bundleheld_packet");

    private boolean lastUseDown;
    private boolean pendingOpen;
    private int pendingTicks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isAutoCollectMaterialsEnabled()) {
            lastUseDown = false;
            pendingOpen = false;
            pendingTicks = 0;
            return;
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(Minecraft client) {
        if (client.player == null || client.level == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.keyUse);
        if (useDown && !lastUseDown && client.gui.screen() == null && isLookingAtSupportedBlock(client)) {
            pendingOpen = true;
            pendingTicks = 0;
        }
        lastUseDown = useDown;
    }

    private void processPendingOpen(Minecraft client) {
        if (!pendingOpen) {
            return;
        }

        pendingTicks++;
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                pendingOpen = false;
                pendingTicks = 0;
            }
            return;
        }

        pendingOpen = false;
        pendingTicks = 0;
        if (client.player == null || client.gameMode == null || !isSupportedHandler(screen.getMenu())) {
            return;
        }

        if (!hasVisibleMaterialLists(client.player)) {
            return;
        }

        MaterialPlan plan = buildMaterialPlan(client.player);
        boolean useQuickShulker = shouldUseQuickShulker();

        if (useQuickShulker) {
            packPlayerTargetMaterialsIntoShulkers(screen, plan.packDemands(), plan.targetTemplates());
            if (!plan.demands().isEmpty()) {
                collectToShulkersOrPlayer(screen, plan.demands(), plan.targetTemplates());
            }
        } else if (!plan.demands().isEmpty()) {
            collectToPlayer(screen, plan.demands(), plan.targetTemplates());
        } else {
            closeCurrentScreen(client);
            return;
        }

        closeCurrentScreen(client);
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
            int remaining = Math.max(0, getTargetCollectCount(demand.missing()) - available);
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
            // 背包已有材料装盒也按材料表数量封顶，避免把同类材料整包吞进盒子。
            int desired = getTargetCollectCount(demand.missing());
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

    private void collectToPlayer(AbstractContainerScreen<?> screen, List<Demand> demands, List<ItemStack> targetTemplates) {
        AbstractContainerMenu handler = screen.getMenu();
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
                    moveWholeCleanShulkerIfUseful(screen, source, demands, targetTemplates);
                    continue;
                }
                if (!stacksMatch(stack, demand.template())) {
                    continue;
                }

                int amount = Math.min(stack.getCount(), demand.remaining());
                int moved = moveFromContainerToPlayer(screen, source.index, demand.template(), amount);
                demand.decrease(moved);
            }
        }
    }

    private void collectToShulkersOrPlayer(AbstractContainerScreen<?> screen, List<Demand> demands, List<ItemStack> targetTemplates) {
        AbstractContainerMenu handler = screen.getMenu();
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
                    moveWholeCleanShulkerIfUseful(screen, source, demands, targetTemplates);
                    continue;
                }
                if (!stacksMatch(stack, demand.template())) {
                    continue;
                }

                int amount = Math.min(stack.getCount(), demand.remaining());
                int moved = moveSlotAmountIntoShulkers(screen, source.index, demand.template(), amount, targetTemplates);
                demand.decrease(moved);

                int remainingAmount = amount - moved;
                if (remainingAmount > 0) {
                    moved = moveFromContainerToPlayer(screen, source.index, demand.template(), remainingAmount);
                    demand.decrease(moved);
                }
            }
        }
    }

    private int moveSlotAmountIntoShulkers(AbstractContainerScreen<?> screen,
                                           int sourceSlotId,
                                           ItemStack template,
                                           int amount,
                                           List<ItemStack> targetTemplates) {
        AbstractContainerMenu handler = screen.getMenu();
        Slot source = handler.getSlot(sourceSlotId);
        if (amount <= 0 || !source.hasItem() || isShulkerBox(source.getItem()) || !stacksMatch(source.getItem(), template)) {
            return 0;
        }

        int amountToPack = Math.min(amount, source.getItem().getCount());
        clickSlot(screen, sourceSlotId, 0, ContainerInput.PICKUP);
        while (!handler.getCarried().isEmpty() && handler.getCarried().getCount() > amountToPack) {
            int before = handler.getCarried().getCount();
            // 超出需求的部分立即放回原容器槽，不借用箱子槽位临时存无关物品。
            clickSlot(screen, sourceSlotId, 1, ContainerInput.PICKUP);
            int after = handler.getCarried().isEmpty() ? 0 : handler.getCarried().getCount();
            if (after >= before) {
                break;
            }
        }

        int moved = packCursorIntoShulkers(screen, targetTemplates);

        if (!handler.getCarried().isEmpty()) {
            clickSlot(screen, sourceSlotId, 0, ContainerInput.PICKUP);
        }

        return moved;
    }

    private int moveFromContainerToPlayer(AbstractContainerScreen<?> screen, int sourceSlotId, ItemStack template, int amount) {
        if (amount <= 0) {
            return 0;
        }

        AbstractContainerMenu handler = screen.getMenu();
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
            clickSlot(screen, sourceSlotId, 0, ContainerInput.QUICK_MOVE);
            return moveAmount;
        }

        clickSlot(screen, sourceSlotId, 0, ContainerInput.PICKUP);
        int deposited = depositCursorToPlayer(screen, sourceTemplate, moveAmount);

        if (!handler.getCarried().isEmpty()) {
            clickSlot(screen, sourceSlotId, 0, ContainerInput.PICKUP);
        }

        return deposited;
    }

    private int depositCursorToPlayer(AbstractContainerScreen<?> screen, ItemStack template, int amount) {
        AbstractContainerMenu handler = screen.getMenu();
        int deposited = 0;

        while (deposited < amount && !handler.getCarried().isEmpty()) {
            Slot target = findPlayerDepositSlot(handler, template);
            if (target == null) {
                break;
            }

            int before = handler.getCarried().getCount();
            clickSlot(screen, target.index, 1, ContainerInput.PICKUP);
            int after = handler.getCarried().isEmpty() ? 0 : handler.getCarried().getCount();
            if (after >= before) {
                break;
            }
            deposited += before - after;
        }

        return deposited;
    }

    private void moveWholeCleanShulkerIfUseful(AbstractContainerScreen<?> screen,
                                               Slot source,
                                               List<Demand> demands,
                                               List<ItemStack> targetTemplates) {
        WholeShulkerCandidate bestCandidate = findBestWholeShulkerCandidate(screen.getMenu(), demands, targetTemplates);
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
        if (!hasPlayerCapacity(screen.getMenu(), shulker, shulker.getCount())) {
            return;
        }

        clickSlot(screen, source.index, 0, ContainerInput.QUICK_MOVE);
        for (StoredCount content : contents) {
            content.demand().decrease(content.count());
        }
    }

    private void packPlayerTargetMaterialsIntoShulkers(AbstractContainerScreen<?> screen,
                                                       List<PackDemand> packDemands,
                                                       List<ItemStack> targetTemplates) {
        if (!screen.getMenu().getCarried().isEmpty()) {
            return;
        }

        for (PackDemand demand : packDemands) {
            if (demand.remaining() <= 0) {
                continue;
            }

            for (Slot source : getPlayerStorageSlots(screen.getMenu())) {
                if (demand.remaining() <= 0) {
                    break;
                }
                if (!source.hasItem() || isShulkerBox(source.getItem()) || !stacksMatch(source.getItem(), demand.template())) {
                    continue;
                }

                int amount = Math.min(source.getItem().getCount(), demand.remaining());
                int moved = moveSlotAmountIntoShulkers(screen, source.index, demand.template(), amount, targetTemplates);
                demand.decrease(moved);
            }
        }
    }

    private int packCursorIntoShulkers(AbstractContainerScreen<?> screen, List<ItemStack> targetTemplates) {
        AbstractContainerMenu handler = screen.getMenu();
        int moved = 0;

        while (!handler.getCarried().isEmpty()) {
            Slot shulkerSlot = findDestinationShulkerSlot(handler, handler.getCarried(), targetTemplates);
            if (shulkerSlot == null) {
                break;
            }

            ItemStack beforeStack = handler.getCarried().copy();
            int before = handler.getCarried().getCount();
            // 右键潜影盒槽位，让 Quick Shulker 的服务端逻辑负责真实写入。
            clickSlot(screen, shulkerSlot.index, 1, ContainerInput.PICKUP);
            if (!handler.getCarried().isEmpty() && !stacksExactlyMatch(handler.getCarried(), beforeStack)) {
                clickSlot(screen, shulkerSlot.index, 0, ContainerInput.PICKUP);
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

    private Slot findDestinationShulkerSlot(AbstractContainerMenu handler, ItemStack insertStack, List<ItemStack> targetTemplates) {
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
        if (!slot.hasItem() || !isShulkerBox(slot.getItem())) {
            return null;
        }

        ItemStack shulker = slot.getItem();
        int totalCapacity = getShulkerCapacityFor(shulker, insertStack);
        if (totalCapacity <= 0) {
            return null;
        }

        return new DestinationShulkerCandidate(
                slot,
                containsStoredMaterial(shulker, insertStack),
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

    private WholeShulkerCandidate findBestWholeShulkerCandidate(AbstractContainerMenu handler,
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

    private WholeShulkerCandidate createWholeShulkerCandidate(AbstractContainerMenu handler,
                                                              Slot source,
                                                              List<Demand> demands,
                                                              List<ItemStack> targetTemplates) {
        if (!source.hasItem() || !isShulkerBox(source.getItem())) {
            return null;
        }

        ItemStack shulker = source.getItem();
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
                capacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
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
                capacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
            }
        }

        int emptySlots = Math.max(0, VANILLA_SHULKER_SLOTS - usedSlots);
        capacity += emptySlots * insertStack.getMaxStackSize();
        return capacity;
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

    private int countStoredInPlayerShulkers(Inventory inventory, ItemStack template) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
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
        ItemContainerContents container = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : container.nonEmptyItemCopyStream().toList()) {
            stacks.add(stack);
        }
        return stacks;
    }

    private boolean hasStoredItems(ItemStack shulker) {
        return getStoredStacks(shulker).isEmpty() == false;
    }

    private Slot findPlayerDepositSlot(AbstractContainerMenu handler, ItemStack template) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasItem() || !slot.mayPlace(template)) {
                continue;
            }
            if (stacksExactlyMatch(slot.getItem(), template) && slot.getItem().getCount() < slot.getItem().getMaxStackSize()) {
                return slot;
            }
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasItem() && slot.mayPlace(template)) {
                return slot;
            }
        }

        return null;
    }

    private boolean hasPlayerCapacity(AbstractContainerMenu handler, ItemStack template, int amount) {
        return getPlayerCapacity(handler, template, amount) >= amount;
    }

    private int getPlayerCapacity(AbstractContainerMenu handler, ItemStack template, int maxAmount) {
        int capacity = 0;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.mayPlace(template)) {
                continue;
            }
            if (!slot.hasItem()) {
                capacity += template.getMaxStackSize();
            } else if (stacksExactlyMatch(slot.getItem(), template)) {
                capacity += Math.max(0, slot.getItem().getMaxStackSize() - slot.getItem().getCount());
            }
            if (capacity >= maxAmount) {
                return maxAmount;
            }
        }
        return capacity;
    }

    private List<Slot> getContainerSlots(AbstractContainerMenu handler) {
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
                .thenComparingInt(slot -> slot.index));
        return slots;
    }

    private List<Slot> getPlayerStorageSlots(AbstractContainerMenu handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isVisibleSlot(slot)
                    && isPlayerStorageSlot(slot)
                    && !QuickContainerLock.isLockedSlot(handler, slot)) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator
                .comparingInt((Slot slot) -> slot.getContainerSlot() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getContainerSlot)
                .thenComparingInt(slot -> slot.index));
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

    private boolean stacksMatch(ItemStack a, ItemStack b) {
        // Litematica 的材料表按 ItemType(stack, true, false) 统计，这里同样只按物品类型匹配。
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItem(a, b);
    }

    private boolean stacksExactlyMatch(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
    }

    private boolean isPlayerStorageSlot(Slot slot) {
        return slot.container instanceof Inventory
                && slot.getContainerSlot() >= 0
                && slot.getContainerSlot() < 36;
    }

    private boolean isVisibleSlot(Slot slot) {
        return slot.isActive() && slot.x >= 0 && slot.y >= 0;
    }

    private void clickSlot(AbstractContainerScreen<?> screen, int slotId, int button, ContainerInput actionType) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
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
    }

    public record MaterialRequest(ItemStack stack, int count) {
    }

    private record MaterialPlan(List<Demand> demands, List<ItemStack> targetTemplates, List<PackDemand> packDemands) {
    }

    private static final class Demand {
        private final ItemStack template;
        private int missing;
        private int remaining;

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

        private void addMissing(int count) {
            this.missing += count;
        }

        private void setRemaining(int remaining) {
            this.remaining = remaining;
        }

        private void decrease(int count) {
            this.remaining = Math.max(0, this.remaining - count);
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
                    && slot.index < other.slot.index);
        }
    }

    private record DestinationShulkerCandidate(Slot slot,
                                               boolean hasMatchingMaterial,
                                               int matchingCapacity,
                                               boolean targetOnly,
                                               int totalCapacity) {
        private boolean isBetterThan(DestinationShulkerCandidate other) {
            // 先尽量续装已有同类材料的盒子，没有同类时再优先纯材料盒，最后才回退到混装盒。
            return compareTrueFirst(hasMatchingMaterial, other.hasMatchingMaterial)
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && matchingCapacity > other.matchingCapacity)
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && matchingCapacity == other.matchingCapacity
                    && compareTrueFirst(targetOnly, other.targetOnly))
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && matchingCapacity == other.matchingCapacity
                    && targetOnly == other.targetOnly
                    && totalCapacity > other.totalCapacity)
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && matchingCapacity == other.matchingCapacity
                    && targetOnly == other.targetOnly
                    && totalCapacity == other.totalCapacity
                    && slot.index < other.slot.index);
        }
    }

    private static boolean compareTrueFirst(boolean current, boolean other) {
        return current && !other;
    }

}
