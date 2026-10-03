package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.*;

/** 当前容器屏幕的绑定、刷新和槽位提示状态；关闭与切换入口统一清理。 */
final class QuickLitematicaContainerScreenBinding {
    private QuickLitematicaContainerScreenBinding() {
    }

    private static boolean suppressInventorySlotHighlights;
    private static BlockPos pendingContainerPos;
    private static BlockPos currentScreenContainerPos;
    private static AbstractContainerScreen<?> currentHandledScreen;
    private static long lastCurrentScreenRefreshTick = Long.MIN_VALUE;
    private static int lastCurrentScreenRevision = Integer.MIN_VALUE;
    private static List<SlotOverlay> currentScreenSlotOverlays = List.of();
    private static Container currentScreenContainerInventory;

    static boolean shouldSuppressInventorySlotHighlights() {
        return suppressInventorySlotHighlights && isEnabled();
    }

    static void setSuppressInventorySlotHighlights(boolean suppress) {
        suppressInventorySlotHighlights = suppress;
    }

    static void clearCurrentHandledScreenBinding() {
        pendingContainerPos = null;
        currentHandledScreen = null;
        clearCurrentScreenContainerBinding();
    }

    static void rememberContainerUse(Minecraft client, BlockHitResult hitResult) {
        Level clientWorld = client.level;
        if (!isEnabled() || clientWorld == null) {
            return;
        }

        Level world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        BlockPos pos = hitResult.getBlockPos();
        BlockEntity blockEntity = world != null ? world.getBlockEntity(pos) : clientWorld.getBlockEntity(pos);

        if (blockEntity instanceof Container || getExpectedContainerAt(world, pos) != null) {
            pendingContainerPos = pos.immutable();
        }
    }

    static SlotOverlay getSlotOverlayForScreen(AbstractContainerScreen<?> screen, Slot slot) {
        if (!isEnabled()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || slot.container instanceof Inventory
                // 只让真正的容器界面参与高亮，避免创造物品栏等界面误触发容器校验。
                || !isSupportedContainerHandler(screen.getMenu())) {
            return null;
        }

        bindCurrentScreen(screen);
        refreshCurrentScreenVerifier(screen);

        // 验证结果刷新不依赖槽位提示开关；这里才决定是否真的绘制提示。
        if (!areSlotHintsVisible()) {
            return null;
        }

        if (currentScreenContainerPos == null) {
            return null;
        }

        if (slot.container != currentScreenContainerInventory) {
            return null;
        }

        if (slot.getContainerSlot() < 0 || slot.getContainerSlot() >= currentScreenSlotOverlays.size()) {
            return null;
        }

        return currentScreenSlotOverlays.get(slot.getContainerSlot());
    }

    static void bindCurrentScreen(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();

        if (QuickClientScreenAccess.currentScreen(client) != screen) {
            return;
        }
        if (currentHandledScreen != screen) {
            currentHandledScreen = screen;
            currentScreenContainerPos = pendingContainerPos;
            pendingContainerPos = null;
            lastCurrentScreenRefreshTick = Long.MIN_VALUE;
            lastCurrentScreenRevision = Integer.MIN_VALUE;
            currentScreenSlotOverlays = List.of();
            currentScreenContainerInventory = null;
        }

        if (currentScreenContainerPos == null) {
            currentScreenContainerPos = isSupportedContainerHandler(screen.getMenu())
                    ? getLookedAtInventoryPos(client)
                    : null;
        }
    }

