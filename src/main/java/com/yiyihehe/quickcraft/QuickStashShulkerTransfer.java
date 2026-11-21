package com.yiyihehe.quickcraft;

import static com.yiyihehe.quickcraft.QuickContainerCopySources.*;

import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.portablestorage.QuickPortableStorageTransfer;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

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

    static boolean start(HandledScreen<?> screen, int inventorySlot, boolean closeAfter) {
        if (session != null) {
            return true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isEnabled() || client.player == null || client.world == null || client.interactionManager == null
                || client.currentScreen != screen || !screen.getScreenHandler().getCursorStack().isEmpty()
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
        HitResult hit = client.crosshairTarget;
        if (inventorySlot < 0 && !(hit instanceof BlockHitResult) && !(hit instanceof EntityHitResult)) {
            return false;
        }
        if (hit instanceof BlockHitResult blockHit) {
            hit = blockHit.withBlockPos(blockHit.getBlockPos().toImmutable());
        }
        session = new Session(client, screen.getScreenHandler(), hit, inventorySlot, closeAfter);
        if (!QuickContainerToolSync.hasSyncedContents(screen.getScreenHandler())) {
            session.stage = Stage.WAIT_INITIAL;
        }
        return true;
    }

    static void onClientTick(MinecraftClient client) {
        if (session == null) {
            return;
        }
        if (!isEnabled() || client.player == null || client.world != session.world
                || client.interactionManager == null || !targetStillExists(client)) {
            stop(client, false, null);
            return;
        }
        if (session.stage == Stage.REOPEN) {
            reopenTarget(client);
            return;
        }
        if (session.stage == Stage.WAIT_INITIAL && !(client.currentScreen instanceof HandledScreen<?> screen
                && screen.getScreenHandler() == session.ownedHandler)) {
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
        if (!(client.currentScreen instanceof HandledScreen<?> screen)
                || screen.getScreenHandler() != session.ownedHandler) {
            stop(client, false, null);
            return;
        }
        if (!session.ownedHandler.getCursorStack().isEmpty()) {
            stop(client, false, "quickcraft.message.quick_stash.cursor_blocked");
            return;
        }
        if (session.stage == Stage.TARGET) {
            processTarget(client, screen);
        } else {
            extractSource(client, screen);
        }
    }

    private static boolean advanceSyncedMenu(MinecraftClient client) {
        var current = client.currentScreen;
        if (current == null) {
            return false;
        }
        if (!(current instanceof HandledScreen<?> screen) || screen.getScreenHandler() != client.player.currentScreenHandler) {
            stop(client, false, null);
            return false;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!QuickContainerToolSync.hasSyncedContents(handler)) {
            return false;
        }
        if (session.stage == Stage.WAIT_INITIAL && handler == session.ownedHandler) {
            session.stage = Stage.TARGET;
        } else if (handler == session.ownedHandler) {
            return false;
        } else if (session.stage == Stage.WAIT_SOURCE && handler instanceof ShulkerBoxScreenHandler) {
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

    private static void processTarget(MinecraftClient client, HandledScreen<?> screen) {
        if (QuickContainerLock.isCurrentScreenLocked(screen)) {
            stop(client, false, null);
            return;
        }
        ScreenHandler handler = session.ownedHandler;
        rememberAcceptedItems(handler);
        for (int processed = 0; processed < MAX_STACKS_PER_BATCH; processed++) {
            if (!depositOnePlayerStack(handler, client)) {
                break;
            }
            if (session == null || processed == MAX_STACKS_PER_BATCH - 1) {
                return;
            }
        }
        if (!handler.getCursorStack().isEmpty()) {
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
        boolean hasSpace = hasExtractionSpace(handler, handler.getSlot(source.slotId()).getStack());
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

    private static void rememberAcceptedItems(ScreenHandler handler) {
        for (int slotId : targetSlots(handler)) {
            ItemStack stack = handler.getSlot(slotId).getStack();
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
        return session.accepted.stream().anyMatch(template -> ItemStack.areItemsAndComponentsEqual(template, stack));
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
            if (!template.isEmpty() && !ItemStack.areItemsAndComponentsEqual(template, stack)) {
                return ItemStack.EMPTY;
            }
            template = stack;
        }
        return template.isEmpty() ? ItemStack.EMPTY : template.copyWithCount(1);
    }

    private static List<Integer> targetSlots(ScreenHandler handler) {
        return getContainerSlotIds(handler).stream()
                .filter(id -> id != session.temporarySlot && !QuickContainerLock.isLockedSlot(handler, id))
                .toList();
    }

    private static boolean depositOnePlayerStack(ScreenHandler handler, MinecraftClient client) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot source = handler.getSlot(slotId);
            if (source.getIndex() == session.targetInventorySlot || !source.hasStack() || !source.canTakeItems(client.player)) {
                continue;
            }
            ItemStack stack = source.getStack();
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
                if (!handler.getCursorStack().isEmpty()) {
                    return false;
                }
                boolean moved = source.getStack().getCount() < before;
                if (moveToContainerSlots(handler, slotId, client) || moved) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean moveToContainerSlots(ScreenHandler handler, int sourceSlotId, MinecraftClient client) {
        Slot source = handler.getSlot(sourceSlotId);
        if (!source.hasStack()) {
            return false;
        }
        int before = source.getStack().getCount();
        List<Integer> slots = new ArrayList<>(targetSlots(handler));
        slots.sort(java.util.Comparator.comparing(id -> !handler.getSlot(id).hasStack()));
        for (int slotId : slots) {
            int capacity = capacity(handler.getSlot(slotId), source.getStack());
            if (capacity > 0) {
                TRANSFER.moveItemsToTargetSlot(handler, sourceSlotId, slotId, Math.min(capacity, source.getStack().getCount()), client);
            }
            if (!source.hasStack() || !handler.getCursorStack().isEmpty()) {
                break;
            }
        }
        return source.getStack().getCount() < before;
    }

    private static int capacity(Slot slot, ItemStack stack) {
        if (stack.isEmpty() || !slot.isEnabled() || !slot.canInsert(stack)
                || slot.hasStack() && !ItemStack.areItemsAndComponentsEqual(slot.getStack(), stack)) {
            return 0;
        }
        return Math.max(0, Math.min(stack.getMaxCount(), slot.getMaxItemCount(stack))
                - (slot.hasStack() ? slot.getStack().getCount() : 0));
    }

    private static List<MissingDemand> remainingCapacity(ScreenHandler handler) {
        List<MissingDemand> demands = new ArrayList<>();
        List<Integer> emptySlots = new ArrayList<>(targetSlots(handler).stream()
                .filter(id -> !handler.getSlot(id).hasStack()).toList());
        java.util.Map<Integer, Integer> shulkerFreeSlots = new java.util.HashMap<>();
        for (int slotId : targetSlots(handler)) {
            ItemStack shulker = handler.getSlot(slotId).getStack();
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
                if (playerSlot.getIndex() == session.targetInventorySlot
                        || session.exhaustedSources.contains(playerSlot.getIndex())
                        || !isShulkerBox(playerSlot.getStack())) {
                    continue;
                }
                for (ItemStack stored : getStoredStacksBySlot(playerSlot.getStack())) {
                    if (ItemStack.areItemsAndComponentsEqual(stored, template)) {
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
                if (slot.hasStack()) {
                    capacity += capacity(slot, template);
                }
                if (QuickShulkerStash.canReceive(handler, slotId, template, MinecraftClient.getInstance())) {
                    capacity += QuickShulkerStash.matchingPartialCapacity(slot.getStack(), template);
                    matchingShulkers.add(slotId);
                }
            }
            for (int slotId : matchingShulkers) {
                if (capacity >= available) {
                    break;
                }
                int maxCount = template.getMaxCount();
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

    private static SourceShulker findSource(ScreenHandler handler) {
        return findSource(handler, -1);
    }

    private static SourceShulker findSource(ScreenHandler handler, int excludedPlayerIndex) {
        SourceShulker fallback = null;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.getIndex() != session.targetInventorySlot && slot.getIndex() != excludedPlayerIndex
                    && !session.exhaustedSources.contains(slot.getIndex())
                    && slot.hasStack() && slot.canTakeItems(MinecraftClient.getInstance().player)
                    && slot.getStack().getCount() == 1 && isShulkerBox(slot.getStack())
                    && getSourceShulkerScore(slot.getStack(), session.demands).usefulItemCount() > 0) {
                SourceShulker source = new SourceShulker(slotId, slot.getIndex());
                if (hasExtractionSpace(handler, slot.getStack())) {
                    return source;
                }
                if (fallback == null) {
                    fallback = source;
                }
            }
        }
        return fallback;
    }

    private static boolean hasExtractionSpace(ScreenHandler handler, ItemStack source) {
        for (ItemStack stored : getStoredStacksBySlot(source)) {
            if (findDemandForStack(session.demands, stored) != null
                    && canStoreAnyStackInPlayerStorage(handler, stored)) {
                return true;
            }
        }
        return false;
    }

    private static boolean makeWorkingSlot(ScreenHandler handler, int sourcePlayerIndex, MinecraftClient client) {
        int excludedShulkerSlot = getPlayerStorageSlotIds(handler).stream()
                .filter(id -> handler.getSlot(id).getIndex() == session.targetInventorySlot).findFirst().orElse(-1);
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (TRANSFER.canUsePlayerStackToFreeQuickShulkerSpace(slot.getStack())
                    && TRANSFER.packSlotIntoQuickShulkerExcept(handler, slotId, excludedShulkerSlot, client)) {
                return true;
            }
        }
        if (session.temporarySlot >= 0) {
            return false;
        }
        for (int sourceId : getPlayerStorageSlotIds(handler)) {
            Slot source = handler.getSlot(sourceId);
            if (!source.hasStack() || !source.canTakeItems(client.player)
                    || source.getIndex() == sourcePlayerIndex || source.getIndex() == session.targetInventorySlot
                    || !isShulkerBox(source.getStack())
                        && !TRANSFER.canUsePlayerStackToFreeQuickShulkerSpace(source.getStack())) {
                continue;
            }
            for (int targetId : targetSlots(handler)) {
                Slot target = handler.getSlot(targetId);
                if (!target.hasStack() && capacity(target, source.getStack()) >= source.getStack().getCount()) {
                    ItemStack saved = source.getStack().copy();
                    TRANSFER.moveItemsToTargetSlot(handler, sourceId, targetId, saved.getCount(), client);
                    if (!source.hasStack() && handler.getCursorStack().isEmpty()
                            && ItemStack.areItemsAndComponentsEqual(saved, target.getStack())
                            && target.getStack().getCount() == saved.getCount()) {
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

    private static boolean restoreTemporaryStack(ScreenHandler handler, MinecraftClient client) {
        if (session.temporarySlot < 0) {
            return true;
        }
        Slot source = handler.getSlot(session.temporarySlot);
        if (!source.canTakeItems(client.player) || QuickContainerLock.isLockedSlot(handler, source)
                || !ItemStack.areItemsAndComponentsEqual(source.getStack(), session.temporaryStack)
                || source.getStack().getCount() != session.temporaryStack.getCount()) {
            return false;
        }
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot target = handler.getSlot(slotId);
            int capacity = capacity(target, source.getStack());
            if (target.getIndex() != session.targetInventorySlot && capacity > 0) {
                TRANSFER.moveItemsToTargetSlot(handler, source.id, slotId, Math.min(capacity, source.getStack().getCount()), client);
                session.temporaryStack = source.getStack().copy();
            }
            if (!source.hasStack() && handler.getCursorStack().isEmpty()) {
                session.temporarySlot = -1;
                return true;
            }
        }
        return false;
    }

    private static void extractSource(MinecraftClient client, HandledScreen<?> screen) {
        int moved = 0;
        if (!QuickContainerLock.isCurrentScreenLocked(screen)) {
            var result = TRANSFER.moveMatchingItemsFromOpenShulker(
                    (ShulkerBoxScreenHandler) session.ownedHandler, session.demands, client);
            moved = result.moved();
            session.demands = result.remainingDemands();
        }
        if (moved == 0) {
            session.exhaustedSources.add(session.sourcePlayerIndex);
        }
        if (!session.ownedHandler.getCursorStack().isEmpty()) {
            stop(client, false, "quickcraft.message.quick_stash.cursor_blocked");
            return;
        }
        openNextSourceOrReturn(client);
    }

    private static void openNextSourceOrReturn(MinecraftClient client) {
        SourceShulker next = findSource(session.ownedHandler, session.sourcePlayerIndex);
        if (next != null && hasExtractionSpace(session.ownedHandler, session.ownedHandler.getSlot(next.slotId()).getStack())) {
            session.sourcePlayerIndex = next.playerIndex();
            session.stage = Stage.WAIT_SOURCE;
            session.waitTicks = 0;
            if (!QuickShulkerConnection.sendOpen(next.slotId())) {
                stop(client, false, null);
            }
            return;
        }
        client.player.closeHandledScreen();
        session.stage = Stage.REOPEN;
    }

    private static boolean targetStillExists(MinecraftClient client) {
        if (session.targetInventorySlot >= 0) {
            return isShulkerBox(client.player.getInventory().getStack(session.targetInventorySlot));
        }
        if (session.targetHit instanceof BlockHitResult hit) {
            return client.world.getBlockState(hit.getBlockPos()).getBlock() == session.targetBlock;
        }
        return session.targetHit instanceof EntityHitResult hit && !hit.getEntity().isRemoved();
    }

    private static void reopenTarget(MinecraftClient client) {
        if (client.currentScreen != null) {
            stop(client, false, null);
            return;
        }
        boolean opened;
        if (session.targetInventorySlot >= 0) {
            int slotId = client.player.playerScreenHandler.slots.stream()
                    .filter(slot -> slot.inventory == client.player.getInventory()
                            && slot.getIndex() == session.targetInventorySlot)
                    .map(slot -> slot.id).findFirst().orElse(-1);
            opened = slotId >= 0 && QuickShulkerConnection.sendOpen(slotId);
        } else if (session.targetHit instanceof BlockHitResult hit) {
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
            opened = true;
        } else if (session.targetHit instanceof EntityHitResult hit) {
            client.interactionManager.interactEntity(client.player, hit.getEntity(), Hand.MAIN_HAND);
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

    private static void stop(MinecraftClient client, boolean close, String message) {
        Session finished = session;
        if (finished.temporarySlot >= 0 && client.player != null && client.world == finished.world
                && client.currentScreen instanceof HandledScreen<?> screen
                && screen.getScreenHandler() == finished.ownedHandler && finished.stage == Stage.TARGET
                && finished.ownedHandler.getCursorStack().isEmpty()) {
            restoreTemporaryStack(finished.ownedHandler, client);
            close &= finished.temporarySlot < 0;
        }
        session = null;
        if (client.player != null) {
            if (message != null) {
                client.player.sendMessage(Text.translatable(message), true);
            }
            if (finished.temporarySlot >= 0) {
                client.player.sendMessage(Text.translatable("quickcraft.message.quick_stash.temporary_left",
                        finished.temporarySlot + 1), false);
            }
            if (close && client.currentScreen instanceof HandledScreen<?> screen
                    && screen.getScreenHandler() == finished.ownedHandler) {
                client.player.closeHandledScreen();
            }
        }
    }

    private enum Stage { WAIT_INITIAL, TARGET, WAIT_SOURCE, SOURCE, REOPEN, WAIT_TARGET }

    private static final class Session {
        final ClientWorld world;
        final ScreenHandler originalHandler;
        final HitResult targetHit;
        final Block targetBlock;
        final int targetInventorySlot;
        final boolean closeAfter;
        final List<ItemStack> accepted = new ArrayList<>();
        final Set<Integer> exhaustedSources = new HashSet<>();
        ScreenHandler ownedHandler;
        Stage stage = Stage.TARGET;
        List<MissingDemand> demands = List.of();
        int sourcePlayerIndex = -1;
        int temporarySlot = -1;
        ItemStack temporaryStack = ItemStack.EMPTY;
        int waitTicks;

        Session(MinecraftClient client, ScreenHandler handler, HitResult targetHit, int inventorySlot, boolean closeAfter) {
            world = client.world;
            originalHandler = handler;
            ownedHandler = handler;
            this.targetHit = targetHit;
            targetBlock = targetHit instanceof BlockHitResult hit ? client.world.getBlockState(hit.getBlockPos()).getBlock() : null;
            targetInventorySlot = inventorySlot;
            this.closeAfter = closeAfter;
        }
    }
}
