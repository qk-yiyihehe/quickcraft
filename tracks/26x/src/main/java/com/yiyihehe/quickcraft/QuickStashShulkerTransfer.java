package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.render.QuickCrossBoxSortStatus;
import static com.yiyihehe.quickcraft.QuickContainerCopySources.*;

import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.portablestorage.QuickPortableStorageTransfer;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 回存只规划匹配物和目的地；拆量、批量取货及装盒沿用容器填充的实现。 */
public final class QuickStashShulkerTransfer {
    private static final int OPEN_TIMEOUT_TICKS = 40;
    // 一批覆盖玩家的 36 个储物槽，避免单个客户端 Tick 被转移操作占满。
    private static final int MAX_STACKS_PER_BATCH = 36;
    private static final QuickContainerCopy TRANSFER = new QuickContainerCopy();
    private static Session session;

    private QuickStashShulkerTransfer() {
    }

    public static boolean isRunning() {
        return session != null;
    }

    private static boolean isEnabled() {
        return QuickCraftConfigs.ContainerTools.QUICK_STASH_WITH_QUICK_SHULKER.getBooleanValue()
                && QuickCraftConfigs.ModSupport.ENABLE_QUICK_SHULKER.getBooleanValue()
                && QuickShulkerConnection.canBundle() && QuickShulkerConnection.canOpen();
    }

