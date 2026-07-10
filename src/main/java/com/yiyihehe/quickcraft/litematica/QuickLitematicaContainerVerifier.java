package com.yiyihehe.quickcraft.litematica;

import com.chocohead.mm.api.ClassTinkerers;
import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.loader.api.FabricLoader;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntitiesDataStorage;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.util.BlockInfoAlignment;
import fi.dy.masa.litematica.util.SchematicUtils;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.util.game.BlockUtils;
import fi.dy.masa.malilib.util.data.Constants;
import fi.dy.masa.malilib.util.nbt.NbtBlockUtils;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
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
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QuickCraft 的 Litematica 容器验证主类。
 * 这里统一收口原理图容器校验相关能力，包括：
 * 1. 容器库存和禁用槽位的比对与错填分类；
 * 2. 验证结果刷新、当前界面联动和容器错填重算；
 * 3. 容器界面里的幽灵物品辅助渲染；
 * 4. 提供给 mixin 挂接的 verifier / mismatch 扩展接口；
 * 5. 需要尽早注册的验证类型注入逻辑。
 *
 * TechUtils 只作为交互参考，这里不依赖它的任何 API。
 */
public final class QuickLitematicaContainerVerifier {
    public static final MismatchType WRONG_FILL = ClassTinkerers.getEnum(
            MismatchType.class,
            EarlyRiser.WRONG_FILL_ENUM
    );
    public static final MismatchType MISSING_FILL = ClassTinkerers.getEnum(
            MismatchType.class,
            EarlyRiser.MISSING_FILL_ENUM
    );
    public static final MismatchType EXTRA_FILL = ClassTinkerers.getEnum(
            MismatchType.class,
            EarlyRiser.EXTRA_FILL_ENUM
    );
    public static final MismatchType WRONG_FILL_STATE = ClassTinkerers.getEnum(
            MismatchType.class,
            EarlyRiser.WRONG_FILL_STATE_ENUM
    );
    private static boolean suppressInventorySlotHighlights;
    private static BlockPos pendingContainerPos;
    private static BlockPos currentScreenContainerPos;
    private static HandledScreen<?> currentHandledScreen;
    private static long lastCurrentScreenRefreshTick = Long.MIN_VALUE;
    private static int lastCurrentScreenRevision = Integer.MIN_VALUE;
    private static List<SlotOverlay> currentScreenSlotOverlays = List.of();
    private static ActualInventoryReadStatus lastActualInventoryReadStatus = ActualInventoryReadStatus.NOT_READ;

    private QuickLitematicaContainerVerifier() {
    }

    public static boolean isEnabled() {
        return QuickCraftConfigs.isLitematicaContainerVerifierEnabled();
    }

    public static boolean areSlotHintsVisible() {
        return isEnabled() && QuickCraftConfigs.isLitematicaContainerSlotHintsVisible();
    }

    public static boolean isContainerMismatchType(MismatchType type) {
        return type == WRONG_FILL
                || type == MISSING_FILL
                || type == EXTRA_FILL
                || type == WRONG_FILL_STATE;
    }

    public static List<MismatchType> getContainerMismatchTypes() {
        return List.of(WRONG_FILL, MISSING_FILL, WRONG_FILL_STATE);
    }

    public static Inventory getExpectedInventory(BlockEntity expectedBlockEntity, Inventory directInventory) {
        if (expectedBlockEntity == null || expectedBlockEntity.getWorld() == null) {
            return directInventory;
        }

        NbtCompound nbt = expectedBlockEntity.createNbtWithIdentifyingData(expectedBlockEntity.getWorld().getRegistryManager());

        if (nbt.contains("Items")) {
            Inventory nbtInventory = getNbtInventoryPreservingComponents(
                    nbt,
                    directInventory != null ? directInventory.size() : -1,
                    expectedBlockEntity.getWorld().getRegistryManager()
            );

            if (nbtInventory != null) {
                return nbtInventory;
            }
        }

        return directInventory;
    }

    public static Inventory getActualInventory(World world, BlockPos pos, Inventory directInventory, Inventory expected) {
        lastActualInventoryReadStatus = ActualInventoryReadStatus.NOT_READ;

        if (world == null) {
            lastActualInventoryReadStatus = ActualInventoryReadStatus.NO_WORLD;
            return null;
        }

        if (DataManager.getInstance().hasIntegratedServer()) {
            Inventory mergedOrDirect = getDirectInventory(world, pos, expected != null ? expected.size() : -1);
            lastActualInventoryReadStatus = mergedOrDirect != null || directInventory != null
                    ? ActualInventoryReadStatus.INTEGRATED_DIRECT
                    : ActualInventoryReadStatus.NO_DIRECT_INVENTORY;
            return mergedOrDirect != null ? mergedOrDirect : directInventory;
        }

        EntitiesDataStorage storage = EntitiesDataStorage.getInstance();
        NbtCompound cachedNbt = storage.getFromBlockEntityCacheNbt(pos);

        if (cachedNbt != null && !cachedNbt.contains("Items") && expected != null && isInventoryEmpty(expected)) {
            // 服务器空容器 NBT 可能只带 x/y/z/id，没有 Items；这表示已读到空库存。
            lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_INVENTORY;
            return new SimpleInventory(expected.size());
        }

        if (cachedNbt != null && (cachedNbt.contains("Items") || isInventoryEmpty(expected))) {
            Inventory cachedInventory = getCachedInventory(world, pos, storage, expected != null ? expected.size() : -1);

            if (cachedInventory != null) {
                lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_INVENTORY;
                return cachedInventory;
            }

            lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_PARSE_FAILED;
        } else if (cachedNbt != null) {
            lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_WITHOUT_ITEMS;
        } else {
            lastActualInventoryReadStatus = ActualInventoryReadStatus.NO_CACHE_NBT;
        }

        // 多人没有实体数据时不要拿客户端空壳库存硬比，避免把未知误报成错误填充。
        storage.requestBlockEntity(world, pos);
        return null;
    }

