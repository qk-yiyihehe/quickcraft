package com.yiyihehe.quickcraft.portablestorage;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

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
                           ItemStack expectedTarget, int remainingStacks) {
        Request withRemainingStacks(int count) {
            return new Request(screen, source, mode, stack, inventorySlot, expectedTarget, count);
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
    private static LocalPlayer owner;
    private static ClientLevel world;
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
                && !active.cancelled && !easyPlaceHasPriority(Minecraft.getInstance());
    }

    public static boolean yieldHandRestockToEasyPlace() {
        if (active != null && active.request.mode == Mode.RESTOCK) {
            active.cancelled = true;
            return true;
        }
        return false;
    }

    static boolean easyPlaceHasPriority(Minecraft client) {
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
        Minecraft client = Minecraft.getInstance();
        finish(client, active, null);
        return QuickClientScreenAccess.currentScreen(client) != screen;
    }

    static long storageRevision() {
        return storageRevision;
    }

    static boolean hasEnderChest(LocalPlayer player) {
        return player != null && findEnderChestCarrier(player.inventoryMenu) >= 0;
    }

    static boolean hasEnderSnapshot() {
        return !enderContents.isEmpty();
    }

    static List<Entry> entries(LocalPlayer player, Source source) {
        List<Entry> entries = new ArrayList<>();
        if (player == null) {
            return entries;
        }
        if (source == Source.ENDER_CHEST) {
            int carrier = findEnderChestCarrier(player.inventoryMenu);
            if (carrier >= 0) {
                collect(entries, carrier, enderContents);
            }
            return entries;
        }
        var menu = player.inventoryMenu;
        for (Slot slot : menu.slots) {
            if (QuickSlotTransfer.isPlayerStorageSlot(slot) && carrierMatches(slot.getItem(), source)) {
                collect(entries, slot.getContainerSlot(), contentsForCarrier(slot, source));
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
        NonNullList<ItemStack> contents = NonNullList.withSize(STORAGE_SLOTS, ItemStack.EMPTY);
        stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents);
        return contents;
    }

    static boolean request(InventoryScreen screen, Source source, Mode mode, ItemStack stack, int inventorySlot) {
        return enqueue(new Request(screen, source, mode, stack.copy(), inventorySlot, ItemStack.EMPTY, 0));
    }

    static Slot findInventoryRestockSource(LocalPlayer player, ItemStack template) {
        var menu = player.inventoryMenu;
        int selected = QuickShulkerRestockFallback.selectedSlot(player);
        for (Slot slot : menu.slots) {
            if (QuickSlotTransfer.isPlayerStorageSlot(slot) && slot.getContainerSlot() != selected
                    && slot.hasItem() && !locked(menu, slot) && slot.mayPickup(player)
                    && QuickShulkerRestockFallback.matches(template, slot.getItem())) {
                return slot;
            }
        }
        return null;
    }

    static boolean restockFromInventory(LocalPlayer player, int targetSlot, ItemStack template,
                                        ItemStack current, Slot source) {
        var menu = player.inventoryMenu;
        if (active != null || !requests.isEmpty() || otherTransferRunning() || !menu.getCarried().isEmpty()
                || !validRestockTarget(player, targetSlot, current)) {
            return false;
        }
        var client = Minecraft.getInstance();
        if (QuickShulkerRestockFallback.isRemainder(template, current)) {
            return replaceRemainder(client, menu, source, targetSlot, current);
        }
        Slot target = inventorySlot(menu, targetSlot);
        return QuickSlotTransfer.moveAmount(client, menu, source, target, source.getItem().getMaxStackSize()) > 0;
    }

    static boolean restock(ItemStack template, int inventorySlot, ItemStack current) {
        if (QuickShulkerRestockFallback.enabled() && active == null && requests.isEmpty()) {
            var player = Minecraft.getInstance().player;
            Request request = new Request(null, Source.SHULKER, Mode.RESTOCK, template.copyWithCount(1),
                    inventorySlot, current.copy(), 0);
            return player != null && !otherTransferRunning() && validRestock(player, request)
                    && findEntry(player, request) != null && enqueue(request);
        }
        return false;
    }

    private static boolean enqueue(Request request) {
        var client = Minecraft.getInstance();
        if (!enabledFor(request) || client.player == null || client.level == null || client.player.isCreative()
                || requests.size() >= MAX_QUEUE) {
            return false;
        }
        bindOwner(client);
        if (request.mode == Mode.TAKE_ALL) {
            // Each successful stack move consumes one source entry unless the inventory fills.
            // Bound retries against a server that repeatedly restores the same contents.
            int matchingStacks = 0;
            for (Entry entry : entries(owner, request.source)) {
                if (ItemStack.isSameItemSameComponents(entry.stack, request.stack)) {
                    matchingStacks++;
                }
            }
            request = request.withRemainingStacks(matchingStacks);
        }
        requests.addLast(request);
        return true;
    }

    private static void bindOwner(Minecraft client) {
        if (owner != client.player || world != client.level) {
            requests.clear();
            active = null;
            recentContents.clear();
            storageRevision++;
            enderContents = List.of();
            owner = client.player;
            world = client.level;
        }
    }

    private static void tick(Minecraft client) {
        bindOwner(client);
        if (!panelEnabled() && !enderContents.isEmpty()) {
            enderContents = List.of();
            storageRevision++;
        }
        if (owner == null || world == null || client.gameMode == null) {
            return;
        }

        Screen screen = QuickClientScreenAccess.currentScreen(client);
        ++tickNumber;
        if (recentContents.entrySet().removeIf(entry -> tickNumber >= entry.getValue().deadline
                || !same(owner.getInventory().getItem(entry.getKey()), entry.getValue().before))) {
            storageRevision++;
        }
        requests.removeIf(request -> !validRequest(client, request, screen));
        if (active != null) {
            tickSession(client, screen);
            if (active != null) {
                return;
            }
        }
        if (requests.isEmpty() || owner.containerMenu != owner.inventoryMenu || otherTransferRunning()) {
            return;
        }
        start(client, requests.removeFirst());
    }

    private static boolean enabledFor(Request request) {
        return request.mode == Mode.RESTOCK ? QuickShulkerRestockFallback.enabled() : panelEnabled();
    }

    private static boolean validRequest(Minecraft client, Request request, Screen current) {
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

    private static void start(Minecraft client, Request request) {
        AbstractContainerMenu menu = owner.inventoryMenu;
        boolean preview = request.mode == Mode.PREVIEW;
        if (owner.isSpectator() || !owner.isAlive() || (!preview && QuickContainerLock.isPlayerInventoryLocked())) {
            return;
        }
        if (!(request.mode == Mode.STORE_CURSOR ? same(menu.getCarried(), request.stack)
                : menu.getCarried().isEmpty())) {
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
            Slot buffer = findPlayerSlot(menu, request.stack, slot -> !slot.hasItem());
            if (buffer == null) {
                message(client, request, "no_space");
                return;
            }
            staging = buffer.getContainerSlot();
            click(client, menu, buffer, 0);
            if (!menu.getCarried().isEmpty() || !same(buffer.getItem(), request.stack)) {
                return;
            }
        }

        long revision = QuickCrossBoxSortSync.revision();
        if (QuickShulkerConnection.sendOpen(carrierSlot.index)) {
            active = new Session(request, carrier, carrierSlot.getItem().copy(), staging, revision);
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
        var client = Minecraft.getInstance();
        if (client.player != owner || client.level != world) {
            return false;
        }
        if (next == null && session.closing) {
            return QuickClientScreenAccess.currentScreen(client) != null;
        }
        if (session.opened != null || !(next instanceof AbstractContainerScreen<?> screen)
                || owner.containerMenu != screen.getMenu()) {
            return false;
        }
        Screen current = QuickClientScreenAccess.currentScreen(client);
        if (current != session.request.screen) {
            if (current != null) {
                return false;
            }
            session.cancelled = true;
        }
        var menu = screen.getMenu();
        boolean matches = session.request.source == Source.SHULKER ? menu instanceof ShulkerBoxMenu
                : QuickContainerLock.isEnderChestScreen(screen);
        if (!matches) {
            return false;
        }
        session.opened = screen;
        if (menu instanceof ShulkerBoxMenu) {
            QuickContainerLock.bindInventoryShulkerScreen(screen, session.carrier);
        } else {
            QuickContainerLock.bindEnderChestScreen(screen);
        }
        return true;
    }

    private static void tickSession(Minecraft client, Screen screen) {
        Session session = active;
        if (!validRequest(client, session.request, screen) || !owner.isAlive()) {
            // Keep a sent open owned until its response arrives or times out.
            session.cancelled = true;
        }
        AbstractContainerMenu menu = session.opened == null ? null : session.opened.getMenu();
        if (owner.containerMenu != (menu == null ? owner.inventoryMenu : menu)) {
            finish(client, session, "open_failed");
        } else if (menu != null && session.cancelled) {
            finish(client, session, null);
        } else if (menu != null && QuickCrossBoxSortSync.contentAdvancedFor(session.revision, menu.containerId)) {
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

    private static void transferOpenedStorage(Minecraft client, Session session, AbstractContainerMenu menu) {
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

    private static boolean canUseOpenedStorage(Minecraft client, Session session, AbstractContainerMenu menu) {
        Slot carrier = inventorySlot(owner.inventoryMenu, session.carrier);
        if (session.request.mode != Mode.PREVIEW && (QuickContainerLock.isPlayerInventoryLocked()
                || inaccessibleCarrier(owner.inventoryMenu, carrier, session.request.source))) {
            finish(client, session, "locked_slot");
            return false;
        }
        if (carrier == null || !sameCarrier(carrier.getItem(), session.expectedCarrier, session.request.source)) {
            finish(client, session, "carrier_changed");
            return false;
        }
        if (!menu.getCarried().isEmpty()) {
            finish(client, session, "cursor_busy");
            return false;
        }
        return true;
    }

    private static void continueBatch(Minecraft client, Session session, AbstractContainerMenu menu) {
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
                    || (isStore(next.mode) ? selectCarrier(next, owner.inventoryMenu) != session.carrier
                    : findLiveSource(menu, next) == null)) {
                finish(client, session, null);
                return;
            }
            requests.removeFirst();
            if (!applyRequest(client, session, menu, next)) {
                return;
            }
        }
    }

    private static boolean applyRequest(Minecraft client, Session session, AbstractContainerMenu menu, Request request) {
        if (request.source == Source.ENDER_CHEST && QuickContainerLock.isEnderChestLocked()) {
            finish(client, session, "locked_slot");
            return false;
        }
        Slot from = isStore(request.mode) ? null : findLiveSource(menu, request);
        if (!isStore(request.mode) && from == null) {
            finish(client, session, "no_source");
            return false;
        }
        boolean moved = move(client, menu, session, request, from);
        refreshOpenedContents(session, menu);
        if (!moved || !menu.getCarried().isEmpty()) {
            finish(client, session, "no_space");
            return false;
        }
        session.lastActionTick = tickNumber;
        if (canContinueTakingAll(menu, request)
                && (findLiveSource(menu, request) != null || findEntry(owner, request) != null)) {
            requests.addFirst(request.withRemainingStacks(request.remainingStacks - 1));
        }
        return true;
    }

    private static boolean canContinueTakingAll(AbstractContainerMenu menu, Request request) {
        return request.mode == Mode.TAKE_ALL && request.remainingStacks > 1
                && findPlayerSlot(menu, request.stack, slot -> true) != null;
    }

    private static Slot findLiveSource(AbstractContainerMenu menu, Request request) {
        for (int index = 0; index < STORAGE_SLOTS; index++) {
            Slot slot = menu.getSlot(index);
            if (!locked(menu, slot) && slot.hasItem() && matchesSource(request, slot.getItem())) {
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
        actualBox.remove(DataComponents.CONTAINER);
        expectedBox.remove(DataComponents.CONTAINER);
        return same(actualBox, expectedBox);
    }

    private static void refreshOpenedContents(Session session, AbstractContainerMenu menu) {
        if (session.contents.size() == STORAGE_SLOTS) {
            int index = 0;
            while (index < STORAGE_SLOTS && same(session.contents.get(index), menu.getSlot(index).getItem())) {
                index++;
            }
            if (index == STORAGE_SLOTS) {
                return;
            }
        }
        session.contents = menu.slots.subList(0, STORAGE_SLOTS).stream().map(slot -> slot.getItem().copy()).toList();
        if (session.request.source == Source.ENDER_CHEST) {
            enderContents = session.contents;
        }
        storageRevision++;
    }

    private static List<ItemStack> contentsForCarrier(Slot slot, Source source) {
        int carrier = slot.getContainerSlot();
        if (source == Source.SHULKER) {
            if (active != null && active.ready && active.request.source == source && active.carrier == carrier
                    && sameCarrier(slot.getItem(), active.expectedCarrier, source)) {
                return active.contents;
            }
            ClosedShulker recent = recentContents.get(carrier);
            if (recent != null && same(slot.getItem(), recent.before)) {
                return recent.contents;
            }
        }
        return contents(slot.getItem(), source);
    }

    private static int selectCarrier(Request request, AbstractContainerMenu menu) {
        if (isStore(request.mode)) {
            return findDepositCarrier(menu, request);
        }
        if (request.mode == Mode.PREVIEW) {
            return findEnderChestCarrier(menu);
        }
        Entry entry = findEntry(owner, request);
        return entry == null ? -1 : entry.carrier;
    }

    private static boolean move(Minecraft client, AbstractContainerMenu menu, Session session, Request request, Slot source) {
        if (isStore(request.mode)) {
            int origin = request.mode == Mode.STORE_CURSOR ? session.staging : request.inventorySlot;
            Slot from = inventorySlot(menu, origin);
            if (locked(menu, from) || !same(from.getItem(), request.stack)) {
                return false;
            }
            int before = from.getItem().getCount();
            if (request.mode == Mode.STORE_ONE) {
                Slot target = findContainerSlot(menu, request.stack);
                return target != null && QuickSlotTransfer.moveAmount(client, menu, from, target, 1) > 0;
            }
            QuickSlotTransfer.quickMove(client, menu, from.index, slot -> slot.index < STORAGE_SLOTS, false,
                    slot -> locked(menu, slot));
            return from.getItem().getCount() < before;
        }

        Slot from = source;
        return switch (request.mode) {
            case STACK, TAKE_ALL -> {
                int before = from.getItem().getCount();
                QuickSlotTransfer.quickMove(client, menu, from.index, QuickSlotTransfer::isPlayerStorageSlot, true,
                        slot -> locked(menu, slot));
                yield from.getItem().getCount() < before;
            }
            case ONE -> {
                Slot target = findPlayerTakeSlot(menu, request.stack, slot -> true);
                yield target != null && QuickSlotTransfer.moveAmount(client, menu, from, target, 1) > 0;
            }
            case OFFHAND -> equipOffhand(client, menu, from);
            case RESTOCK -> restockHand(client, menu, from, request);
            default -> false;
        };
    }

    private static boolean restockHand(Minecraft client, AbstractContainerMenu menu, Slot source, Request request) {
        if (!validRestock(owner, request)) {
            return false;
        }
        if (request.inventorySlot == Inventory.SLOT_OFFHAND) {
            if (request.expectedTarget.isEmpty()) {
                return equipOffhand(client, menu, source);
            }
            if (QuickShulkerRestockFallback.isRemainder(request.stack, request.expectedTarget)) {
                return replaceRemainder(client, menu, source, request.inventorySlot, request.expectedTarget);
            }
            Slot reserve = findPlayerSlot(menu, request.expectedTarget, slot -> !slot.hasItem());
            if (reserve == null) {
                return false;
            }
            swapOffhand(client, menu, reserve);
            if (!same(reserve.getItem(), request.expectedTarget) || !client.player.getOffhandItem().isEmpty()) {
                return false;
            }
            int moved = QuickSlotTransfer.moveAmount(client, menu, source, reserve, source.getItem().getMaxStackSize());
            swapOffhand(client, menu, reserve);
            return moved > 0;
        }
        Slot target = inventorySlot(menu, request.inventorySlot);
        if (locked(menu, target)) {
            return false;
        }
        Slot remainderDestination = null;
        if (QuickShulkerRestockFallback.isRemainder(request.stack, request.expectedTarget)) {
            remainderDestination = findPlayerSlot(menu, target.getItem(), slot -> slot != target);
            if (remainderDestination == null || !menu.getCarried().isEmpty()
                    || QuickSlotTransfer.moveAmount(client, menu, target, remainderDestination, 1) != 1) {
                return false;
            }
        }
        if (QuickSlotTransfer.moveAmount(client, menu, source, target, source.getItem().getMaxStackSize()) > 0) {
            return true;
        }
        if (remainderDestination != null && !target.hasItem()) {
            QuickSlotTransfer.moveAmount(client, menu, remainderDestination, target, 1);
        }
        return false;
    }

    private static boolean replaceRemainder(Minecraft client, AbstractContainerMenu menu, Slot source,
                                            int targetSlot, ItemStack remainder) {
        boolean sourceInInventory = QuickSlotTransfer.isPlayerStorageSlot(source);
        Slot destination = findPlayerSlot(menu, remainder, slot -> slot != source && slot.getContainerSlot() != targetSlot
                && (!sourceInInventory || slot.hasItem()));
        if ((!sourceInInventory && destination == null) || !source.mayPlace(remainder)
                || remainder.getCount() > source.getMaxStackSize(remainder)) {
            return false;
        }
        ItemStack replacement = source.getItem().copy();
        swapWithPlayerSlot(client, menu, source, targetSlot);
        if (!same(client.player.getInventory().getItem(targetSlot), replacement)
                || !same(source.getItem(), remainder)) {
            return false;
        }
        if (destination != null
                && QuickSlotTransfer.moveAmount(client, menu, source, destination, remainder.getCount()) > 0) {
            return true;
        }
        // A backpack source already holds the remainder safely. A shulker source is only a temporary slot.
        if (sourceInInventory) {
            return true;
        }
        if (menu.getCarried().isEmpty() && same(source.getItem(), remainder)) {
            swapWithPlayerSlot(client, menu, source, targetSlot);
        }
        return false;
    }

    private static boolean validRestock(LocalPlayer player, Request request) {
        return validRestockTarget(player, request.inventorySlot, request.expectedTarget)
                && findInventoryRestockSource(player, request.stack) == null;
    }

    private static boolean validRestockTarget(LocalPlayer player, int slot, ItemStack expected) {
        return (slot == Inventory.SLOT_OFFHAND || slot == QuickShulkerRestockFallback.selectedSlot(player))
                && same(player.getInventory().getItem(slot), expected)
                && !QuickContainerLock.isPlayerInventoryLocked()
                && !locked(player.inventoryMenu, inventorySlot(player.inventoryMenu, slot));
    }

    static boolean equipOffhand(Minecraft client, AbstractContainerMenu menu, Slot source) {
        ItemStack template = source.getItem().copy();
        ItemStack old = client.player.getOffhandItem();
        if (!old.isEmpty()) {
            // Use a normal empty slot to preserve the displaced offhand stack.
            Slot spare = findPlayerSlot(menu, old, slot -> !slot.hasItem() && slot != source);
            if (spare == null) {
                return false;
            }
            swapOffhand(client, menu, spare);
            if (!client.player.getOffhandItem().isEmpty()) {
                return false;
            }
            swapOffhand(client, menu, source);
            if (client.player.getOffhandItem().isEmpty()) {
                swapOffhand(client, menu, spare);
                return false;
            }
        } else {
            swapOffhand(client, menu, source);
        }
        return same(client.player.getOffhandItem(), template);
    }

    private static void swapOffhand(Minecraft client, AbstractContainerMenu menu, Slot slot) {
        swapWithPlayerSlot(client, menu, slot, Inventory.SLOT_OFFHAND);
    }

    private static void swapWithPlayerSlot(Minecraft client, AbstractContainerMenu menu, Slot slot, int inventorySlot) {
        client.gameMode.handleContainerInput(menu.containerId, slot.index, inventorySlot, ContainerInput.SWAP, client.player);
    }

    private static void finish(Minecraft client, Session session, String failure) {
        try {
            if (session.opened != null && owner.containerMenu == session.opened.getMenu()) {
                if (!owner.containerMenu.getCarried().isEmpty()) {
                    requests.clear();
                    QuickClientScreenAccess.setScreen(client, session.opened);
                    message(client, session.request, "cursor_busy");
                    return;
                }
                if (session.ready && session.request.source == Source.SHULKER) {
                    Slot carrier = inventorySlot(owner.inventoryMenu, session.carrier);
                    if (carrier != null && sameCarrier(carrier.getItem(), session.expectedCarrier, Source.SHULKER)) {
                        refreshOpenedContents(session, owner.containerMenu);
                        recentContents.put(session.carrier,
                                new ClosedShulker(carrier.getItem().copy(), session.contents, tickNumber + OPEN_TIMEOUT));
                    }
                }
                session.closing = true;
                owner.closeContainer();
            }
            if (session.request.mode == Mode.STORE_CURSOR) {
                restoreCursor(client, session.request, session.staging, session.request.stack);
            }
            if (failure != null && validRequest(client, session.request, QuickClientScreenAccess.currentScreen(client))) {
                message(client, session.request, failure);
            }
        } finally {
            active = null;
        }
    }

    private static void restoreCursor(Minecraft client, Request request, int index, ItemStack expected) {
        AbstractContainerMenu base = owner.inventoryMenu;
        Slot slot = inventorySlot(base, index);
        if (request.screen == QuickClientScreenAccess.currentScreen(client) && owner.containerMenu == base
                && base.getCarried().isEmpty() && !locked(base, slot) && slot.hasItem()
                && ItemStack.isSameItemSameComponents(slot.getItem(), expected)
                && slot.getItem().getCount() <= expected.getCount()) {
            click(client, base, slot, 0);
        }
    }

    private static boolean matchesSource(Request request, ItemStack stack) {
        return request.mode == Mode.RESTOCK ? QuickShulkerRestockFallback.matches(request.stack, stack)
                : ItemStack.isSameItemSameComponents(request.stack, stack);
    }

    private static Entry findEntry(LocalPlayer player, Request request) {
        for (Entry entry : entries(player, request.source)) {
            if (matchesSource(request, entry.stack)
                    && !inaccessibleCarrier(player.inventoryMenu,
                            inventorySlot(player.inventoryMenu, entry.carrier), request.source)
                    && !isStoredSlotLocked(entry, request.source)) {
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

    private static int findEnderChestCarrier(AbstractContainerMenu menu) {
        int lockedCarrier = -1;
        for (Slot slot : menu.slots) {
            if (!QuickSlotTransfer.isPlayerStorageSlot(slot) || !slot.isActive()
                    || !carrierMatches(slot.getItem(), Source.ENDER_CHEST)) {
                continue;
            }
            if (!locked(menu, slot)) {
                return slot.getContainerSlot();
            }
            if (lockedCarrier < 0) {
                lockedCarrier = slot.getContainerSlot();
            }
        }
        // All carriers share the same contents. A locked carrier can still be used for preview.
        return lockedCarrier;
    }

    private static int findDepositCarrier(AbstractContainerMenu menu, Request request) {
        int emptySpace = -1;
        for (Slot slot : menu.slots) {
            if (!QuickSlotTransfer.isPlayerStorageSlot(slot) || slot.getContainerSlot() == request.inventorySlot
                    || inaccessibleCarrier(menu, slot, request.source) || !carrierMatches(slot.getItem(), request.source)) {
                continue;
            }
            if (request.source == Source.ENDER_CHEST) {
                return slot.getContainerSlot();
            }
            List<ItemStack> contents = contentsForCarrier(slot, request.source);
            for (int boxSlot = 0; boxSlot < contents.size(); boxSlot++) {
                if (QuickContainerLock.isInventoryShulkerSlotLocked(slot.getContainerSlot(), boxSlot)) {
                    continue;
                }
                ItemStack inside = contents.get(boxSlot);
                if (inside.isEmpty() && emptySpace == -1) {
                    emptySpace = slot.getContainerSlot();
                } else if (ItemStack.isSameItemSameComponents(inside, request.stack)
                        && inside.getCount() < inside.getMaxStackSize()) {
                    return slot.getContainerSlot();
                }
            }
        }
        return emptySpace;
    }

    private static boolean carrierMatches(ItemStack stack, Source source) {
        return switch (source) {
            case SHULKER -> QuickCrossBoxSortInventory.isVanillaShulkerBox(stack);
            case ENDER_CHEST -> stack.is(Items.ENDER_CHEST);
            case BUNDLE -> stack.has(DataComponents.BUNDLE_CONTENTS);
        };
    }

    static Slot inventorySlot(AbstractContainerMenu menu, int index) {
        if (index < 0) {
            return null;
        }
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory && slot.getContainerSlot() == index) {
                return slot;
            }
        }
        return null;
    }

    static Slot findPlayerSlot(AbstractContainerMenu menu, ItemStack stack, Predicate<Slot> filter) {
        return findTarget(menu, menu.slots, stack, slot -> QuickSlotTransfer.isPlayerStorageSlot(slot) && filter.test(slot));
    }

    static Slot findPlayerTakeSlot(AbstractContainerMenu menu, ItemStack stack, Predicate<Slot> filter) {
        return findTarget(menu, menu.slots.reversed(), stack,
                slot -> QuickSlotTransfer.isPlayerStorageSlot(slot) && filter.test(slot));
    }

    private static Slot findContainerSlot(AbstractContainerMenu menu, ItemStack stack) {
        return findTarget(menu, menu.slots, stack, slot -> slot.index < STORAGE_SLOTS);
    }

    private static Slot findTarget(AbstractContainerMenu menu, List<Slot> slots, ItemStack stack, Predicate<Slot> filter) {
        Slot empty = null;
        for (Slot slot : slots) {
            if (!filter.test(slot) || locked(menu, slot) || !slot.mayPlace(stack)) {
                continue;
            }
            if (!slot.hasItem()) {
                if (empty == null) {
                    empty = slot;
                }
            } else if (ItemStack.isSameItemSameComponents(slot.getItem(), stack)
                    && slot.getItem().getCount() < slot.getMaxStackSize(stack)) {
                return slot;
            }
        }
        return empty;
    }

    private static boolean inaccessibleCarrier(AbstractContainerMenu menu, Slot slot, Source source) {
        return locked(menu, slot)
                || source == Source.SHULKER && QuickContainerLock.isInventoryShulkerLocked(slot.getContainerSlot());
    }

    static boolean locked(AbstractContainerMenu menu, Slot slot) {
        return slot == null || !slot.isActive() || QuickContainerLock.isLockedSlot(menu, slot)
                || QuickSlotTransfer.isPlayerStorageSlot(slot) && QuickContainerLock.isLockedPlayerInventorySlot(slot.getContainerSlot());
    }

    static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && (a.isEmpty() && b.isEmpty() || ItemStack.isSameItemSameComponents(a, b));
    }

    static boolean isStore(Mode mode) {
        return mode == Mode.STORE_CURSOR || mode == Mode.STORE_ONE || mode == Mode.STORE_STACK;
    }

    static void click(Minecraft client, AbstractContainerMenu menu, Slot slot, int button) {
        client.gameMode.handleContainerInput(menu.containerId, slot.index, button, ContainerInput.PICKUP, client.player);
    }

    private static void message(Minecraft client, Request request, String name) {
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
        AbstractContainerScreen<?> opened;
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
