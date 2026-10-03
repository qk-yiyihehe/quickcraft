package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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
    private static HandledScreen<?> currentHandledScreen;
    private static long lastCurrentScreenRefreshTick = Long.MIN_VALUE;
    private static int lastCurrentScreenRevision = Integer.MIN_VALUE;
    private static List<SlotOverlay> currentScreenSlotOverlays = List.of();
    private static Inventory currentScreenContainerInventory;

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

    static void rememberContainerUse(MinecraftClient client, BlockHitResult hitResult) {
        if (!isEnabled() || client.world == null) {
            return;
        }

        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        BlockPos pos = hitResult.getBlockPos();
        BlockEntity blockEntity = world != null ? world.getBlockEntity(pos) : client.world.getBlockEntity(pos);

        if (blockEntity instanceof Inventory || getExpectedContainerAt(world, pos) != null) {
            pendingContainerPos = pos.toImmutable();
        }
    }

    static SlotOverlay getSlotOverlayForScreen(HandledScreen<?> screen, Slot slot) {
        if (!isEnabled()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || slot.inventory instanceof PlayerInventory
                // 只让真正的容器界面参与高亮，避免创造物品栏等界面误触发容器校验。
                || !isSupportedContainerHandler(screen.getScreenHandler())) {
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

        if (slot.inventory != currentScreenContainerInventory) {
            return null;
        }

        if (slot.getIndex() < 0 || slot.getIndex() >= currentScreenSlotOverlays.size()) {
            return null;
        }

        return currentScreenSlotOverlays.get(slot.getIndex());
    }

    static void bindCurrentScreen(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.currentScreen != screen) {
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
            currentScreenContainerPos = isSupportedContainerHandler(screen.getScreenHandler())
                    ? getLookedAtInventoryPos(client)
                    : null;
        }
    }

    static void refreshCurrentScreenVerifier(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world == null
                || currentScreenContainerPos == null) {
            return;
        }

        int currentRevision = screen.getScreenHandler().getRevision();

        long currentTick = client.world.getTime();

        if (currentTick == lastCurrentScreenRefreshTick && currentRevision == lastCurrentScreenRevision) {
            return;
        }

        lastCurrentScreenRefreshTick = currentTick;
        lastCurrentScreenRevision = currentRevision;
        currentScreenSlotOverlays = List.of();
        currentScreenContainerInventory = null;
        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        ExpectedContainer expectedContainer = getExpectedContainerAt(world, currentScreenContainerPos, placement);

        if (expectedContainer == null
                || !isSupportedHandlerForExpectedContainer(screen.getScreenHandler(), expectedContainer)) {
            clearCurrentScreenContainerBinding();
            return;
        }

        Inventory containerInventory = findContainerInventory(
                screen.getScreenHandler(),
                expectedContainer.inventory().size()
        );
        Inventory foundInventory = copyContainerInventoryFromScreen(
                screen.getScreenHandler(),
                containerInventory
        );

        if (foundInventory == null) {
            return;
        }

        currentScreenContainerInventory = containerInventory;
        Set<Integer> foundDisabledSlots = copyCrafterDisabledSlotsFromScreen(
                screen.getScreenHandler(),
                containerInventory
        );
        List<ContainerMismatch> mismatches = null;

        if (placement != null && placement.hasVerifier()) {
            VerifierExtension verifier = (VerifierExtension) placement.getSchematicVerifier();
            mismatches = verifier.quickcraft$refreshContainerMismatchAt(
                    currentScreenContainerPos,
                    foundInventory,
                    foundDisabledSlots
            );

            BlockPos pairedPos = getExpectedDoubleChestAdjacentPos(currentScreenContainerPos, placement);
            if (pairedPos != null) {
                // 大箱子的错误可能记录在另一半坐标；打开任意半边都同步刷新两半。
                verifier.quickcraft$refreshContainerMismatchAt(pairedPos, foundInventory, foundDisabledSlots);
            }
        }

        if (foundInventory.size() == expectedContainer.inventory().size()) {
            currentScreenSlotOverlays = mismatches != null
                    ? buildSlotOverlays(expectedContainer, mismatches)
                    : buildSlotOverlays(expectedContainer, foundInventory, foundDisabledSlots);
        }
    }

    static BlockPos getLookedAtInventoryPos(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof BlockHitResult blockHitResult)
                || blockHitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        World lookupWorld = world != null ? world : client.world;
        if (lookupWorld == null) {
            return null;
        }
        BlockEntity blockEntity = lookupWorld.getBlockEntity(blockHitResult.getBlockPos());

        return blockEntity instanceof Inventory ? blockHitResult.getBlockPos().toImmutable() : null;
    }

    static void clearCurrentScreenContainerBinding() {
        currentScreenContainerPos = null;
        lastCurrentScreenRefreshTick = Long.MIN_VALUE;
        lastCurrentScreenRevision = Integer.MIN_VALUE;
        currentScreenSlotOverlays = List.of();
        currentScreenContainerInventory = null;
    }

    static boolean isSupportedContainerHandler(ScreenHandler handler) {
        return handler instanceof HopperScreenHandler
                || handler instanceof GenericContainerScreenHandler
                || handler instanceof ShulkerBoxScreenHandler
                || handler instanceof Generic3x3ContainerScreenHandler
                || handler instanceof CrafterScreenHandler
                || handler instanceof FurnaceScreenHandler
                || handler instanceof BlastFurnaceScreenHandler
                || handler instanceof SmokerScreenHandler
                || handler instanceof BrewingStandScreenHandler;
    }

    static boolean isSupportedHandlerForExpectedContainer(ScreenHandler handler, ExpectedContainer expectedContainer) {
        QuickContainerCopy.PublicContainerType type = QuickContainerCopy.getPublicContainerType(
                expectedContainer.state().getBlock(),
                QuickLitematicaContainerInventory.getChestType(expectedContainer.state())
        );

        if (type == null) {
            return false;
        }

        return switch (type) {
            case HOPPER -> handler instanceof HopperScreenHandler;
            case SMALL_CHEST, BARREL -> handler instanceof GenericContainerScreenHandler genericHandler
                    && genericHandler.getRows() == 3;
            case LARGE_CHEST -> handler instanceof GenericContainerScreenHandler genericHandler
                    && genericHandler.getRows() == 6;
            case SHULKER_BOX -> handler instanceof ShulkerBoxScreenHandler;
            case DISPENSER, DROPPER -> handler instanceof Generic3x3ContainerScreenHandler;
            case CRAFTER -> handler instanceof CrafterScreenHandler;
            case FURNACE -> handler instanceof FurnaceScreenHandler;
            case BLAST_FURNACE -> handler instanceof BlastFurnaceScreenHandler;
            case SMOKER -> handler instanceof SmokerScreenHandler;
            case BREWING_STAND -> handler instanceof BrewingStandScreenHandler;
        };
    }

    static Inventory findContainerInventory(ScreenHandler handler, int expectedSize) {
        if (expectedSize <= 0) {
            return null;
        }

        for (Slot candidate : handler.slots) {
            Inventory inventory = candidate.inventory;
            if (inventory instanceof PlayerInventory || inventory.size() != expectedSize) {
                continue;
            }

            boolean[] visibleSlots = new boolean[expectedSize];
            for (Slot slot : handler.slots) {
                if (slot.inventory == inventory
                        && slot.getIndex() >= 0
                        && slot.getIndex() < expectedSize) {
                    visibleSlots[slot.getIndex()] = true;
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

    static Inventory copyContainerInventoryFromScreen(ScreenHandler handler, Inventory containerInventory) {
        if (containerInventory == null) {
            return null;
        }

        SimpleInventory inventory = new SimpleInventory(containerInventory.size());

        for (Slot slot : handler.slots) {
            if (slot.inventory == containerInventory
                    && slot.getIndex() >= 0
                    && slot.getIndex() < inventory.size()) {
                inventory.setStack(slot.getIndex(), slot.getStack().copy());
            }
        }

        return inventory;
    }

    static Set<Integer> copyCrafterDisabledSlotsFromScreen(
            ScreenHandler handler,
            Inventory containerInventory
    ) {
        if (!(handler instanceof CrafterScreenHandler crafterHandler)) {
            return Set.of();
        }

        Set<Integer> disabledSlots = new HashSet<>();

        for (Slot slot : handler.slots) {
            if (slot.inventory != containerInventory || slot.getIndex() < 0) {
                continue;
            }

            if (crafterHandler.isSlotDisabled(slot.id)) {
                disabledSlots.add(slot.getIndex());
            }
        }

        return disabledSlots;
    }

    static List<SlotOverlay> buildSlotOverlays(
            ExpectedContainer expectedContainer,
            Inventory foundInventory,
            Set<Integer> foundDisabledSlots
    ) {
        int size = expectedContainer.inventory().size();
        List<SlotOverlay> overlays = new ArrayList<>(size);

        // 打开大箱子时每帧都会绘制很多槽位，这里先按 tick 预计算一次，
        // 避免满潜影盒场景反复深比较内部组件导致高亮掉帧。
        for (int slot = 0; slot < size; slot++) {
            ItemStack expectedStack = expectedContainer.inventory().getStack(slot);
            SlotMismatchStatus status = QuickLitematicaContainerComparison.getSlotMismatchStatus(expectedStack, foundInventory.getStack(slot));

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
        int size = expectedContainer.inventory().size();
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
