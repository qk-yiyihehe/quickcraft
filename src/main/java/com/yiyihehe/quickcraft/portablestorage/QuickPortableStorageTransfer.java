package com.yiyihehe.quickcraft.portablestorage;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import com.yiyihehe.quickcraft.QuickInventoryShulkerTools;
import com.yiyihehe.quickcraft.QuickMaterialCollector;
import com.yiyihehe.quickcraft.QuickSlotTransfer;
import com.yiyihehe.quickcraft.QuickStashShulkerTransfer;
import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulker;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortInventory;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortSync;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.type.ContainerComponent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class QuickPortableStorageTransfer {
    public enum Source { SHULKER, ENDER_CHEST, BUNDLE }
    enum Mode { PREVIEW, ONE, STACK, TAKE_ALL, OFFHAND, STORE_ONE, STORE_STACK, STORE_CURSOR, RESTOCK }

    record Entry(int carrier, int slot, ItemStack stack) {}
    private record Request(Screen screen, Source source, Mode mode, ItemStack stack, int inventorySlot,
                           ItemStack expectedTarget, boolean tweakerooMiss, int remainingStacks) {
        Request withRemainingStacks(int count) {
            return new Request(screen, source, mode, stack, inventorySlot, expectedTarget, tweakerooMiss, count);
        }
    }

    private static final int STORAGE_SLOTS = 27;
    private static final int OPEN_TIMEOUT = 40;
    private static final int MAX_QUEUE = 36;
    private static final int CLICK_BURST_IDLE_TICKS = 6;
    private static final int MAX_ACTIONS_PER_TICK = 8;
    private static final ArrayDeque<Request> requests = new ArrayDeque<>();
    private static Session active;
    private static final Map<Integer, ClosedShulker> recentContents = new HashMap<>();
    private static long storageRevision;
    private static long tickNumber;
    private static long lastOpenTick = -1;
    private static ClientPlayerEntity owner;
    private static ClientWorld world;
    private static List<ItemStack> enderContents = List.of();

    private QuickPortableStorageTransfer() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            QuickShulkerRestockFallback.tick(client);
            tick(client);
        });
    }

    public static boolean panelEnabled() {
        return QuickCraftConfigs.ContainerTools.ENABLE_PORTABLE_STORAGE.getBooleanValue();
    }

    public static boolean isRunning() {
        return active != null;
    }

    public static boolean blocksItemUse() {
        return QuickShulkerRestockFallback.enabled() && active != null && active.request.mode == Mode.RESTOCK
                && !active.cancelled && !easyPlaceHasPriority(MinecraftClient.getInstance());
    }

    public static boolean yieldHandRestockToEasyPlace() {
        if (active != null && active.request.mode == Mode.RESTOCK) {
            active.cancelled = true;
            return true;
        }
        return false;
    }

    static boolean easyPlaceHasPriority(MinecraftClient client) {
        return FabricLoader.getInstance().isModLoaded("litematica")
                && QuickLitematicaShulkerMaterialRestock.takesPriorityOverHandRestock(client);
    }

    static boolean inUse(Screen screen) {
        return active != null && active.request.screen == screen;
    }

    static boolean waitingForOpen(Screen screen) {
        return inUse(screen) && !active.ready;
    }

    static boolean blocksVanillaInput(Screen screen) {
        if (!inUse(screen)) {
            return false;
        }
        if (!active.ready) {
            return true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        finish(client, active, null);
        return client.currentScreen != screen;
    }

    static long storageRevision() {
        return storageRevision;
    }

    static boolean hasEnderChest(ClientPlayerEntity player) {
        return player != null && findEnderChestCarrier(player.playerScreenHandler) >= 0;
    }

    static boolean hasEnderSnapshot() {
        return !enderContents.isEmpty();
    }

    static List<Entry> entries(ClientPlayerEntity player, Source source) {
        List<Entry> entries = new ArrayList<>();
        if (player == null) {
            return entries;
        }
        if (source == Source.ENDER_CHEST) {
            int carrier = findEnderChestCarrier(player.playerScreenHandler);
            if (carrier >= 0) {
                collect(entries, carrier, enderContents);
            }
            return entries;
        }
        var menu = player.playerScreenHandler;
        for (Slot slot : menu.slots) {
            if (QuickSlotTransfer.isPlayerStorageSlot(slot) && carrierMatches(slot.getStack(), source)) {
                collect(entries, slot.getIndex(), contentsForCarrier(slot, source));
            }
        }
        return entries;
    }

    private static void collect(List<Entry> entries, int carrier, List<ItemStack> contents) {
        for (int index = 0; index < contents.size(); index++) {
            ItemStack stack = contents.get(index);
            if (!stack.isEmpty()) {
                entries.add(new Entry(carrier, index, stack));
            }
        }
    }

    private static List<ItemStack> contents(ItemStack stack, Source source) {
        if (source == Source.BUNDLE) {
            return QuickPortableStorageBundleAccess.contents(stack);
        }
        DefaultedList<ItemStack> contents = DefaultedList.ofSize(STORAGE_SLOTS, ItemStack.EMPTY);
        stack.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).copyTo(contents);
        return contents;
    }

    static boolean request(InventoryScreen screen, Source source, Mode mode, ItemStack stack, int inventorySlot) {
        return enqueue(new Request(screen, source, mode, stack.copy(), inventorySlot, ItemStack.EMPTY, false, 0));
    }

    static boolean restock(ItemStack template, int inventorySlot, ItemStack current, boolean tweakerooMiss) {
        if (QuickShulkerRestockFallback.enabled() && active == null && requests.isEmpty()) {
            var player = MinecraftClient.getInstance().player;
            if (player == null || QuickContainerLock.isPlayerInventoryLocked()
                    || locked(player.playerScreenHandler, inventorySlot(player.playerScreenHandler, inventorySlot))
                    || otherTransferRunning() || findEntry(player, Source.SHULKER, template) == null) {
                return false;
            }
            return enqueue(new Request(null, Source.SHULKER, Mode.RESTOCK, template.copyWithCount(1),
                    inventorySlot, current.copy(), tweakerooMiss, 0));
        }
        return false;
    }

    private static boolean enqueue(Request request) {
        var client = MinecraftClient.getInstance();
        if (!enabledFor(request) || client.player == null || client.world == null || client.player.isCreative()
                || requests.size() >= MAX_QUEUE) {
            return false;
        }
        bindOwner(client);
        if (request.mode == Mode.TAKE_ALL) {
            // Each successful stack move consumes one source entry unless the inventory fills.
            // Bound retries against a server that repeatedly restores the same contents.
            int matchingStacks = 0;
            for (Entry entry : entries(owner, request.source)) {
                if (ItemStack.areItemsAndComponentsEqual(entry.stack, request.stack)) {
                    matchingStacks++;
                }
            }
            request = request.withRemainingStacks(matchingStacks);
        }
        requests.addLast(request);
        return true;
    }

    private static void bindOwner(MinecraftClient client) {
        if (owner != client.player || world != client.world) {
            requests.clear();
            active = null;
            recentContents.clear();
            storageRevision++;
            lastOpenTick = -1;
            enderContents = List.of();
            owner = client.player;
            world = client.world;
        }
    }

    private static void tick(MinecraftClient client) {
        bindOwner(client);
        if (!panelEnabled() && !enderContents.isEmpty()) {
            enderContents = List.of();
            storageRevision++;
        }
        if (owner == null || world == null || client.interactionManager == null) {
            return;
        }

        Screen screen = client.currentScreen;
        ++tickNumber;
        if (recentContents.entrySet().removeIf(entry -> tickNumber >= entry.getValue().deadline
                || !same(owner.getInventory().getStack(entry.getKey()), entry.getValue().before))) {
            storageRevision++;
        }
        requests.removeIf(request -> !validRequest(client, request, screen));
        if (active != null) {
            tickSession(client, screen);
            if (active != null) {
                return;
            }
        }
        Request next = requests.peekFirst();
        if (next == null || owner.currentScreenHandler != owner.playerScreenHandler || otherTransferRunning()) {
            return;
        }
        if (next.source != Source.BUNDLE && lastOpenTick >= 0
                && tickNumber - lastOpenTick < QuickCraftConfigs.getQuickShulkerActionIntervalTicks()) {
            return;
        }
        start(client, requests.removeFirst());
    }

    private static boolean enabledFor(Request request) {
        return request.mode == Mode.RESTOCK ? QuickShulkerRestockFallback.enabled() : panelEnabled();
    }

    private static boolean validRequest(MinecraftClient client, Request request, Screen current) {
        return enabledFor(request) && request.screen == current
                && (request.mode != Mode.RESTOCK || !easyPlaceHasPriority(client) && validRestock(owner, request))
                && (!(current instanceof InventoryScreen inventory) || QuickPortableStoragePanel.isVisible(inventory));
    }

    private static boolean otherTransferRunning() {
        return QuickCrossBoxSort.isRunning() || QuickStashShulkerTransfer.isRunning()
                || QuickInventoryShulkerTools.isOpening()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || QuickMaterialCollector.shouldHideBackgroundHandledScreen()
                || QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()
                || (FabricLoader.getInstance().isModLoaded("litematica")
                && QuickLitematicaShulkerMaterialRestock.isRestockInProgress());
    }

    private static void start(MinecraftClient client, Request request) {
        ScreenHandler menu = owner.playerScreenHandler;
        boolean preview = request.mode == Mode.PREVIEW;
        if (owner.isSpectator() || !owner.isAlive() || (!preview && QuickContainerLock.isPlayerInventoryLocked())) {
            return;
        }
        if (!(request.mode == Mode.STORE_CURSOR ? same(menu.getCursorStack(), request.stack)
                : menu.getCursorStack().isEmpty())) {
            return;
        }
        if (request.mode == Mode.RESTOCK && !validRestock(owner, request)) {
            return;
        }
        boolean store = isStore(request.mode);
        if (store && request.mode != Mode.STORE_CURSOR && locked(menu, inventorySlot(menu, request.inventorySlot))) {
            message(client, request, "locked_slot");
            return;
        }
        if (request.mode == Mode.TAKE_ALL && findPlayerSlot(menu, request.stack, slot -> true) == null) {
            message(client, request, "no_space");
            return;
        }
        if (request.source == Source.BUNDLE) {
            boolean moreBundles = QuickPortableStorageBundles.transfer(client, request.mode, request.stack, request.inventorySlot);
            if (moreBundles && canContinueTakingAll(menu, request)) {
                requests.addFirst(request.withRemainingStacks(request.remainingStacks - 1));
            }
            return;
        }
        if (request.source == Source.ENDER_CHEST && !preview && QuickContainerLock.isEnderChestLocked()) {
            message(client, request, "locked_slot");
            return;
        }
        if (!QuickShulkerConnection.canOpen()) {
            message(client, request, "unavailable");
            return;
        }
        int carrier = selectCarrier(request, menu);
        Slot carrierSlot = inventorySlot(menu, carrier);
        if (carrierSlot == null || (!preview && inaccessibleCarrier(menu, carrierSlot, request.source))) {
            message(client, request, "no_source");
            return;
        }
        int staging = -1;
        if (request.mode == Mode.STORE_CURSOR) {
            Slot buffer = findPlayerSlot(menu, request.stack, slot -> !slot.hasStack());
            if (buffer == null) {
                message(client, request, "no_space");
                return;
            }
            staging = buffer.getIndex();
            click(client, menu, buffer, 0);
            if (!menu.getCursorStack().isEmpty() || !same(buffer.getStack(), request.stack)) {
                return;
            }
        }

        long revision = QuickCrossBoxSortSync.revision();
        if (QuickShulkerConnection.sendOpen(carrierSlot.id)) {
            lastOpenTick = tickNumber;
            active = new Session(request, carrier, carrierSlot.getStack().copy(), staging, revision);
        } else {
            restoreCursor(client, request, staging, request.stack);
            message(client, request, "unavailable");
        }
    }

    /** Invoked by the shared setScreen interception before any hidden screen is initialized. */
    public static boolean consumeScreen(Screen next) {
        Session session = active;
        if (session == null) {
            return false;
        }
        var client = MinecraftClient.getInstance();
        if (client.player != owner || client.world != world) {
            return false;
        }
        if (next == null && session.closing) {
            return client.currentScreen != null;
        }
        if (session.opened != null || !(next instanceof HandledScreen<?> screen)
                || owner.currentScreenHandler != screen.getScreenHandler()) {
            return false;
        }
        Screen current = client.currentScreen;
        if (current != session.request.screen) {
            if (current != null) {
                return false;
            }
            session.cancelled = true;
        }
        var menu = screen.getScreenHandler();
        boolean matches = session.request.source == Source.SHULKER ? menu instanceof ShulkerBoxScreenHandler
                : QuickContainerLock.isEnderChestScreen(screen);
        if (!matches) {
            return false;
        }
        session.opened = screen;
        if (menu instanceof ShulkerBoxScreenHandler) {
            QuickContainerLock.bindInventoryShulkerScreen(screen, session.carrier);
        } else {
            QuickContainerLock.bindEnderChestScreen(screen);
        }
        return true;
    }

    private static void tickSession(MinecraftClient client, Screen screen) {
        Session session = active;
        if (!validRequest(client, session.request, screen) || !owner.isAlive()) {
            // Keep a sent open owned until its response arrives or times out.
            session.cancelled = true;
        }
        ScreenHandler menu = session.opened == null ? null : session.opened.getScreenHandler();
        if (owner.currentScreenHandler != (menu == null ? owner.playerScreenHandler : menu)) {
            finish(client, session, "open_failed");
        } else if (menu != null && session.cancelled) {
            finish(client, session, null);
        } else if (menu != null && QuickCrossBoxSortSync.contentAdvancedFor(session.revision, menu.syncId)) {
            // Let the full client packet batch finish before reading the carrier and sending menu clicks.
            if (session.ready) {
                continueBatch(client, session, menu);
            } else {
                transferOpenedStorage(client, session, menu);
            }
        } else if (++session.ticks >= OPEN_TIMEOUT) {
            finish(client, session, "open_failed");
        }
    }

    private static void transferOpenedStorage(MinecraftClient client, Session session, ScreenHandler menu) {
        if (!canUseOpenedStorage(client, session, menu)) {
            return;
        }

        session.ready = true;
        refreshOpenedContents(session, menu);
        if (session.request.mode == Mode.PREVIEW) {
            finish(client, session, null);
        } else if (applyRequest(client, session, menu, session.request)) {
            if (isBatchable(session.request)) {
                continueBatch(client, session, menu);
            } else {
                finish(client, session, null);
            }
        }
    }

    private static boolean canUseOpenedStorage(MinecraftClient client, Session session, ScreenHandler menu) {
        Slot carrier = inventorySlot(owner.playerScreenHandler, session.carrier);
        if (session.request.mode != Mode.PREVIEW && (QuickContainerLock.isPlayerInventoryLocked()
                || inaccessibleCarrier(owner.playerScreenHandler, carrier, session.request.source))) {
            finish(client, session, "locked_slot");
            return false;
        }
        if (carrier == null || !sameCarrier(carrier.getStack(), session.expectedCarrier, session.request.source)) {
            finish(client, session, "carrier_changed");
            return false;
        }
        if (!menu.getCursorStack().isEmpty()) {
            finish(client, session, "cursor_busy");
            return false;
        }
        return true;
    }

    private static void continueBatch(MinecraftClient client, Session session, ScreenHandler menu) {
        if (!canUseOpenedStorage(client, session, menu)) {
            return;
        }
        refreshOpenedContents(session, menu);
        for (int actions = 0; actions < MAX_ACTIONS_PER_TICK; actions++) {
            Request next = requests.peekFirst();
            if (next == null) {
                if (tickNumber - session.lastActionTick >= CLICK_BURST_IDLE_TICKS) {
                    finish(client, session, null);
                }
                return;
            }
            if (next.source != session.request.source || !isBatchable(next)
                    || (isStore(next.mode) ? selectCarrier(next, owner.playerScreenHandler) != session.carrier
                    : findLiveSource(menu, next.stack) == null)) {
                finish(client, session, null);
                return;
            }
            if (tickNumber - session.lastActionTick < QuickCraftConfigs.getQuickShulkerActionIntervalTicks()) {
                return;
            }
            requests.removeFirst();
            if (!applyRequest(client, session, menu, next)) {
                return;
            }
        }
    }

    private static boolean applyRequest(MinecraftClient client, Session session, ScreenHandler menu, Request request) {
        if (request.source == Source.ENDER_CHEST && QuickContainerLock.isEnderChestLocked()) {
            finish(client, session, "locked_slot");
            return false;
        }
        Slot from = isStore(request.mode) ? null : findLiveSource(menu, request.stack);
        if (!isStore(request.mode) && from == null) {
            finish(client, session, "no_source");
            return false;
        }
        boolean moved = move(client, menu, session, request, from);
        refreshOpenedContents(session, menu);
        if (!moved || !menu.getCursorStack().isEmpty()) {
            finish(client, session, "no_space");
            return false;
        }
        session.lastActionTick = tickNumber;
        if (canContinueTakingAll(menu, request)
                && (findLiveSource(menu, request.stack) != null || findEntry(owner, request.source, request.stack) != null)) {
            requests.addFirst(request.withRemainingStacks(request.remainingStacks - 1));
        }
        return true;
    }

    private static boolean canContinueTakingAll(ScreenHandler menu, Request request) {
        return request.mode == Mode.TAKE_ALL && request.remainingStacks > 1
                && findPlayerSlot(menu, request.stack, slot -> true) != null;
    }

    private static Slot findLiveSource(ScreenHandler menu, ItemStack template) {
        for (int index = 0; index < STORAGE_SLOTS; index++) {
            Slot slot = menu.getSlot(index);
            if (!locked(menu, slot) && slot.hasStack() && ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isBatchable(Request request) {
        return request.mode != Mode.PREVIEW && request.mode != Mode.STORE_CURSOR && request.mode != Mode.RESTOCK;
    }

    private static boolean sameCarrier(ItemStack actual, ItemStack expected, Source source) {
        if (same(actual, expected)) {
            return true;
        }
        if (source != Source.SHULKER || !carrierMatches(actual, source)) {
            return false;
        }
        // The server can update the box component during transfer; slot and outer item still identify the carrier.
        ItemStack actualBox = actual.copy();
        ItemStack expectedBox = expected.copy();
        actualBox.remove(DataComponentTypes.CONTAINER);
        expectedBox.remove(DataComponentTypes.CONTAINER);
        return same(actualBox, expectedBox);
    }

    private static void refreshOpenedContents(Session session, ScreenHandler menu) {
        if (session.contents.size() == STORAGE_SLOTS) {
            int index = 0;
            while (index < STORAGE_SLOTS && same(session.contents.get(index), menu.getSlot(index).getStack())) {
                index++;
            }
            if (index == STORAGE_SLOTS) {
                return;
            }
        }
        session.contents = menu.slots.subList(0, STORAGE_SLOTS).stream().map(slot -> slot.getStack().copy()).toList();
        if (session.request.source == Source.ENDER_CHEST) {
            enderContents = session.contents;
        }
        storageRevision++;
    }

    private static List<ItemStack> contentsForCarrier(Slot slot, Source source) {
        int carrier = slot.getIndex();
        if (source == Source.SHULKER) {
            if (active != null && active.ready && active.request.source == source && active.carrier == carrier
                    && sameCarrier(slot.getStack(), active.expectedCarrier, source)) {
                return active.contents;
            }
            ClosedShulker recent = recentContents.get(carrier);
            if (recent != null && same(slot.getStack(), recent.before)) {
                return recent.contents;
            }
        }
        return contents(slot.getStack(), source);
    }

    private static int selectCarrier(Request request, ScreenHandler menu) {
        if (isStore(request.mode)) {
            return findDepositCarrier(menu, request);
        }
        if (request.mode == Mode.PREVIEW) {
            return findEnderChestCarrier(menu);
        }
        Entry entry = findEntry(owner, request.source, request.stack);
        return entry == null ? -1 : entry.carrier;
    }

    private static boolean move(MinecraftClient client, ScreenHandler menu, Session session, Request request, Slot source) {
        if (isStore(request.mode)) {
            int origin = request.mode == Mode.STORE_CURSOR ? session.staging : request.inventorySlot;
            Slot from = inventorySlot(menu, origin);
            if (locked(menu, from) || !same(from.getStack(), request.stack)) {
                return false;
            }
            int before = from.getStack().getCount();
            if (request.mode == Mode.STORE_ONE) {
                Slot target = findContainerSlot(menu, request.stack);
                return target != null && QuickSlotTransfer.moveAmount(client, menu, from, target, 1) > 0;
            }
            QuickSlotTransfer.quickMove(client, menu, from.id, slot -> slot.id < STORAGE_SLOTS, false,
                    slot -> locked(menu, slot));
            return from.getStack().getCount() < before;
        }

        Slot from = source;
        return switch (request.mode) {
            case STACK, TAKE_ALL -> {
                int before = from.getStack().getCount();
                QuickSlotTransfer.quickMove(client, menu, from.id, QuickSlotTransfer::isPlayerStorageSlot, true,
                        slot -> locked(menu, slot));
                yield from.getStack().getCount() < before;
            }
            case ONE -> {
                Slot target = findPlayerSlot(menu, request.stack, slot -> true);
                yield target != null && QuickSlotTransfer.moveAmount(client, menu, from, target, 1) > 0;
            }
            case OFFHAND -> equipOffhand(client, menu, from);
            case RESTOCK -> restockHand(client, menu, from, request);
            default -> false;
        };
    }

    private static boolean restockHand(MinecraftClient client, ScreenHandler menu, Slot source, Request request) {
        if (!validRestock(owner, request)) {
            return false;
        }
        if (request.inventorySlot == PlayerInventory.OFF_HAND_SLOT) {
            if (request.expectedTarget.isEmpty()) {
                return equipOffhand(client, menu, source);
            }
            Slot reserve = findPlayerSlot(menu, request.expectedTarget, slot -> !slot.hasStack());
            if (reserve == null) {
                return false;
            }
            swapOffhand(client, menu, reserve);
            if (!same(reserve.getStack(), request.expectedTarget) || !client.player.getOffHandStack().isEmpty()) {
                return false;
            }
            int moved = QuickSlotTransfer.moveAmount(client, menu, source, reserve, source.getStack().getMaxCount());
            swapOffhand(client, menu, reserve);
            return moved > 0;
        }
        Slot target = inventorySlot(menu, request.inventorySlot);
        if (locked(menu, target)) {
            return false;
        }
        Slot bucketDestination = null;
        if (target.getStack().isOf(Items.BUCKET) && request.stack.isOf(Items.WATER_BUCKET)) {
            bucketDestination = findPlayerSlot(menu, target.getStack(), slot -> slot != target);
            if (bucketDestination == null || !menu.getCursorStack().isEmpty()
                    || QuickSlotTransfer.moveAmount(client, menu, target, bucketDestination, 1) != 1) {
                return false;
            }
        }
        if (QuickSlotTransfer.moveAmount(client, menu, source, target, source.getStack().getMaxCount()) > 0) {
            return true;
        }
        if (bucketDestination != null && !target.hasStack()) {
            QuickSlotTransfer.moveAmount(client, menu, bucketDestination, target, 1);
        }
        return false;
    }

    private static boolean validRestock(ClientPlayerEntity player, Request request) {
        return (request.inventorySlot == PlayerInventory.OFF_HAND_SLOT
                || request.inventorySlot == QuickShulkerRestockFallback.selectedSlot(player))
                && same(player.getInventory().getStack(request.inventorySlot), request.expectedTarget)
                && !QuickContainerLock.isPlayerInventoryLocked()
                && !locked(player.playerScreenHandler, inventorySlot(player.playerScreenHandler, request.inventorySlot))
                && QuickShulkerRestockFallback.canTakeOver(player, request.stack, request.tweakerooMiss);
    }

    static boolean equipOffhand(MinecraftClient client, ScreenHandler menu, Slot source) {
        ItemStack template = source.getStack().copy();
        ItemStack old = client.player.getOffHandStack();
        if (!old.isEmpty()) {
            // Use a normal empty slot to preserve the displaced offhand stack.
            Slot spare = findPlayerSlot(menu, old, slot -> !slot.hasStack() && slot != source);
            if (spare == null) {
                return false;
            }
            swapOffhand(client, menu, spare);
            if (!client.player.getOffHandStack().isEmpty()) {
                return false;
            }
            swapOffhand(client, menu, source);
            if (client.player.getOffHandStack().isEmpty()) {
                swapOffhand(client, menu, spare);
                return false;
            }
        } else {
            swapOffhand(client, menu, source);
        }
        return same(client.player.getOffHandStack(), template);
    }

    private static void swapOffhand(MinecraftClient client, ScreenHandler menu, Slot slot) {
        client.interactionManager.clickSlot(menu.syncId, slot.id, PlayerInventory.OFF_HAND_SLOT,
                SlotActionType.SWAP, client.player);
    }

    private static void finish(MinecraftClient client, Session session, String failure) {
        try {
            if (session.opened != null && owner.currentScreenHandler == session.opened.getScreenHandler()) {
                if (!owner.currentScreenHandler.getCursorStack().isEmpty()) {
                    requests.clear();
                    client.setScreen(session.opened);
                    message(client, session.request, "cursor_busy");
                    return;
                }
                if (session.ready && session.request.source == Source.SHULKER) {
                    Slot carrier = inventorySlot(owner.playerScreenHandler, session.carrier);
                    if (carrier != null && sameCarrier(carrier.getStack(), session.expectedCarrier, Source.SHULKER)) {
                        refreshOpenedContents(session, owner.currentScreenHandler);
                        recentContents.put(session.carrier,
                                new ClosedShulker(carrier.getStack().copy(), session.contents, tickNumber + OPEN_TIMEOUT));
                    }
                }
                session.closing = true;
                owner.closeHandledScreen();
            }
            if (session.request.mode == Mode.STORE_CURSOR) {
                restoreCursor(client, session.request, session.staging, session.request.stack);
            }
            if (failure != null && validRequest(client, session.request, client.currentScreen)) {
                message(client, session.request, failure);
            }
        } finally {
            active = null;
        }
    }

    private static void restoreCursor(MinecraftClient client, Request request, int index, ItemStack expected) {
        ScreenHandler base = owner.playerScreenHandler;
        Slot slot = inventorySlot(base, index);
        if (request.screen == client.currentScreen && owner.currentScreenHandler == base
                && base.getCursorStack().isEmpty() && !locked(base, slot) && slot.hasStack()
                && ItemStack.areItemsAndComponentsEqual(slot.getStack(), expected)
                && slot.getStack().getCount() <= expected.getCount()) {
            click(client, base, slot, 0);
        }
    }

    private static Entry findEntry(ClientPlayerEntity player, Source source, ItemStack stack) {
        for (Entry entry : entries(player, source)) {
            if (ItemStack.areItemsAndComponentsEqual(entry.stack, stack)
                    && !inaccessibleCarrier(player.playerScreenHandler, inventorySlot(player.playerScreenHandler, entry.carrier), source)
                    && !isStoredSlotLocked(entry, source)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean isStoredSlotLocked(Entry entry, Source source) {
        return switch (source) {
            case SHULKER -> QuickContainerLock.isInventoryShulkerSlotLocked(entry.carrier, entry.slot);
            case ENDER_CHEST -> QuickContainerLock.isEnderChestSlotLocked(entry.slot);
            case BUNDLE -> false;
        };
    }

    private static int findEnderChestCarrier(ScreenHandler menu) {
        int lockedCarrier = -1;
        for (Slot slot : menu.slots) {
            if (!QuickSlotTransfer.isPlayerStorageSlot(slot) || !slot.isEnabled()
                    || !carrierMatches(slot.getStack(), Source.ENDER_CHEST)) {
                continue;
            }
            if (!locked(menu, slot)) {
                return slot.getIndex();
            }
            if (lockedCarrier < 0) {
                lockedCarrier = slot.getIndex();
            }
        }
        // All carriers share the same contents. A locked carrier can still be used for preview.
        return lockedCarrier;
    }

    private static int findDepositCarrier(ScreenHandler menu, Request request) {
        int emptySpace = -1;
        for (Slot slot : menu.slots) {
            if (!QuickSlotTransfer.isPlayerStorageSlot(slot) || slot.getIndex() == request.inventorySlot
                    || inaccessibleCarrier(menu, slot, request.source) || !carrierMatches(slot.getStack(), request.source)) {
                continue;
            }
            if (request.source == Source.ENDER_CHEST) {
                return slot.getIndex();
            }
            List<ItemStack> contents = contentsForCarrier(slot, request.source);
            for (int boxSlot = 0; boxSlot < contents.size(); boxSlot++) {
                if (QuickContainerLock.isInventoryShulkerSlotLocked(slot.getIndex(), boxSlot)) {
                    continue;
                }
                ItemStack inside = contents.get(boxSlot);
                if (inside.isEmpty() && emptySpace == -1) {
                    emptySpace = slot.getIndex();
                } else if (ItemStack.areItemsAndComponentsEqual(inside, request.stack)
                        && inside.getCount() < inside.getMaxCount()) {
                    return slot.getIndex();
                }
            }
        }
        return emptySpace;
    }

    private static boolean carrierMatches(ItemStack stack, Source source) {
        return switch (source) {
            case SHULKER -> QuickCrossBoxSortInventory.isVanillaShulkerBox(stack);
            case ENDER_CHEST -> stack.isOf(Items.ENDER_CHEST);
            case BUNDLE -> stack.contains(DataComponentTypes.BUNDLE_CONTENTS);
        };
    }

    static Slot inventorySlot(ScreenHandler menu, int index) {
        if (index < 0) {
            return null;
        }
        for (Slot slot : menu.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == index) {
                return slot;
            }
        }
        return null;
    }

    static Slot findPlayerSlot(ScreenHandler menu, ItemStack stack, Predicate<Slot> filter) {
        return findTarget(menu, stack, slot -> QuickSlotTransfer.isPlayerStorageSlot(slot) && filter.test(slot));
    }

    private static Slot findContainerSlot(ScreenHandler menu, ItemStack stack) {
        return findTarget(menu, stack, slot -> slot.id < STORAGE_SLOTS);
    }

    private static Slot findTarget(ScreenHandler menu, ItemStack stack, Predicate<Slot> filter) {
        Slot empty = null;
        for (Slot slot : menu.slots) {
            if (!filter.test(slot) || locked(menu, slot) || !slot.canInsert(stack)) {
                continue;
            }
            if (!slot.hasStack()) {
                if (empty == null) {
                    empty = slot;
                }
            } else if (ItemStack.areItemsAndComponentsEqual(slot.getStack(), stack)
                    && slot.getStack().getCount() < slot.getMaxItemCount(stack)) {
                return slot;
            }
        }
        return empty;
    }

    private static boolean inaccessibleCarrier(ScreenHandler menu, Slot slot, Source source) {
        return locked(menu, slot)
                || source == Source.SHULKER && QuickContainerLock.isInventoryShulkerLocked(slot.getIndex());
    }

    static boolean locked(ScreenHandler menu, Slot slot) {
        return slot == null || !slot.isEnabled() || QuickContainerLock.isLockedSlot(menu, slot)
                || QuickSlotTransfer.isPlayerStorageSlot(slot) && QuickContainerLock.isLockedPlayerInventorySlot(slot.getIndex());
    }

    static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && (a.isEmpty() && b.isEmpty() || ItemStack.areItemsAndComponentsEqual(a, b));
    }

    static boolean isStore(Mode mode) {
        return mode == Mode.STORE_CURSOR || mode == Mode.STORE_ONE || mode == Mode.STORE_STACK;
    }

    static void click(MinecraftClient client, ScreenHandler menu, Slot slot, int button) {
        client.interactionManager.clickSlot(menu.syncId, slot.id, button, SlotActionType.PICKUP, client.player);
    }

    private static void message(MinecraftClient client, Request request, String name) {
        if (request.screen != null) {
            fi.dy.masa.malilib.util.InfoUtils.printActionbarMessage("quickcraft.portable_storage." + name);
        }
    }

    private record ClosedShulker(ItemStack before, List<ItemStack> contents, long deadline) {}

    private static final class Session {
        final Request request;
        final int carrier;
        final ItemStack expectedCarrier;
        final int staging;
        final long revision;
        HandledScreen<?> opened;
        List<ItemStack> contents = List.of();
        int ticks;
        long lastActionTick;
        boolean ready;
        boolean closing;
        boolean cancelled;

        Session(Request request, int carrier, ItemStack expectedCarrier, int staging, long revision) {
            this.request = request;
            this.carrier = carrier;
            this.expectedCarrier = expectedCarrier;
            this.staging = staging;
            this.revision = revision;
        }
    }
}