    private static Inventory getCachedInventory(World world, BlockPos pos, EntitiesDataStorage storage, int expectedSize) {
        Inventory merged = getMergedCachedDoubleChestInventory(world, pos, storage, expectedSize);

        if (merged != null) {
            return merged;
        }

        NbtCompound cachedNbt = storage.getFromBlockEntityCacheNbt(pos);
        Inventory special = getCachedSpecialInventory(world, cachedNbt, expectedSize);

        if (special != null) {
            return special;
        }

        return cachedNbt != null
                ? getNbtInventoryPreservingComponents(
                        cachedNbt,
                        expectedSize,
                        world.getRegistryManager()
                )
                : null;
    }

    private static Inventory getCachedSpecialInventory(World world, NbtCompound cachedNbt, int expectedSize) {
        if (world == null || cachedNbt == null || expectedSize != 1 || !cachedNbt.contains("RecordItem")) {
            return null;
        }

        SimpleInventory inventory = new SimpleInventory(1);
        inventory.setStack(
                0,
                cachedNbt.getCompound("RecordItem")
                        .map(nbt -> itemStackFromNbt(world.getRegistryManager(), nbt))
                        .orElse(ItemStack.EMPTY)
        );
        return inventory;
    }

    private static Inventory getMergedCachedDoubleChestInventory(World world, BlockPos pos, EntitiesDataStorage storage, int expectedSize) {
        if (expectedSize != 54) {
            return null;
        }

        BlockState state = world.getBlockState(pos);
        ChestType chestType = getChestType(state);

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = pos.add(ChestBlock.getFacing(state).getVector());
        NbtCompound currentNbt = storage.getFromBlockEntityCacheNbt(pos);
        NbtCompound adjacentNbt = storage.getFromBlockEntityCacheNbt(adjacentPos);

        if (currentNbt == null || adjacentNbt == null) {
            return null;
        }

        Inventory currentInventory = getNbtInventoryPreservingComponents(
                currentNbt,
                27,
                world.getRegistryManager()
        );
        Inventory adjacentInventory = getNbtInventoryPreservingComponents(
                adjacentNbt,
                27,
                world.getRegistryManager()
        );

        if (currentInventory == null || adjacentInventory == null) {
            return null;
        }

        return chestType == ChestType.RIGHT
                ? mergeInventories(currentInventory, adjacentInventory)
                : mergeInventories(adjacentInventory, currentInventory);
    }

    private static Inventory getNbtInventoryPreservingComponents(
            NbtCompound nbt,
            int expectedSize,
            RegistryWrapper.WrapperLookup registryLookup
    ) {
        if (nbt == null || registryLookup == null || !nbt.contains("Items")) {
            return null;
        }

        NbtList items = nbt.getList("Items").orElse(null);
        if (items == null) {
            return null;
        }
        int size = expectedSize > 0 ? expectedSize : inferInventorySize(items);
        if (size <= 0) {
            return null;
        }

        SimpleInventory inventory = new SimpleInventory(size);
        for (int i = 0; i < items.size(); i++) {
            NbtCompound itemNbt = items.getCompound(i).orElse(null);
            if (itemNbt == null) {
                continue;
            }
            int slot = itemNbt.getByte("Slot").orElse((byte) 0) & 255;
            if (slot < 0 || slot >= size) {
                continue;
            }

            ItemStack stack = itemStackFromNbt(registryLookup, itemNbt);
            if (!stack.isEmpty()) {
                inventory.setStack(slot, stack);
            }
        }

        return inventory;
    }

    private static ItemStack itemStackFromNbt(RegistryWrapper.WrapperLookup registryLookup, NbtCompound nbt) {
        return ItemStack.OPTIONAL_CODEC
                .parse(registryLookup.getOps(NbtOps.INSTANCE), nbt)
                .result()
                .orElse(ItemStack.EMPTY);
    }

    private static int inferInventorySize(NbtList items) {
        int size = 0;
        for (int i = 0; i < items.size(); i++) {
            NbtCompound itemNbt = items.getCompound(i).orElse(null);
            if (itemNbt != null) {
                size = Math.max(size, (itemNbt.getByte("Slot").orElse((byte) 0) & 255) + 1);
            }
        }
        return size;
    }

    public static ActualInventoryReadStatus getLastActualInventoryReadStatus() {
        return lastActualInventoryReadStatus;
    }

    public static void requestInventoryData(World world, BlockPos pos) {
        if (world != null) {
            EntitiesDataStorage.getInstance().requestBlockEntity(world, pos);
        }
    }

    public static boolean requestInventoryDataChunk(World world, ChunkPos chunkPos, int minY, int maxY) {
        if (world == null || DataManager.getInstance().hasIntegratedServer()) {
            return false;
        }

        EntitiesDataStorage storage = EntitiesDataStorage.getInstance();
        if (storage.hasServuxServer()) {
            storage.requestServuxBulkEntityData(chunkPos, minY, maxY);
            return true;
        }
        if (storage.getIfReceivedBackupPackets()) {
            storage.requestBackupBulkEntityData(chunkPos, minY, maxY);
            return true;
        }

        return false;
    }

    public static List<ContainerMismatch> findMismatches(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            BlockEntity expectedBlockEntity,
            BlockEntity foundBlockEntity,
            Inventory expected,
            Inventory found
    ) {
        return findMismatches(
                pos,
                expectedState,
                foundState,
                expectedBlockEntity,
                foundBlockEntity,
                getDisabledSlots(expectedBlockEntity),
                getDisabledSlots(foundBlockEntity),
                expected,
                found
        );
    }