    static void refreshCurrentScreenVerifier(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        Level clientWorld = client.level;
        BlockPos containerPos = currentScreenContainerPos;

        if (clientWorld == null || containerPos == null) {
            return;
        }

        int currentRevision = screen.getMenu().getStateId();

        long currentTick = clientWorld.getGameTime();

        if (currentTick == lastCurrentScreenRefreshTick && currentRevision == lastCurrentScreenRevision) {
            return;
        }

        lastCurrentScreenRefreshTick = currentTick;
        lastCurrentScreenRevision = currentRevision;
        currentScreenSlotOverlays = List.of();
        currentScreenContainerInventory = null;
        Level world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        ExpectedContainer expectedContainer = getExpectedContainerAt(world, containerPos, placement);

        if (expectedContainer == null
                || !isSupportedHandlerForExpectedContainer(screen.getMenu(), expectedContainer)) {
            clearCurrentScreenContainerBinding();
            return;
        }

        Container containerInventory = findContainerInventory(
                screen.getMenu(), expectedContainer.inventory().getContainerSize());
        Container foundInventory = copyContainerInventoryFromScreen(screen.getMenu(), containerInventory);

        if (foundInventory == null) {
            return;
        }

        currentScreenContainerInventory = containerInventory;
        Set<Integer> foundDisabledSlots = copyCrafterDisabledSlotsFromScreen(screen.getMenu(), containerInventory);
        List<ContainerMismatch> mismatches = null;

        if (placement != null && placement.hasVerifier()) {
            VerifierExtension verifier = (VerifierExtension) placement.getSchematicVerifier();
            mismatches = verifier.quickcraft$refreshContainerMismatchAt(
                    containerPos,
                    foundInventory,
                    foundDisabledSlots
            );

            BlockPos pairedPos = getExpectedDoubleChestAdjacentPos(containerPos, placement);
            if (pairedPos != null) {
                // 大箱子的错误可能记录在另一半坐标；打开任意半边都同步刷新两半。
                verifier.quickcraft$refreshContainerMismatchAt(pairedPos, foundInventory, foundDisabledSlots);
            }
        }

        if (foundInventory.getContainerSize() == expectedContainer.inventory().getContainerSize()) {
            currentScreenSlotOverlays = mismatches != null
                    ? buildSlotOverlays(expectedContainer, mismatches)
                    : buildSlotOverlays(expectedContainer, foundInventory, foundDisabledSlots);
        }
    }

    static BlockPos getLookedAtInventoryPos(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult blockHitResult)
                || blockHitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        Level world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        Level clientWorld = client.level;
        Level lookupWorld = world != null ? world : clientWorld;
        if (lookupWorld == null) {
            return null;
        }

        BlockEntity blockEntity = lookupWorld.getBlockEntity(blockHitResult.getBlockPos());