    static boolean start(AbstractContainerScreen<?> screen, int inventorySlot, boolean closeAfter) {
        if (session != null) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client.player == null || client.level == null || client.gameMode == null
                || QuickClientScreenAccess.currentScreen(client) != screen || !screen.getMenu().getCarried().isEmpty()
                || QuickPortableStorageTransfer.isRunning()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || QuickMaterialCollector.shouldHideBackgroundHandledScreen()) {
            return false;
        }
        if (inventorySlot < 0) {
            inventorySlot = QuickContainerLock.getInventoryShulkerSlot(screen);
        }
        if (QuickContainerLock.isCurrentScreenLocked(screen)) {
            return true;
        }
        HitResult hit = client.hitResult;
        if (inventorySlot < 0 && !(hit instanceof BlockHitResult) && !(hit instanceof EntityHitResult)) {
            return false;
        }
        if (hit instanceof BlockHitResult blockHit) {
            hit = blockHit.withPosition(blockHit.getBlockPos().immutable());
        }
        session = new Session(client, screen.getMenu(), hit, inventorySlot, closeAfter);
        if (!QuickContainerToolSync.hasSyncedContents(screen.getMenu())) {
            session.stage = Stage.WAIT_INITIAL;
        }
        return true;
    }

    static void onClientTick(Minecraft client) {
        if (session == null) {
            return;
        }
        if (!isEnabled() || client.player == null || client.level != session.world
                || client.gameMode == null || !targetStillExists(client)) {
            stop(client, false, null);
            return;
        }
        if (session.stage == Stage.REOPEN) {
            reopenTarget(client);
            return;
        }
        if (session.stage == Stage.WAIT_INITIAL && !(QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() == session.ownedHandler)) {
            stop(client, false, null);
            return;
        }
        if (session.stage == Stage.WAIT_INITIAL || session.stage == Stage.WAIT_SOURCE || session.stage == Stage.WAIT_TARGET) {
            if (++session.waitTicks > OPEN_TIMEOUT_TICKS) {
                stop(client, false, null);
                return;
            }
            if (!advanceSyncedMenu(client)) {
                return;
            }
        }
        if (!(QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != session.ownedHandler) {
            stop(client, false, null);
            return;
        }
        if (!session.ownedHandler.getCarried().isEmpty()) {
            stop(client, false, "quickcraft.message.quick_stash.cursor_blocked");
            return;
        }
        if (session.stage == Stage.TARGET) {
            processTarget(client, screen);
        } else {
            extractSource(client, screen);
        }
    }

    private static boolean advanceSyncedMenu(Minecraft client) {
        var current = QuickClientScreenAccess.currentScreen(client);
        if (current == null) {
            return false;
        }
        if (!(current instanceof AbstractContainerScreen<?> screen) || screen.getMenu() != client.player.containerMenu) {
            stop(client, false, null);
            return false;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!QuickContainerToolSync.hasSyncedContents(handler)) {
            return false;
        }
        if (session.stage == Stage.WAIT_INITIAL && handler == session.ownedHandler) {
            session.stage = Stage.TARGET;
        } else if (handler == session.ownedHandler) {
            return false;
        } else if (session.stage == Stage.WAIT_SOURCE && handler instanceof ShulkerBoxMenu) {
            QuickContainerLock.bindInventoryShulkerScreen(screen, session.sourcePlayerIndex);
            session.stage = Stage.SOURCE;
        } else if (session.stage == Stage.WAIT_TARGET
                && handler.getClass() == session.originalHandler.getClass()
                && handler.slots.size() == session.originalHandler.slots.size()) {
            QuickContainerLock.bindSameContainerScreen(handler, session.originalHandler);
            session.stage = Stage.TARGET;
        } else {
            stop(client, false, null);
            return false;
        }
        session.ownedHandler = handler;
        session.waitTicks = 0;
        return true;
    }

    private static void processTarget(Minecraft client, AbstractContainerScreen<?> screen) {
        if (QuickContainerLock.isCurrentScreenLocked(screen)) {
            stop(client, false, null);
            return;
        }
        AbstractContainerMenu handler = session.ownedHandler;
        rememberAcceptedItems(handler);
        for (int processed = 0; processed < MAX_STACKS_PER_BATCH; processed++) {
            if (!depositOnePlayerStack(handler, client)) {
                break;
            }
            if (session == null || processed == MAX_STACKS_PER_BATCH - 1) {
                return;
            }
        }
        if (!handler.getCarried().isEmpty()) {
            stop(client, false, "quickcraft.message.quick_stash.cursor_blocked");
            return;
        }
        session.demands = remainingCapacity(handler);
        SourceShulker source = findSource(handler);
        if (source == null) {
            boolean restored = restoreTemporaryStack(handler, client);
            stop(client, restored && session.closeAfter, null);
            return;
        }
        boolean hasSpace = hasExtractionSpace(handler, handler.getSlot(source.slotId()).getItem());
        if (!hasSpace) {
            if (!makeWorkingSlot(handler, source.playerIndex(), client)) {
                stop(client, false, null);
                return;
            }
            // 借走空格后重新分配容量，不能把周转格同时算作货物的最终落点。
            session.demands = remainingCapacity(handler);
            source = findSource(handler);
            if (source == null) {
                stop(client, false, null);
                return;
            }
        }
        session.sourcePlayerIndex = source.playerIndex();
        session.stage = Stage.WAIT_SOURCE;
        session.waitTicks = 0;
        if (!QuickShulkerConnection.sendOpen(source.slotId())) {
            stop(client, false, null);
        }
    }

    private static void rememberAcceptedItems(AbstractContainerMenu handler) {
        for (int slotId : targetSlots(handler)) {
            ItemStack stack = handler.getSlot(slotId).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (!isShulkerBox(stack)) {
                addAccepted(stack);
            } else {
                for (ItemStack stored : getStoredStacksBySlot(stack)) {
                    if (!stored.isEmpty() && !isShulkerBox(stored)) {
                        addAccepted(stored);
                    }
                }
            }
        }
    }

    private static void addAccepted(ItemStack stack) {
        if (!matchesAccepted(stack)) {
            session.accepted.add(stack.copyWithCount(1));
        }
    }

    private static boolean matchesAccepted(ItemStack stack) {
        return session.accepted.stream().anyMatch(template -> ItemStack.isSameItemSameComponents(template, stack));
    }

    private static ItemStack pureContent(ItemStack shulker) {
        if (!isShulkerBox(shulker)) {
            return ItemStack.EMPTY;
        }
        ItemStack template = ItemStack.EMPTY;
        for (ItemStack stack : getStoredStacksBySlot(shulker)) {
            if (stack.isEmpty()) {
                continue;
            }
            if (!template.isEmpty() && !ItemStack.isSameItemSameComponents(template, stack)) {
                return ItemStack.EMPTY;
            }
            template = stack;
        }
        return template.isEmpty() ? ItemStack.EMPTY : template.copyWithCount(1);
    }

    private static List<Integer> targetSlots(AbstractContainerMenu handler) {
        return getContainerSlotIds(handler).stream()
                .filter(id -> id != session.temporarySlot && !QuickContainerLock.isLockedSlot(handler, id))
                .toList();
    }

    private static boolean depositOnePlayerStack(AbstractContainerMenu handler, Minecraft client) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot source = handler.getSlot(slotId);
            if (source.getContainerSlot() == session.targetInventorySlot || !source.hasItem() || !source.mayPickup(client.player)) {
                continue;
            }
            ItemStack stack = source.getItem();
            if (isShulkerBox(stack)) {
                ItemStack pure = pureContent(stack);
                if (!pure.isEmpty() && matchesAccepted(pure)
                        && moveToContainerSlots(handler, slotId, client)) {
                    return true;
                }
            } else if (matchesAccepted(stack)) {
                int before = stack.getCount();
                if (!QuickShulkerStash.packMatching(handler, slotId, targetSlots(handler), client)) {
                    stop(client, false, null);
                    return true;
                }
                if (!handler.getCarried().isEmpty()) {
                    return false;
                }
                boolean moved = source.getItem().getCount() < before;
                if (moveToContainerSlots(handler, slotId, client) || moved) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean moveToContainerSlots(AbstractContainerMenu handler, int sourceSlotId, Minecraft client) {
        Slot source = handler.getSlot(sourceSlotId);
        if (!source.hasItem()) {
            return false;
        }
        int before = source.getItem().getCount();
        List<Integer> slots = new ArrayList<>(targetSlots(handler));
        slots.sort(java.util.Comparator.comparing(id -> !handler.getSlot(id).hasItem()));
        for (int slotId : slots) {
            int capacity = capacity(handler.getSlot(slotId), source.getItem());
            if (capacity > 0) {
                TRANSFER.moveItemsToTargetSlot(handler, sourceSlotId, slotId, Math.min(capacity, source.getItem().getCount()), client);
            }
            if (!source.hasItem() || !handler.getCarried().isEmpty()) {
                break;
            }
        }
        return source.getItem().getCount() < before;
    }

    private static int capacity(Slot slot, ItemStack stack) {
        if (stack.isEmpty() || !slot.isActive() || !slot.mayPlace(stack)
                || slot.hasItem() && !ItemStack.isSameItemSameComponents(slot.getItem(), stack)) {
            return 0;
        }
        return Math.max(0, Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack))
                - (slot.hasItem() ? slot.getItem().getCount() : 0));
    }

    private static List<MissingDemand> remainingCapacity(AbstractContainerMenu handler) {
        List<MissingDemand> demands = new ArrayList<>();
        List<Integer> emptySlots = new ArrayList<>(targetSlots(handler).stream()
                .filter(id -> !handler.getSlot(id).hasItem()).toList());
        java.util.Map<Integer, Integer> shulkerFreeSlots = new java.util.HashMap<>();
        for (int slotId : targetSlots(handler)) {
            ItemStack shulker = handler.getSlot(slotId).getItem();
            if (isShulkerBox(shulker)) {
                int empty = 0;
                for (ItemStack stored : getStoredStacksBySlot(shulker)) {
                    if (stored.isEmpty()) {
                        empty++;
                    }
                }
                shulkerFreeSlots.put(slotId, empty);
            }
        }
        // 杂物盒的空槽只能分配一次，不能同时计入多种物品的取货容量。
        for (ItemStack template : session.accepted) {
            if (isShulkerBox(template)) {
                continue;
            }
            int available = 0;
            for (int playerId : getPlayerStorageSlotIds(handler)) {
                Slot playerSlot = handler.getSlot(playerId);
                if (playerSlot.getContainerSlot() == session.targetInventorySlot
                        || session.exhaustedSources.contains(playerSlot.getContainerSlot())
                        || !isShulkerBox(playerSlot.getItem())) {
                    continue;
                }
                for (ItemStack stored : getStoredStacksBySlot(playerSlot.getItem())) {
                    if (ItemStack.isSameItemSameComponents(stored, template)) {
                        available += stored.getCount();
                    }
                }
            }
            if (available == 0) {
                continue;
            }
            int capacity = 0;
            List<Integer> matchingShulkers = new ArrayList<>();
            for (int slotId : targetSlots(handler)) {
                Slot slot = handler.getSlot(slotId);
                if (slot.hasItem()) {
                    capacity += capacity(slot, template);
                }
                if (QuickShulkerStash.canReceive(handler, slotId, template, Minecraft.getInstance())) {
                    capacity += QuickShulkerStash.matchingPartialCapacity(slot.getItem(), template);
                    matchingShulkers.add(slotId);
                }
            }
            for (int slotId : matchingShulkers) {
                if (capacity >= available) {
                    break;
                }
                int maxCount = template.getMaxStackSize();
                int neededSlots = (available - capacity + maxCount - 1) / maxCount;
                int reserved = Math.min(shulkerFreeSlots.get(slotId), neededSlots);
                capacity += reserved * maxCount;
                shulkerFreeSlots.put(slotId, shulkerFreeSlots.get(slotId) - reserved);
            }
            // 空格只能分配给一种物品，避免多个缺口重复占用同一个周转目的地。
            var iterator = emptySlots.iterator();
            while (capacity < available && iterator.hasNext()) {
                int extra = capacity(handler.getSlot(iterator.next()), template);
                if (extra > 0) {
                    capacity += extra;
                    iterator.remove();
                }
            }
            if (capacity > 0) {
                demands.add(new MissingDemand(template, Math.min(capacity, available)));
            }
        }
        return demands;
    }

    private static SourceShulker findSource(AbstractContainerMenu handler) {
        return findSource(handler, -1);
    }

    private static SourceShulker findSource(AbstractContainerMenu handler, int excludedPlayerIndex) {
        SourceShulker fallback = null;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.getContainerSlot() != session.targetInventorySlot && slot.getContainerSlot() != excludedPlayerIndex
                    && !session.exhaustedSources.contains(slot.getContainerSlot())
                    && slot.hasItem() && slot.mayPickup(Minecraft.getInstance().player)
                    && slot.getItem().getCount() == 1 && isShulkerBox(slot.getItem())
                    && getSourceShulkerScore(slot.getItem(), session.demands).usefulItemCount() > 0) {
                SourceShulker source = new SourceShulker(slotId, slot.getContainerSlot());
                if (hasExtractionSpace(handler, slot.getItem())) {
                    return source;
                }
                if (fallback == null) {
                    fallback = source;
                }
            }
        }
        return fallback;
    }

    private static boolean hasExtractionSpace(AbstractContainerMenu handler, ItemStack source) {
        for (ItemStack stored : getStoredStacksBySlot(source)) {
            if (findDemandForStack(session.demands, stored) != null
                    && canStoreAnyStackInPlayerStorage(handler, stored)) {
                return true;
            }
        }
        return false;
    }

    private static boolean makeWorkingSlot(AbstractContainerMenu handler, int sourcePlayerIndex, Minecraft client) {
        int excludedShulkerSlot = getPlayerStorageSlotIds(handler).stream()
                .filter(id -> handler.getSlot(id).getContainerSlot() == session.targetInventorySlot).findFirst().orElse(-1);
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (TRANSFER.canUsePlayerStackToFreeQuickShulkerSpace(slot.getItem())
                    && TRANSFER.packSlotIntoQuickShulkerExcept(handler, slotId, excludedShulkerSlot, client)) {
                return true;
            }
        }
        if (session.temporarySlot >= 0) {
            return false;
        }
        for (int sourceId : getPlayerStorageSlotIds(handler)) {
            Slot source = handler.getSlot(sourceId);
            if (!source.hasItem() || !source.mayPickup(client.player)
                    || source.getContainerSlot() == sourcePlayerIndex || source.getContainerSlot() == session.targetInventorySlot
                    || !isShulkerBox(source.getItem())
                        && !TRANSFER.canUsePlayerStackToFreeQuickShulkerSpace(source.getItem())) {
                continue;
            }
            for (int targetId : targetSlots(handler)) {
                Slot target = handler.getSlot(targetId);
                if (!target.hasItem() && capacity(target, source.getItem()) >= source.getItem().getCount()) {
                    ItemStack saved = source.getItem().copy();
                    TRANSFER.moveItemsToTargetSlot(handler, sourceId, targetId, saved.getCount(), client);
                    if (!source.hasItem() && handler.getCarried().isEmpty()
                            && ItemStack.isSameItemSameComponents(saved, target.getItem())
                            && target.getItem().getCount() == saved.getCount()) {
                        session.temporarySlot = targetId;
                        session.temporaryStack = saved;
                        return true;
                    }
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean restoreTemporaryStack(AbstractContainerMenu handler, Minecraft client) {
        if (session.temporarySlot < 0) {
            return true;
        }
        Slot source = handler.getSlot(session.temporarySlot);
        if (!source.mayPickup(client.player) || QuickContainerLock.isLockedSlot(handler, source)
                || !ItemStack.isSameItemSameComponents(source.getItem(), session.temporaryStack)
                || source.getItem().getCount() != session.temporaryStack.getCount()) {
            return false;
        }
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot target = handler.getSlot(slotId);
            int capacity = capacity(target, source.getItem());
            if (target.getContainerSlot() != session.targetInventorySlot && capacity > 0) {
                TRANSFER.moveItemsToTargetSlot(handler, source.index, slotId, Math.min(capacity, source.getItem().getCount()), client);
                session.temporaryStack = source.getItem().copy();
            }
            if (!source.hasItem() && handler.getCarried().isEmpty()) {
                session.temporarySlot = -1;
                return true;
            }
        }
        return false;
    }

    private static void extractSource(Minecraft client, AbstractContainerScreen<?> screen) {
        int moved = 0;
        if (!QuickContainerLock.isCurrentScreenLocked(screen)) {
            var result = TRANSFER.moveMatchingItemsFromOpenShulker(
                    (ShulkerBoxMenu) session.ownedHandler, session.demands, client);
            moved = result.moved();
            session.demands = result.remainingDemands();
        }
        if (moved == 0) {
            session.exhaustedSources.add(session.sourcePlayerIndex);
        }
        if (!session.ownedHandler.getCarried().isEmpty()) {
            stop(client, false, "quickcraft.message.quick_stash.cursor_blocked");
            return;
        }
        openNextSourceOrReturn(client);
    }

    private static void openNextSourceOrReturn(Minecraft client) {
        SourceShulker next = findSource(session.ownedHandler, session.sourcePlayerIndex);
        if (next != null && hasExtractionSpace(session.ownedHandler, session.ownedHandler.getSlot(next.slotId()).getItem())) {
            session.sourcePlayerIndex = next.playerIndex();
            session.stage = Stage.WAIT_SOURCE;
            session.waitTicks = 0;
            if (!QuickShulkerConnection.sendOpen(next.slotId())) {
                stop(client, false, null);
            }
            return;
        }
        client.player.closeContainer();
        session.stage = Stage.REOPEN;
    }

    private static boolean targetStillExists(Minecraft client) {
        if (session.targetInventorySlot >= 0) {
            return isShulkerBox(client.player.getInventory().getItem(session.targetInventorySlot));
        }
        if (session.targetHit instanceof BlockHitResult hit) {
            return client.level.getBlockState(hit.getBlockPos()).getBlock() == session.targetBlock;
        }
        return session.targetHit instanceof EntityHitResult hit && !hit.getEntity().isRemoved();
    }

    private static void reopenTarget(Minecraft client) {
        if (QuickClientScreenAccess.currentScreen(client) != null) {
            stop(client, false, null);
            return;
        }
        boolean opened;
        if (session.targetInventorySlot >= 0) {
            int slotId = client.player.inventoryMenu.slots.stream()
                    .filter(slot -> slot.container == client.player.getInventory()
                            && slot.getContainerSlot() == session.targetInventorySlot)
                    .map(slot -> slot.index).findFirst().orElse(-1);
            opened = slotId >= 0 && QuickShulkerConnection.sendOpen(slotId);
        } else if (session.targetHit instanceof BlockHitResult hit) {
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
            opened = true;
        } else if (session.targetHit instanceof EntityHitResult hit) {
            client.gameMode.interact(client.player, hit.getEntity(), hit, InteractionHand.MAIN_HAND);
            opened = true;
        } else {
            opened = false;
        }
        session.stage = Stage.WAIT_TARGET;
        session.waitTicks = 0;
        if (!opened) {
            stop(client, false, null);
        }
    }

    private static void stop(Minecraft client, boolean close, String message) {
        Session finished = session;
        if (finished.temporarySlot >= 0 && client.player != null && client.level == finished.world
                && QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() == finished.ownedHandler && finished.stage == Stage.TARGET
                && finished.ownedHandler.getCarried().isEmpty()) {
            restoreTemporaryStack(finished.ownedHandler, client);
            close &= finished.temporarySlot < 0;
        }
        session = null;
        if (client.player != null) {
            if (finished.temporarySlot >= 0 && client.level == finished.world) {
                var statusScreen = !close && QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> visible
                        && visible.getMenu() == finished.ownedHandler ? visible : null;
                QuickCrossBoxSortStatus.show(statusScreen, Component.translatable("quickcraft.message.quick_stash.temporary_left",
                        finished.temporarySlot + 1));
            } else if (message != null) {
                client.player.sendOverlayMessage(Component.translatable(message));
            }
            if (close && QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen
                    && screen.getMenu() == finished.ownedHandler) {
                client.player.closeContainer();
            }
        }
    }

    private enum Stage { WAIT_INITIAL, TARGET, WAIT_SOURCE, SOURCE, REOPEN, WAIT_TARGET }

    private static final class Session {
        final ClientLevel world;
        final AbstractContainerMenu originalHandler;
        final HitResult targetHit;
        final Block targetBlock;
        final int targetInventorySlot;
        final boolean closeAfter;
        final List<ItemStack> accepted = new ArrayList<>();
        final Set<Integer> exhaustedSources = new HashSet<>();
        AbstractContainerMenu ownedHandler;
        Stage stage = Stage.TARGET;
        List<MissingDemand> demands = List.of();
        int sourcePlayerIndex = -1;
        int temporarySlot = -1;
        ItemStack temporaryStack = ItemStack.EMPTY;
        int waitTicks;

        Session(Minecraft client, AbstractContainerMenu handler, HitResult targetHit, int inventorySlot, boolean closeAfter) {
            world = client.level;
            originalHandler = handler;
            ownedHandler = handler;
            this.targetHit = targetHit;
            targetBlock = targetHit instanceof BlockHitResult hit ? client.level.getBlockState(hit.getBlockPos()).getBlock() : null;
            targetInventorySlot = inventorySlot;
            this.closeAfter = closeAfter;
        }
    }
}