    public static List<ContainerMismatch> findMismatches(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            BlockEntity expectedBlockEntity,
            BlockEntity foundBlockEntity,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            Inventory expected,
            Inventory found
    ) {
        Inventory expectedCopy = copyInventory(expected);
        Inventory foundCopy = copyInventory(found);
        List<SlotMismatch> slotMismatches = new ArrayList<>();
        boolean hasWrongItem = false;
        boolean hasMissing = false;
        boolean hasStateMismatch = false;
        boolean hasExpectedFilledSlot = false;
        boolean allExpectedFilledSlotsMissing = true;

        for (int slot = 0; slot < expected.size(); slot++) {
            ItemStack expectedStack = expected.getStack(slot);
            ItemStack foundStack = found.getStack(slot);
            SlotMismatchStatus status = getSlotMismatchStatus(expectedStack, foundStack);

            if (!expectedStack.isEmpty()) {
                hasExpectedFilledSlot = true;
                if (!foundStack.isEmpty()) {
                    allExpectedFilledSlotsMissing = false;
                }
            }

            if (status != null) {
                slotMismatches.add(new SlotMismatch(slot, status, expectedStack.copy(), foundStack.copy()));

                if (status == SlotMismatchStatus.WRONG || status == SlotMismatchStatus.EXTRA) {
                    hasWrongItem = true;
                } else if (status == SlotMismatchStatus.MISSING) {
                    hasMissing = true;
                } else if (status == SlotMismatchStatus.COUNT) {
                    hasStateMismatch = true;
                }
            }
        }

        if (!expectedDisabledSlots.equals(foundDisabledSlots)) {
            for (int slot : unionSlots(expectedDisabledSlots, foundDisabledSlots)) {
                if (expectedDisabledSlots.contains(slot) != foundDisabledSlots.contains(slot)) {
                    addSlotStatusIfEmpty(slotMismatches, slot, SlotMismatchStatus.LOCK_STATE, expected.getStack(slot), found.getStack(slot));
                }
            }

            hasStateMismatch = true;
        }

        if (slotMismatches.isEmpty()) {
            return List.of();
        }

        SlotMismatch first = slotMismatches.getFirst();
        MismatchType type;

        if (hasWrongItem) {
            type = WRONG_FILL;
        } else if (hasExpectedFilledSlot && allExpectedFilledSlotsMissing) {
            type = MISSING_FILL;
        } else if (hasStateMismatch) {
            type = WRONG_FILL_STATE;
        } else if (hasMissing) {
            type = MISSING_FILL;
        } else {
            type = WRONG_FILL;
        }

        return List.of(new ContainerMismatch(
                pos,
                expectedState,
                foundState,
                first.slot(),
                type,
                first.expectedStack(),
                first.foundStack(),
                expectedCopy,
                foundCopy,
                expectedDisabledSlots,
                foundDisabledSlots,
                List.copyOf(slotMismatches)
        ));
    }

    public static void renderInventoryPair(
            ContainerMismatch mismatch,
            BlockState expectedState,
            BlockState foundState,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            int mouseX,
            int mouseY,
            MinecraftClient mc,
            DrawContext drawContext
    ) {
        Pair<Inventory, Inventory> inventories = mismatch != null ? mismatch.inventories() : null;

        if (inventories == null) {
            return;
        }

        renderInventoryOverlay(BlockInfoAlignment.CENTER, LeftRight.LEFT, 0, mismatch.type(), inventories.getLeft(), expectedState, expectedDisabledSlots, List.of(), false, mouseX, mouseY, mc, drawContext);
        renderInventoryOverlay(BlockInfoAlignment.CENTER, LeftRight.RIGHT, 0, mismatch.type(), inventories.getRight(), foundState, foundDisabledSlots, mismatch.slotMismatches(), true, mouseX, mouseY, mc, drawContext);
    }

    public static boolean shouldSuppressInventorySlotHighlights() {
        return suppressInventorySlotHighlights && isEnabled();
    }

    public static void setSuppressInventorySlotHighlights(boolean suppress) {
        suppressInventorySlotHighlights = suppress;
    }

    public static void clearCurrentHandledScreenBinding() {
        pendingContainerPos = null;
        currentHandledScreen = null;
        clearCurrentScreenContainerBinding();
    }

    public static void drawGhostItem(
            DrawContext context,
            MinecraftClient client,
            ItemStack stack,
            int x,
            int y,
            int guiLeft,
            int guiTop,
            float alpha
    ) {
        GhostItemBuffer.drawGhostItem(context, client, stack, x, y, guiLeft, guiTop, alpha);
    }

    public static boolean beginHandledScreenGhostRender(DrawContext context, MinecraftClient client) {
        return client != null;
    }

    public static void endHandledScreenGhostRender(DrawContext context, int guiLeft, int guiTop, float alpha) {
    }