        return blockEntity instanceof Container ? blockHitResult.getBlockPos().immutable() : null;
    }

    static void clearCurrentScreenContainerBinding() {
        currentScreenContainerPos = null;
        lastCurrentScreenRefreshTick = Long.MIN_VALUE;
        lastCurrentScreenRevision = Integer.MIN_VALUE;
        currentScreenSlotOverlays = List.of();
        currentScreenContainerInventory = null;
    }

    static boolean isSupportedContainerHandler(AbstractContainerMenu handler) {
        return handler instanceof HopperMenu
                || handler instanceof ChestMenu
                || handler instanceof ShulkerBoxMenu
                || handler instanceof DispenserMenu
                || handler instanceof CrafterMenu
                || handler instanceof FurnaceMenu
                || handler instanceof BlastFurnaceMenu
                || handler instanceof SmokerMenu
                || handler instanceof BrewingStandMenu;
    }

    static boolean isSupportedHandlerForExpectedContainer(AbstractContainerMenu handler, ExpectedContainer expectedContainer) {
        QuickContainerCopy.PublicContainerType type = QuickContainerCopy.getPublicContainerType(
                expectedContainer.state().getBlock(),
                QuickLitematicaContainerInventory.getChestType(expectedContainer.state())
        );

        if (type == null) {
            return false;
        }

        return switch (type) {
            case HOPPER -> handler instanceof HopperMenu;
            case SMALL_CHEST, BARREL -> handler instanceof ChestMenu genericHandler
                    && genericHandler.getRowCount() == 3;
            case LARGE_CHEST -> handler instanceof ChestMenu genericHandler
                    && genericHandler.getRowCount() == 6;
            case SHULKER_BOX -> handler instanceof ShulkerBoxMenu;
            case DISPENSER, DROPPER -> handler instanceof DispenserMenu;
            case CRAFTER -> handler instanceof CrafterMenu;
            case FURNACE -> handler instanceof FurnaceMenu;
            case BLAST_FURNACE -> handler instanceof BlastFurnaceMenu;
            case SMOKER -> handler instanceof SmokerMenu;
            case BREWING_STAND -> handler instanceof BrewingStandMenu;
        };
    }

    static Container findContainerInventory(AbstractContainerMenu handler, int expectedSize) {
        if (expectedSize <= 0) {
            return null;
        }

        for (Slot candidate : handler.slots) {
            Container inventory = candidate.container;
            if (inventory instanceof Inventory || inventory.getContainerSize() != expectedSize) {
                continue;
            }

            boolean[] visibleSlots = new boolean[expectedSize];
            for (Slot slot : handler.slots) {
                if (slot.container == inventory
                        && slot.getContainerSlot() >= 0
                        && slot.getContainerSlot() < expectedSize) {
                    visibleSlots[slot.getContainerSlot()] = true;
                }
            }

            boolean complete = true;
            for (boolean visible : visibleSlots) {
                if (!visible) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return inventory;
            }
        }
        return null;
    }

    static Container copyContainerInventoryFromScreen(AbstractContainerMenu handler, Container containerInventory) {
        if (containerInventory == null) {
            return null;
        }

        SimpleContainer inventory = new SimpleContainer(containerInventory.getContainerSize());

        for (Slot slot : handler.slots) {
            if (slot.container == containerInventory
                    && slot.getContainerSlot() >= 0
                    && slot.getContainerSlot() < inventory.getContainerSize()) {
                inventory.setItem(slot.getContainerSlot(), slot.getItem().copy());
            }
        }

        return inventory;
    }

    static Set<Integer> copyCrafterDisabledSlotsFromScreen(
            AbstractContainerMenu handler, Container containerInventory) {
        if (!(handler instanceof CrafterMenu crafterHandler)) {
            return Set.of();
        }

        Set<Integer> disabledSlots = new HashSet<>();

        for (Slot slot : handler.slots) {
            if (slot.container != containerInventory || slot.getContainerSlot() < 0) {
                continue;
            }

            if (crafterHandler.isSlotDisabled(slot.index)) {
                disabledSlots.add(slot.getContainerSlot());
            }
        }

        return disabledSlots;
    }

    static List<SlotOverlay> buildSlotOverlays(
            ExpectedContainer expectedContainer,
            Container foundInventory,
            Set<Integer> foundDisabledSlots
    ) {
        int size = expectedContainer.inventory().getContainerSize();
        List<SlotOverlay> overlays = new ArrayList<>(size);

        // 打开大箱子时每帧都会绘制很多槽位，这里先按 tick 预计算一次，
        // 避免满潜影盒场景反复深比较内部组件导致高亮掉帧。
        for (int slot = 0; slot < size; slot++) {
            ItemStack expectedStack = expectedContainer.inventory().getItem(slot);
            SlotMismatchStatus status = QuickLitematicaContainerComparison.getSlotMismatchStatus(expectedStack, foundInventory.getItem(slot));

            if (status == null && QuickLitematicaContainerComparison.isSlotLockMismatch(
                    expectedContainer.disabledSlots(),
                    foundDisabledSlots,
                    slot
            )) {
                status = SlotMismatchStatus.LOCK_STATE;
            }

            overlays.add(status != null ? new SlotOverlay(status, expectedStack.copy()) : null);
        }

        return overlays;
    }

    static List<SlotOverlay> buildSlotOverlays(
            ExpectedContainer expectedContainer,
            List<ContainerMismatch> mismatches
    ) {
        int size = expectedContainer.inventory().getContainerSize();
        List<SlotOverlay> overlays = new ArrayList<>(size);

        for (int slot = 0; slot < size; slot++) {
            overlays.add(null);
        }

        if (mismatches.isEmpty()) {
            return overlays;
        }

        for (SlotMismatch mismatch : mismatches.getFirst().slotMismatches()) {
            int slot = mismatch.slot();

            if (slot >= 0 && slot < overlays.size()) {
                overlays.set(slot, new SlotOverlay(mismatch.status(), mismatch.expectedStack().copy()));
            }
        }

        return overlays;
    }
}