    public static void rememberContainerUse(MinecraftClient client, BlockHitResult hitResult) {
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

    public static SlotOverlay getSlotOverlayForScreen(HandledScreen<?> screen, Slot slot) {
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

        if (slot.getIndex() < 0 || slot.getIndex() >= currentScreenSlotOverlays.size()) {
            return null;
        }

        return currentScreenSlotOverlays.get(slot.getIndex());
    }

    private static SlotMismatchStatus getSlotMismatchStatus(ItemStack expectedStack, ItemStack foundStack) {
        boolean expectedEmpty = expectedStack.isEmpty();
        boolean foundEmpty = foundStack.isEmpty();

        if (expectedEmpty && foundEmpty) {
            return null;
        }
        if (!expectedEmpty && foundEmpty) {
            return SlotMismatchStatus.MISSING;
        }
        if (expectedEmpty) {
            return SlotMismatchStatus.EXTRA;
        }
        if (!ItemStack.areItemsAndComponentsEqual(expectedStack, foundStack)) {
            return SlotMismatchStatus.WRONG;
        }
        if (expectedStack.getCount() != foundStack.getCount()) {
            return SlotMismatchStatus.COUNT;
        }

        return null;
    }

    private static boolean isSlotLockMismatch(Set<Integer> expectedDisabledSlots, Set<Integer> foundDisabledSlots, int slot) {
        return expectedDisabledSlots.contains(slot) != foundDisabledSlots.contains(slot);
    }

    public static ExpectedContainer getExpectedContainerAt(World foundWorld, BlockPos pos) {
        if (foundWorld == null) {
            return null;
        }

        ExpectedContainer current = getExpectedContainerInternal(pos);

        if (current == null) {
            return null;
        }

        ExpectedContainer merged = getExpectedDoubleChestContainer(pos, current);

        return merged != null ? merged : current;
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshotAt(World foundWorld, BlockPos pos) {
        ExpectedContainer expected = getExpectedContainerAt(foundWorld, pos);
        if (expected == null) {
            return null;
        }

        QuickContainerCopy.PublicContainerType type = QuickContainerCopy.getPublicContainerType(
                expected.state().getBlock(),
                getChestType(expected.state())
        );
        if (type == null) {
            return null;
        }

        List<ItemStack> templates = new ArrayList<>(expected.inventory().size());
        List<Boolean> disabledStates = new ArrayList<>(expected.inventory().size());
        for (int i = 0; i < expected.inventory().size(); i++) {
            ItemStack stack = expected.inventory().getStack(i);
            templates.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            disabledStates.add(expected.disabledSlots().contains(i));
        }

        return new QuickContainerCopy.TemplateSnapshot(type, templates, disabledStates);
    }

    private static Inventory getDirectInventory(World world, BlockPos pos, int expectedSize) {
        Inventory merged = getMergedDoubleChestInventory(world, pos);

        if (merged != null && (expectedSize < 0 || merged.size() == expectedSize)) {
            return merged;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof Inventory inventory ? inventory : null;
    }

    public static Set<Integer> getDisabledSlots(BlockEntity blockEntity) {
        if (blockEntity != null && blockEntity.getWorld() != null) {
            NbtCompound nbt = blockEntity.createNbtWithIdentifyingData(blockEntity.getWorld().getRegistryManager());
            return getDisabledSlots(blockEntity, nbt);
        }

        if (blockEntity instanceof CrafterBlockEntity crafter) {
            return Set.copyOf(BlockUtils.getDisabledSlots(crafter));
        }

        return Set.of();
    }

    private static void addSlotStatusIfEmpty(
            List<SlotMismatch> slotMismatches,
            int slot,
            SlotMismatchStatus status,
            ItemStack expectedStack,
            ItemStack foundStack
    ) {
        for (SlotMismatch mismatch : slotMismatches) {
            if (mismatch.slot() == slot) {
                return;
            }
        }

        slotMismatches.add(new SlotMismatch(slot, status, expectedStack.copy(), foundStack.copy()));
    }

    private static Set<Integer> unionSlots(Set<Integer> left, Set<Integer> right) {
        java.util.HashSet<Integer> slots = new java.util.HashSet<>(left);
        slots.addAll(right);
        return slots;
    }

    private static void bindCurrentScreen(HandledScreen<?> screen) {
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
        }

        if (currentScreenContainerPos == null) {
            currentScreenContainerPos = isSupportedContainerHandler(screen.getScreenHandler())
                    ? getLookedAtInventoryPos(client)
                    : null;
        }
    }

    private static void refreshCurrentScreenVerifier(HandledScreen<?> screen) {
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
        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        ExpectedContainer expectedContainer = getExpectedContainerAt(world, currentScreenContainerPos);

        if (expectedContainer == null
                || !isSupportedHandlerForExpectedContainer(screen.getScreenHandler(), expectedContainer)) {
            clearCurrentScreenContainerBinding();
            return;
        }

        Inventory foundInventory = copyContainerInventoryFromScreen(screen.getScreenHandler());

        if (foundInventory == null) {
            return;
        }

        Set<Integer> foundDisabledSlots = copyCrafterDisabledSlotsFromScreen(screen.getScreenHandler());
        List<ContainerMismatch> mismatches = null;
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement != null && placement.hasVerifier()) {
            VerifierExtension verifier = (VerifierExtension) placement.getSchematicVerifier();
            mismatches = verifier.quickcraft$refreshContainerMismatchAt(
                    currentScreenContainerPos,
                    foundInventory,
                    foundDisabledSlots
            );

            BlockPos pairedPos = getExpectedDoubleChestAdjacentPos(currentScreenContainerPos);
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

    private static BlockPos getLookedAtInventoryPos(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof BlockHitResult blockHitResult)
                || blockHitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        BlockEntity blockEntity = world != null
                ? world.getBlockEntity(blockHitResult.getBlockPos())
                : client.world.getBlockEntity(blockHitResult.getBlockPos());

        return blockEntity instanceof Inventory ? blockHitResult.getBlockPos().toImmutable() : null;
    }

    private static void clearCurrentScreenContainerBinding() {
        currentScreenContainerPos = null;
        lastCurrentScreenRefreshTick = Long.MIN_VALUE;
        lastCurrentScreenRevision = Integer.MIN_VALUE;
        currentScreenSlotOverlays = List.of();
    }

    private static boolean isSupportedContainerHandler(ScreenHandler handler) {
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

    private static boolean isSupportedHandlerForExpectedContainer(ScreenHandler handler, ExpectedContainer expectedContainer) {
        QuickContainerCopy.PublicContainerType type = QuickContainerCopy.getPublicContainerType(
                expectedContainer.state().getBlock(),
                getChestType(expectedContainer.state())
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

    private static Inventory copyContainerInventoryFromScreen(ScreenHandler handler) {
        int maxIndex = -1;

        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) && slot.getIndex() > maxIndex) {
                maxIndex = slot.getIndex();
            }
        }

        if (maxIndex < 0) {
            return null;
        }

        SimpleInventory inventory = new SimpleInventory(maxIndex + 1);

        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)
                    && slot.getIndex() >= 0
                    && slot.getIndex() < inventory.size()) {
                inventory.setStack(slot.getIndex(), slot.getStack().copy());
            }
        }

        return inventory;
    }

    private static Set<Integer> copyCrafterDisabledSlotsFromScreen(ScreenHandler handler) {
        if (!(handler instanceof CrafterScreenHandler crafterHandler)) {
            return Set.of();
        }

        Set<Integer> disabledSlots = new HashSet<>();

        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory || slot.getIndex() < 0) {
                continue;
            }

            if (crafterHandler.isSlotDisabled(slot.id)) {
                disabledSlots.add(slot.getIndex());
            }
        }

        return disabledSlots;
    }

    private static List<SlotOverlay> buildSlotOverlays(
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
            SlotMismatchStatus status = getSlotMismatchStatus(expectedStack, foundInventory.getStack(slot));

            if (status == null && isSlotLockMismatch(
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

    private static List<SlotOverlay> buildSlotOverlays(
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

    private static ExpectedContainer getExpectedDoubleChestContainer(BlockPos pos, ExpectedContainer current) {
        ChestType chestType = getChestType(current.state());

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = pos.add(ChestBlock.getFacing(current.state()).getVector());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos);

        if (adjacent == null) {
            return null;
        }

        Inventory currentInventory = current.inventory();
        Inventory adjacentInventory = adjacent.inventory();
        Inventory merged = chestType == ChestType.RIGHT
                ? mergeInventories(currentInventory, adjacentInventory)
                : mergeInventories(adjacentInventory, currentInventory);

        return new ExpectedContainer(
                pos,
                current.state(),
                current.blockEntity(),
                merged,
                Set.of()
        );
    }

    public static BlockPos getExpectedDoubleChestAdjacentPos(BlockPos pos) {
        ExpectedContainer current = getExpectedContainerInternal(pos);

        if (current == null || getChestType(current.state()) == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = pos.add(ChestBlock.getFacing(current.state()).getVector());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos);
        return adjacent != null && getChestType(adjacent.state()) != ChestType.SINGLE
                ? adjacentPos
                : null;
    }

    private static ExpectedContainer getExpectedContainerInternal(BlockPos worldPos) {
        LocalPlacementPos placementPos = getLocalPlacementPos(worldPos);

        if (placementPos == null) {
            return null;
        }

        Map<BlockPos, NbtCompound> blockEntities = placementPos.placement().getSchematic()
                .getBlockEntityMapForRegion(placementPos.region());

        if (blockEntities == null) {
            return null;
        }

        NbtCompound nbt = blockEntities.get(placementPos.pos());

        if (nbt == null) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world == null) {
            return null;
        }

        BlockEntity blockEntity = BlockEntity.createFromNbt(
                placementPos.pos(),
                placementPos.rawState(),
                nbt,
                client.world.getRegistryManager()
        );

        if (!(blockEntity instanceof Inventory inventory)) {
            return null;
        }

        return new ExpectedContainer(
                worldPos,
                placementPos.worldState(),
                blockEntity,
                copyInventory(inventory),
                getDisabledSlots(blockEntity, nbt)
        );
    }

    private static LocalPlacementPos getLocalPlacementPos(BlockPos worldPos) {
        List<SchematicPlacementManager.PlacementPart> parts = DataManager.getSchematicPlacementManager()
                .getAllPlacementsTouchingChunk(worldPos);

        for (SchematicPlacementManager.PlacementPart part : parts) {
            if (!part.getBox().containsPos(worldPos)) {
                continue;
            }

            SchematicPlacement placement = part.getPlacement();
            String region = part.getSubRegionName();
            LitematicaSchematic schematic = placement.getSchematic();
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);
            SubRegionPlacement subRegionPlacement = placement.getRelativeSubRegionPlacement(region);

            if (container == null || subRegionPlacement == null) {
                continue;
            }

            BlockPos schematicPos = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                    worldPos,
                    schematic,
                    region,
                    placement,
                    subRegionPlacement,
                    container
            );

            if (schematicPos == null) {
                continue;
            }

            BlockState rawState = container.get(schematicPos.getX(), schematicPos.getY(), schematicPos.getZ());
            BlockState worldState = getTransformedWorldState(rawState, placement, region);

            return new LocalPlacementPos(schematicPos, region, placement, rawState, worldState);
        }

        return null;
    }

    private static BlockState getTransformedWorldState(BlockState state, SchematicPlacement schematicPlacement, String region) {
        SubRegionPlacement placement = schematicPlacement.getRelativeSubRegionPlacement(region);

        if (placement == null) {
            return state;
        }

        BlockRotation rotationCombined = schematicPlacement.getRotation().rotate(placement.getRotation());
        BlockMirror mirrorMain = schematicPlacement.getMirror();
        BlockMirror mirrorSub = placement.getMirror();

        if (mirrorSub != BlockMirror.NONE
                && (schematicPlacement.getRotation() == BlockRotation.CLOCKWISE_90
                || schematicPlacement.getRotation() == BlockRotation.COUNTERCLOCKWISE_90)) {
            mirrorSub = mirrorSub == BlockMirror.FRONT_BACK ? BlockMirror.LEFT_RIGHT : BlockMirror.FRONT_BACK;
        }

        if (mirrorMain != BlockMirror.NONE) {
            state = state.mirror(mirrorMain);
        }
        if (mirrorSub != BlockMirror.NONE) {
            state = state.mirror(mirrorSub);
        }
        if (rotationCombined != BlockRotation.NONE) {
            state = state.rotate(rotationCombined);
        }

        return state;
    }

    private static Inventory getMergedDoubleChestInventory(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        ChestType chestType = getChestType(state);

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = pos.add(ChestBlock.getFacing(state).getVector());
        BlockEntity current = world.getBlockEntity(pos);
        BlockEntity adjacent = world.getBlockEntity(adjacentPos);

        if (!(current instanceof Inventory currentInventory)
                || !(adjacent instanceof Inventory adjacentInventory)) {
            return null;
        }

        return chestType == ChestType.RIGHT
                ? mergeInventories(currentInventory, adjacentInventory)
                : mergeInventories(adjacentInventory, currentInventory);
    }

    private static Inventory mergeInventories(Inventory first, Inventory second) {
        SimpleInventory inventory = new SimpleInventory(first.size() + second.size());

        for (int i = 0; i < first.size(); i++) {
            inventory.setStack(i, first.getStack(i).copy());
        }

        for (int i = 0; i < second.size(); i++) {
            inventory.setStack(first.size() + i, second.getStack(i).copy());
        }

        return inventory;
    }

    private static ChestType getChestType(BlockState state) {
        return state.getBlock() instanceof ChestBlock ? state.get(ChestBlock.CHEST_TYPE) : ChestType.SINGLE;
    }

    private static Set<Integer> getDisabledSlots(BlockEntity blockEntity, NbtCompound nbt) {
        // 投影和实际世界都优先按 NBT 里的 disabled_slots 比较，避免两边来源不同导致合成器锁槽误判。
        if (nbt != null && nbt.contains("disabled_slots")) {
            return Set.copyOf(NbtBlockUtils.getDisabledSlotsFromNbt(nbt));
        }

        if (blockEntity instanceof CrafterBlockEntity crafter) {
            return Set.copyOf(BlockUtils.getDisabledSlots(crafter));
        }

        return Set.of();
    }

    private static SimpleInventory copyInventory(Inventory source) {
        SimpleInventory copy = new SimpleInventory(source.size());

        for (int i = 0; i < source.size(); i++) {
            copy.setStack(i, source.getStack(i).copy());
        }

        return copy;
    }

    private static void renderInventoryOverlay(
            BlockInfoAlignment align,
            LeftRight side,
            int offY,
            MismatchType mismatchType,
            Inventory inventory,
            BlockState state,
            Set<Integer> disabledSlots,
            List<SlotMismatch> slotMismatches,
            boolean renderGhostStacks,
            double mouseX,
            double mouseY,
            MinecraftClient mc,
            DrawContext drawContext
    ) {
        InventoryOverlay.InventoryRenderType type = getInventoryType(inventory, state);
        InventoryOverlay.InventoryProperties props = InventoryOverlay.getInventoryPropsTemp(type, inventory.size());
        int xInv = 0;
        int yInv = 0;

        switch (align) {
            case CENTER -> {
                xInv = fi.dy.masa.malilib.util.GuiUtils.getScaledWindowWidth() / 2 - (props.width / 2);
                yInv = fi.dy.masa.malilib.util.GuiUtils.getScaledWindowHeight() / 2 - props.height - offY;
            }
            case TOP_CENTER -> {
                xInv = fi.dy.masa.malilib.util.GuiUtils.getScaledWindowWidth() / 2 - (props.width / 2);
                yInv = offY;
            }
        }

        if (side == LeftRight.LEFT) {
            xInv -= props.width / 2 + 4;
        } else if (side == LeftRight.RIGHT) {
            xInv += props.width / 2 + 4;
        }

        InventoryOverlay.renderInventoryBackground(drawContext, type, xInv, yInv, props.slotsPerRow, props.totalSlots, mc);
        drawSlotHighlights(drawContext, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        InventoryOverlay.renderInventoryStacks(drawContext, type, inventory, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, 0, inventory.size(), disabledSlots, mc);

        if (renderGhostStacks) {
            drawMissingGhostStacks(drawContext, mc, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        }
    }

    private static void drawSlotHighlights(
            DrawContext drawContext,
            InventoryOverlay.InventoryRenderType type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            List<SlotMismatch> slotMismatches
    ) {
        for (SlotMismatch mismatch : slotMismatches) {
            SlotPosition pos = getInventoryOverlaySlotPosition(type, xSlots, ySlots, slotsPerRow, mismatch.slot());
            int x = pos.x();
            int y = pos.y();
            drawContext.fill(x, y, x + 16, y + 16, mismatch.status().fillColor());
            drawOutline(drawContext, x, y, 16, 16, mismatch.status().borderColor());
        }
    }

    private static void drawMissingGhostStacks(
            DrawContext drawContext,
            MinecraftClient mc,
            InventoryOverlay.InventoryRenderType type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            List<SlotMismatch> slotMismatches
    ) {
        for (SlotMismatch mismatch : slotMismatches) {
            if (mismatch.status() != SlotMismatchStatus.MISSING || mismatch.expectedStack().isEmpty()) {
                continue;
            }

            SlotPosition pos = getInventoryOverlaySlotPosition(type, xSlots, ySlots, slotsPerRow, mismatch.slot());
            int x = pos.x();
            int y = pos.y();
            drawGhostItem(
                    drawContext,
                    mc,
                    mismatch.expectedStack(),
                    x,
                    y,
                    0,
                    0,
                    QuickLitematicaVerifierPalette.ghostItemAlpha()
            );
            drawOutline(drawContext, x, y, 16, 16, mismatch.status().borderColor());
        }
    }

    private static SlotPosition getInventoryOverlaySlotPosition(
            InventoryOverlay.InventoryRenderType type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            int slot
    ) {
        // 炉子类和酿造台在 malilib 的 InventoryOverlay 里不是普通网格槽位。
        if (type == InventoryOverlay.InventoryRenderType.FURNACE) {
            return switch (slot) {
                case 0 -> new SlotPosition(xSlots + 8, ySlots + 8);
                case 1 -> new SlotPosition(xSlots + 8, ySlots + 44);
                case 2 -> new SlotPosition(xSlots + 68, ySlots + 26);
                default -> getGridSlotPosition(xSlots, ySlots, slotsPerRow, slot);
            };
        }

        if (type == InventoryOverlay.InventoryRenderType.BREWING_STAND) {
            return switch (slot) {
                case 0 -> new SlotPosition(xSlots + 47, ySlots + 42);
                case 1 -> new SlotPosition(xSlots + 70, ySlots + 49);
                case 2 -> new SlotPosition(xSlots + 93, ySlots + 42);
                case 3 -> new SlotPosition(xSlots + 70, ySlots + 8);
                case 4 -> new SlotPosition(xSlots + 8, ySlots + 8);
                default -> getGridSlotPosition(xSlots, ySlots, slotsPerRow, slot);
            };
        }

        return getGridSlotPosition(xSlots, ySlots, slotsPerRow, slot);
    }

    private static SlotPosition getGridSlotPosition(int xSlots, int ySlots, int slotsPerRow, int slot) {
        return new SlotPosition(
                xSlots + (slot % slotsPerRow) * 18,
                ySlots + (slot / slotsPerRow) * 18
        );
    }

    private static void drawOutline(DrawContext drawContext, int x, int y, int width, int height, int color) {
        drawContext.fill(x, y, x + width, y + 1, color);
        drawContext.fill(x, y + height - 1, x + width, y + height, color);
        drawContext.fill(x, y + 1, x + 1, y + height - 1, color);
        drawContext.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private static InventoryOverlay.InventoryRenderType getInventoryType(Inventory inventory, BlockState state) {
        if (state != null) {
            if (state.getBlock() instanceof AbstractFurnaceBlock) {
                return InventoryOverlay.InventoryRenderType.FURNACE;
            }
            if (state.getBlock() instanceof BrewingStandBlock) {
                return InventoryOverlay.InventoryRenderType.BREWING_STAND;
            }
            if (state.getBlock() instanceof CrafterBlock) {
                return InventoryOverlay.InventoryRenderType.CRAFTER;
            }
            if (state.getBlock() instanceof DispenserBlock) {
                return InventoryOverlay.InventoryRenderType.DISPENSER;
            }
            if (state.getBlock() instanceof HopperBlock) {
                return InventoryOverlay.InventoryRenderType.HOPPER;
            }
        }

        return switch (inventory.size()) {
            case 3 -> InventoryOverlay.InventoryRenderType.FURNACE;
            case 5 -> InventoryOverlay.InventoryRenderType.HOPPER;
            case 9 -> InventoryOverlay.InventoryRenderType.DISPENSER;
            case 27 -> InventoryOverlay.InventoryRenderType.FIXED_27;
            case 54 -> InventoryOverlay.InventoryRenderType.FIXED_54;
            default -> InventoryOverlay.InventoryRenderType.GENERIC;
        };
    }

    private static boolean isInventoryEmpty(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    public record ContainerMismatch(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            int slot,
            MismatchType type,
            ItemStack expectedStack,
            ItemStack foundStack,
            Inventory expectedInventory,
            Inventory foundInventory,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            List<SlotMismatch> slotMismatches
    ) {
        public Pair<Inventory, Inventory> inventories() {
            return Pair.of(this.expectedInventory, this.foundInventory);
        }

        public ContainerMismatchKey key() {
            return new ContainerMismatchKey(this.type, this.pos, this.slotMismatchSignature());
        }

        private String slotMismatchSignature() {
            StringBuilder builder = new StringBuilder();

            for (SlotMismatch mismatch : this.slotMismatches) {
                builder.append(mismatch.slot())
                        .append(':')
                        .append(mismatch.status().name())
                        .append(';');
            }

            return builder.toString();
        }
    }

    public record ExpectedContainer(
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            Inventory inventory,
            Set<Integer> disabledSlots
    ) {
    }

    public enum ActualInventoryReadStatus {
        NOT_READ,
        NO_WORLD,
        INTEGRATED_DIRECT,
        NO_DIRECT_INVENTORY,
        CACHE_INVENTORY,
        NO_CACHE_NBT,
        CACHE_WITHOUT_ITEMS,
        CACHE_PARSE_FAILED
    }

    private record LocalPlacementPos(
            BlockPos pos,
            String region,
            SchematicPlacement placement,
            BlockState rawState,
            BlockState worldState
    ) {
    }

    public record ContainerMismatchKey(
            MismatchType type,
            BlockPos pos,
            String slotSignature
    ) {
    }

    public record SlotMismatch(
            int slot,
            SlotMismatchStatus status,
            ItemStack expectedStack,
            ItemStack foundStack
    ) {
    }

    private record SlotPosition(int x, int y) {
    }

    public record SlotOverlay(
            SlotMismatchStatus status,
            ItemStack expectedStack
    ) {
        public int fillColor() {
            return this.status.fillColor();
        }

        public int borderColor() {
            return this.status.borderColor();
        }

    }

    /**
     * 给 Litematica 原版验证器补充 QuickCraft 容器校验状态访问接口。
     * 供界面、列表和交互逻辑读取容器错填统计与刷新入口。
     */
    public interface VerifierExtension {
        List<BlockMismatch> quickcraft$getSelectedInventoryMismatches();

        int quickcraft$getWrongInventoryCount();

        int quickcraft$getContainerMismatchCount(MismatchType type);

        int quickcraft$getExpectedContainerCount();

        int quickcraft$getCheckedContainerCount();

        int quickcraft$getPendingContainerCount();

        List<ContainerMismatch> quickcraft$refreshContainerMismatchAt(BlockPos pos, Inventory foundInventory, Set<Integer> foundDisabledSlots);
    }

    /**
     * 给 Litematica 的 BlockMismatch 挂接容器校验附加数据。
     * 这里保存容器对比结果、库存引用和禁用槽位信息，供列表与悬浮预览复用。
     */
    public interface BlockMismatchExtension {
        void quickcraft$setInventories(Pair<Inventory, Inventory> inventories);

        Pair<Inventory, Inventory> quickcraft$getInventories();

        void quickcraft$setContainerMismatch(ContainerMismatch mismatch);

        ContainerMismatch quickcraft$getContainerMismatch();

        void quickcraft$setContainerMismatchKey(ContainerMismatchKey key);

        ContainerMismatchKey quickcraft$getContainerMismatchKey();

        void quickcraft$setDisabledSlots(Set<Integer> expectedDisabledSlots, Set<Integer> foundDisabledSlots);

        Set<Integer> quickcraft$getExpectedDisabledSlots();

        Set<Integer> quickcraft$getFoundDisabledSlots();
    }

    public enum SlotMismatchStatus {
        MISSING,
        WRONG,
        EXTRA,
        COUNT,
        LOCK_STATE;

        public int fillColor() {
            return QuickLitematicaVerifierPalette.slotFillColor(this.mismatchType());
        }

        public int borderColor() {
            return QuickLitematicaVerifierPalette.slotBorderColor(this.mismatchType());
        }

        private MismatchType mismatchType() {
            return switch (this) {
                case MISSING -> MISSING_FILL;
                case WRONG -> WRONG_FILL;
                case EXTRA -> EXTRA_FILL;
                case COUNT, LOCK_STATE -> WRONG_FILL_STATE;
            };
        }
    }

    /**
     * 在 Litematica 类加载前补充验证器枚举。
     * 这样容器填充错误可以复用原版验证器按钮、列表、高亮和 HUD。
     */
    public static class EarlyRiser implements Runnable {
        public static final String WRONG_FILL_ENUM = "QUICKCRAFT_WRONG_FILL";
        public static final String MISSING_FILL_ENUM = "QUICKCRAFT_MISSING_FILL";
        public static final String EXTRA_FILL_ENUM = "QUICKCRAFT_EXTRA_FILL";
        public static final String WRONG_FILL_STATE_ENUM = "QUICKCRAFT_WRONG_FILL_STATE";

        @Override
        public void run() {
            if (!FabricLoader.getInstance().isModLoaded("litematica")) {
                return;
            }

            ClassTinkerers.enumBuilder(
                            "fi.dy.masa.litematica.schematic.verifier.SchematicVerifier$MismatchType",
                            int.class,
                            String.class,
                            String.class
                    )
                    .addEnum(
                            WRONG_FILL_ENUM,
                            QuickLitematicaVerifierPalette.wrongFillRgb(),
                            "litematica.gui.label.schematic_verifier_display_type.quickcraft_wrong_fill",
                            QuickLitematicaVerifierPalette.wrongFillFormattingCode()
                    )
                    .addEnum(
                            MISSING_FILL_ENUM,
                            QuickLitematicaVerifierPalette.missingFillRgb(),
                            "litematica.gui.label.schematic_verifier_display_type.quickcraft_missing_fill",
                            QuickLitematicaVerifierPalette.missingFillFormattingCode()
                    )
                    .addEnum(
                            EXTRA_FILL_ENUM,
                            QuickLitematicaVerifierPalette.extraFillRgb(),
                            "litematica.gui.label.schematic_verifier_display_type.quickcraft_extra_fill",
                            QuickLitematicaVerifierPalette.extraFillFormattingCode()
                    )
                    .addEnum(
                            WRONG_FILL_STATE_ENUM,
                            QuickLitematicaVerifierPalette.wrongFillStateRgb(),
                            "litematica.gui.label.schematic_verifier_display_type.quickcraft_wrong_fill_state",
                            QuickLitematicaVerifierPalette.wrongFillStateFormattingCode()
                    )
                    .build();
        }
    }

    private static final class GhostItemBuffer {
        // 原版槽位灰底与缺失槽位蓝色底纹合成后的颜色；用它覆盖不透明物品，模拟 30% alpha 的幽灵效果。
        private static final int MISSING_SLOT_GHOST_MASK = 0xB36E87AC;

        private GhostItemBuffer() {
        }

        private static void drawGhostItem(
                DrawContext context,
                MinecraftClient client,
                ItemStack stack,
                int x,
                int y,
                int guiLeft,
                int guiTop,
                float alpha
        ) {
            if (stack.isEmpty()) {
                return;
            }

            context.drawItem(stack, x, y);
            context.drawStackOverlay(client.textRenderer, stack, x, y);
            int maskAlpha = Math.round((1.0F - Math.max(0.0F, Math.min(1.0F, alpha))) * 255.0F);
            context.fill(x, y, x + 16, y + 16, (maskAlpha << 24) | (MISSING_SLOT_GHOST_MASK & 0x00FFFFFF));
        }
    }
}
