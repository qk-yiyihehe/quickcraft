package com.yiyihehe.quickcraft.litematica;

import com.chocohead.mm.api.ClassTinkerers;
import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.loader.api.FabricLoader;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntityDataManager;
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
import fi.dy.masa.malilib.render.InventoryOverlayType;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.game.BlockUtils;
import fi.dy.masa.malilib.util.data.Constants;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
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
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

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
    private static AbstractContainerScreen<?> currentHandledScreen;
    private static long lastCurrentScreenRefreshTick = Long.MIN_VALUE;
    private static int lastCurrentScreenRevision = Integer.MIN_VALUE;
    private static List<SlotOverlay> currentScreenSlotOverlays = List.of();
    private static Container currentScreenContainerInventory;
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

    public static Container getExpectedInventory(BlockEntity expectedBlockEntity, Container directInventory) {
        if (expectedBlockEntity == null) {
            return directInventory;
        }

        Level blockEntityWorld = expectedBlockEntity.getLevel();
        if (blockEntityWorld == null) {
            return directInventory;
        }

        CompoundTag nbt = expectedBlockEntity.saveWithFullMetadata(blockEntityWorld.registryAccess());

        if (nbt.contains("Items")) {
            Container nbtInventory = getNbtInventoryPreservingComponents(
                    nbt,
                    directInventory != null ? directInventory.getContainerSize() : -1,
                    blockEntityWorld.registryAccess()
            );

            if (nbtInventory != null) {
                return nbtInventory;
            }
        }

        return directInventory;
    }

    public static Container getActualInventory(Level world, BlockPos pos, Container directInventory, Container expected) {
        lastActualInventoryReadStatus = ActualInventoryReadStatus.NOT_READ;

        if (world == null) {
            lastActualInventoryReadStatus = ActualInventoryReadStatus.NO_WORLD;
            return null;
        }

        if (directInventory != null
                && expected != null
                && directInventory.getContainerSize() == expected.getContainerSize()
                && !isInventoryEmpty(directInventory)) {
            lastActualInventoryReadStatus = ActualInventoryReadStatus.DIRECT_INVENTORY;
            return directInventory;
        }

        if (DataManager.getInstance().hasIntegratedServer()) {
            Container mergedOrDirect = getDirectInventory(world, pos, expected != null ? expected.getContainerSize() : -1);
            lastActualInventoryReadStatus = mergedOrDirect != null || directInventory != null
                    ? ActualInventoryReadStatus.INTEGRATED_DIRECT
                    : ActualInventoryReadStatus.NO_DIRECT_INVENTORY;
            return mergedOrDirect != null ? mergedOrDirect : directInventory;
        }

        EntityDataManager storage = EntityDataManager.getInstance();
        CompoundTag cachedNbt = storage.getCache().getBlockEntityNbtFromCache(pos);

        if (cachedNbt != null && !cachedNbt.contains("Items") && expected != null && isInventoryEmpty(expected)) {
            // 服务器空容器 NBT 可能只带 x/y/z/id，没有 Items；这表示已读到空库存。
            lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_INVENTORY;
            return new SimpleContainer(expected.getContainerSize());
        }

        if (cachedNbt != null && (cachedNbt.contains("Items") || isInventoryEmpty(expected))) {
            Container cachedInventory = getCachedInventory(world, pos, storage, expected != null ? expected.getContainerSize() : -1);

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
        storage.requestBlockEntityWrapped(world, pos);
        return null;
    }

    private static Container getCachedInventory(Level world, BlockPos pos, EntityDataManager storage, int expectedSize) {
        Container merged = getMergedCachedDoubleChestInventory(world, pos, storage, expectedSize);

        if (merged != null) {
            return merged;
        }

        CompoundTag cachedNbt = storage.getCache().getBlockEntityNbtFromCache(pos);
        Container special = getCachedSpecialInventory(world, cachedNbt, expectedSize);

        if (special != null) {
            return special;
        }

        return cachedNbt != null
                ? getNbtInventoryPreservingComponents(
                        cachedNbt,
                        expectedSize,
                        world.registryAccess()
                )
                : null;
    }

    private static Container getCachedSpecialInventory(Level world, CompoundTag cachedNbt, int expectedSize) {
        if (world == null || cachedNbt == null || expectedSize != 1 || !cachedNbt.contains("RecordItem")) {
            return null;
        }

        SimpleContainer inventory = new SimpleContainer(1);
        inventory.setItem(
                0,
                cachedNbt.getCompound("RecordItem")
                        .map(nbt -> itemStackFromNbt(world.registryAccess(), nbt))
                        .orElse(ItemStack.EMPTY)
        );
        return inventory;
    }

    private static Container getMergedCachedDoubleChestInventory(Level world, BlockPos pos, EntityDataManager storage, int expectedSize) {
        if (expectedSize != 54) {
            return null;
        }

        BlockState state = world.getBlockState(pos);
        ChestType chestType = getChestType(state);

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = ChestBlock.getConnectedBlockPos(pos, state);
        CompoundTag currentNbt = storage.getCache().getBlockEntityNbtFromCache(pos);
        CompoundTag adjacentNbt = storage.getCache().getBlockEntityNbtFromCache(adjacentPos);

        if (currentNbt == null || adjacentNbt == null) {
            return null;
        }

        Container currentInventory = getNbtInventoryPreservingComponents(
                currentNbt,
                27,
                world.registryAccess()
        );
        Container adjacentInventory = getNbtInventoryPreservingComponents(
                adjacentNbt,
                27,
                world.registryAccess()
        );

        if (currentInventory == null || adjacentInventory == null) {
            return null;
        }

        return chestType == ChestType.RIGHT
                ? mergeInventories(currentInventory, adjacentInventory)
                : mergeInventories(adjacentInventory, currentInventory);
    }

    private static Container getNbtInventoryPreservingComponents(
            CompoundTag nbt,
            int expectedSize,
            HolderLookup.Provider registryLookup
    ) {
        if (nbt == null || registryLookup == null || !nbt.contains("Items")) {
            return null;
        }

        ListTag items = nbt.getList("Items").orElse(null);
        if (items == null) {
            return null;
        }
        int size = expectedSize > 0 ? expectedSize : inferInventorySize(items);
        if (size <= 0) {
            return null;
        }

        SimpleContainer inventory = new SimpleContainer(size);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemNbt = items.getCompound(i).orElse(null);
            if (itemNbt == null) {
                continue;
            }
            int slot = itemNbt.getByte("Slot").orElse((byte) 0) & 255;
            if (slot >= size) {
                continue;
            }

            ItemStack stack = itemStackFromNbt(registryLookup, itemNbt);
            if (!stack.isEmpty()) {
                inventory.setItem(slot, stack);
            }
        }

        return inventory;
    }

    private static ItemStack itemStackFromNbt(HolderLookup.Provider registryLookup, CompoundTag nbt) {
        return ItemStack.OPTIONAL_CODEC
                .parse(registryLookup.createSerializationContext(NbtOps.INSTANCE), nbt)
                .result()
                .orElse(ItemStack.EMPTY);
    }

    private static int inferInventorySize(ListTag items) {
        int size = 0;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemNbt = items.getCompound(i).orElse(null);
            if (itemNbt != null) {
                size = Math.max(size, (itemNbt.getByte("Slot").orElse((byte) 0) & 255) + 1);
            }
        }
        return size;
    }

    public static ActualInventoryReadStatus getLastActualInventoryReadStatus() {
        return lastActualInventoryReadStatus;
    }

    public static String getItemStackSignature(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            try {
                return ItemStack.CODEC.encodeStart(
                        client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                        stack
                ).getOrThrow().toString();
            } catch (RuntimeException ignored) {
                // 组件损坏时仍保留可比较的本地表示，不能让刷新验证结果的路径崩溃。
            }
        }

        return stack.getItem() + "|" + stack.getComponents();
    }

    public static void requestInventoryData(Level world, BlockPos pos) {
        if (world != null) {
            EntityDataManager.getInstance().requestBlockEntityWrapped(world, pos);
        }
    }

    public static boolean requestInventoryDataChunk(Level world, ChunkPos chunkPos, int minY, int maxY) {
        if (world == null || DataManager.getInstance().hasIntegratedServer()) {
            return false;
        }

        EntityDataManager storage = EntityDataManager.getInstance();
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
            Container expected,
            Container found
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
            Container expected,
            Container found
    ) {
        Container expectedCopy = copyInventory(expected);
        Container foundCopy = copyInventory(found);
        List<SlotMismatch> slotMismatches = new ArrayList<>();
        boolean hasWrongItem = false;
        boolean hasMissing = false;
        boolean hasStateMismatch = false;
        boolean hasExpectedFilledSlot = false;
        boolean allExpectedFilledSlotsMissing = true;

        for (int slot = 0; slot < expected.getContainerSize(); slot++) {
            ItemStack expectedStack = expected.getItem(slot);
            ItemStack foundStack = found.getItem(slot);
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
                    addSlotStatusIfEmpty(slotMismatches, slot, SlotMismatchStatus.LOCK_STATE, expected.getItem(slot), found.getItem(slot));
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
            Minecraft mc,
            GuiGraphicsExtractor drawContext
    ) {
        if (mismatch == null) {
            return;
        }

        Pair<Container, Container> inventories = mismatch.inventories();

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
            GuiGraphicsExtractor context,
            Minecraft client,
            ItemStack stack,
            int x,
            int y,
            int guiLeft,
            int guiTop,
            float alpha
    ) {
        GhostItemBuffer.drawGhostItem(context, client, stack, x, y, guiLeft, guiTop, alpha);
    }

    public static boolean beginHandledScreenGhostRender(GuiGraphicsExtractor context, Minecraft client) {
        return client != null;
    }

    public static void endHandledScreenGhostRender(GuiGraphicsExtractor context, int guiLeft, int guiTop, float alpha) {
    }

    public static void rememberContainerUse(Minecraft client, BlockHitResult hitResult) {
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

    public static SlotOverlay getSlotOverlayForScreen(AbstractContainerScreen<?> screen, Slot slot) {
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
        if (!areItemsAndComponentsEqual(expectedStack, foundStack)) {
            return SlotMismatchStatus.WRONG;
        }
        if (expectedStack.getCount() != foundStack.getCount()) {
            return SlotMismatchStatus.COUNT;
        }

        return null;
    }

    private static boolean areItemsAndComponentsEqual(ItemStack expectedStack, ItemStack foundStack) {
        if (ItemStack.isSameItemSameComponents(expectedStack, foundStack)) {
            return true;
        }

        if (!expectedStack.is(foundStack.getItem())
                || !sameEnchantments(expectedStack, foundStack, DataComponents.ENCHANTMENTS)
                || !sameEnchantments(expectedStack, foundStack, DataComponents.STORED_ENCHANTMENTS)) {
            return false;
        }

        ItemStack expectedWithoutEnchantments = expectedStack.copy();
        ItemStack foundWithoutEnchantments = foundStack.copy();
        expectedWithoutEnchantments.remove(DataComponents.ENCHANTMENTS);
        expectedWithoutEnchantments.remove(DataComponents.STORED_ENCHANTMENTS);
        foundWithoutEnchantments.remove(DataComponents.ENCHANTMENTS);
        foundWithoutEnchantments.remove(DataComponents.STORED_ENCHANTMENTS);
        return ItemStack.isSameItemSameComponents(expectedWithoutEnchantments, foundWithoutEnchantments);
    }

    private static boolean sameEnchantments(
            ItemStack expectedStack,
            ItemStack foundStack,
            DataComponentType<ItemEnchantments> type
    ) {
        ItemEnchantments expected = expectedStack.get(type);
        ItemEnchantments found = foundStack.get(type);

        if (expected == null || found == null) {
            return expected == found;
        }
        if (expected.size() != found.size()) {
            return false;
        }

        for (var entry : expected.entrySet()) {
            Holder<Enchantment> expectedEnchantment = entry.getKey();
            int expectedLevel = entry.getIntValue();
            boolean matched = false;

            for (var foundEntry : found.entrySet()) {
                Holder<Enchantment> foundEnchantment = foundEntry.getKey();
                if (expectedEnchantment.unwrapKey().equals(foundEnchantment.unwrapKey())
                        && expectedLevel == foundEntry.getIntValue()) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSlotLockMismatch(Set<Integer> expectedDisabledSlots, Set<Integer> foundDisabledSlots, int slot) {
        return expectedDisabledSlots.contains(slot) != foundDisabledSlots.contains(slot);
    }

    public static ExpectedContainer getExpectedContainerAt(Level foundWorld, BlockPos pos) {
        return getExpectedContainerAt(foundWorld, pos, null);
    }

    public static ExpectedContainer getExpectedContainerAt(
            Level foundWorld,
            BlockPos pos,
            @Nullable SchematicPlacement placementFilter
    ) {
        if (foundWorld == null) {
            return null;
        }

        ExpectedContainer current = getExpectedContainerInternal(pos, placementFilter);

        if (current == null) {
            return null;
        }

        ExpectedContainer merged = getExpectedDoubleChestContainer(pos, current, placementFilter);

        return merged != null ? merged : current;
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshotAt(Level foundWorld, BlockPos pos) {
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

        List<ItemStack> templates = new ArrayList<>(expected.inventory().getContainerSize());
        List<Boolean> disabledStates = new ArrayList<>(expected.inventory().getContainerSize());
        for (int i = 0; i < expected.inventory().getContainerSize(); i++) {
            ItemStack stack = expected.inventory().getItem(i);
            templates.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            disabledStates.add(expected.disabledSlots().contains(i));
        }

        return new QuickContainerCopy.TemplateSnapshot(type, templates, disabledStates);
    }

    private static Container getDirectInventory(Level world, BlockPos pos, int expectedSize) {
        Container merged = getMergedDoubleChestInventory(world, pos);

        if (merged != null && (expectedSize < 0 || merged.getContainerSize() == expectedSize)) {
            return merged;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof Container inventory ? inventory : null;
    }

    public static Set<Integer> getDisabledSlots(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return Set.of();
        }

        Level blockEntityWorld = blockEntity.getLevel();
        if (blockEntityWorld != null) {
            CompoundTag nbt = blockEntity.saveWithFullMetadata(blockEntityWorld.registryAccess());
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

    private static void bindCurrentScreen(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();

        if (client.gui.screen() != screen) {
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

    private static void refreshCurrentScreenVerifier(AbstractContainerScreen<?> screen) {
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

    private static BlockPos getLookedAtInventoryPos(Minecraft client) {
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

    private static void clearCurrentScreenContainerBinding() {
        currentScreenContainerPos = null;
        lastCurrentScreenRefreshTick = Long.MIN_VALUE;
        lastCurrentScreenRevision = Integer.MIN_VALUE;
        currentScreenSlotOverlays = List.of();
        currentScreenContainerInventory = null;
    }

    private static boolean isSupportedContainerHandler(AbstractContainerMenu handler) {
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

    private static boolean isSupportedHandlerForExpectedContainer(AbstractContainerMenu handler, ExpectedContainer expectedContainer) {
        QuickContainerCopy.PublicContainerType type = QuickContainerCopy.getPublicContainerType(
                expectedContainer.state().getBlock(),
                getChestType(expectedContainer.state())
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

    private static Container findContainerInventory(AbstractContainerMenu handler, int expectedSize) {
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

    private static Container copyContainerInventoryFromScreen(AbstractContainerMenu handler, Container containerInventory) {
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

    private static Set<Integer> copyCrafterDisabledSlotsFromScreen(
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

    private static List<SlotOverlay> buildSlotOverlays(
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
            SlotMismatchStatus status = getSlotMismatchStatus(expectedStack, foundInventory.getItem(slot));

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

    private static ExpectedContainer getExpectedDoubleChestContainer(
            BlockPos pos,
            ExpectedContainer current,
            @Nullable SchematicPlacement placementFilter
    ) {
        ChestType chestType = getChestType(current.state());

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = ChestBlock.getConnectedBlockPos(pos, current.state());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos, placementFilter);

        if (adjacent == null) {
            return null;
        }

        Container currentInventory = current.inventory();
        Container adjacentInventory = adjacent.inventory();
        Container merged = chestType == ChestType.RIGHT
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
        return getExpectedDoubleChestAdjacentPos(pos, null);
    }

    public static BlockPos getExpectedDoubleChestAdjacentPos(
            BlockPos pos,
            @Nullable SchematicPlacement placementFilter
    ) {
        ExpectedContainer current = getExpectedContainerInternal(pos, placementFilter);

        if (current == null || getChestType(current.state()) == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = ChestBlock.getConnectedBlockPos(pos, current.state());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos, placementFilter);
        return adjacent != null && getChestType(adjacent.state()) != ChestType.SINGLE
                ? adjacentPos
                : null;
    }

    private static ExpectedContainer getExpectedContainerInternal(BlockPos worldPos) {
        return getExpectedContainerInternal(worldPos, null);
    }

    public static ExpectedContainer getExpectedContainerPartAt(SchematicPlacement placement, BlockPos pos) {
        return placement != null ? getExpectedContainerInternal(pos, placement) : null;
    }

    private static ExpectedContainer getExpectedContainerInternal(
            BlockPos worldPos, @Nullable SchematicPlacement placementFilter) {
        LocalPlacementPos placementPos = getLocalPlacementPos(worldPos, placementFilter);

        if (placementPos == null) {
            return null;
        }

        Map<BlockPos, ?> blockEntities = placementPos.placement().getSchematic()
                .getBlockEntityMapForRegion(placementPos.region());

        if (blockEntities == null) {
            return null;
        }

        Object data = blockEntities.get(placementPos.pos());

        if (data == null) {
            return null;
        }

        CompoundTag nbt = QuickLitematicaDataCompat.toVanillaNbt(data);

        Minecraft client = Minecraft.getInstance();
        Level clientWorld = client.level;

        if (clientWorld == null) {
            return null;
        }

        BlockEntity blockEntity = BlockEntity.loadStatic(
                placementPos.pos(),
                placementPos.rawState(),
                nbt,
                clientWorld.registryAccess()
        );

        if (!(blockEntity instanceof Container inventory)) {
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
        return getLocalPlacementPos(worldPos, null);
    }

    private static LocalPlacementPos getLocalPlacementPos(
            BlockPos worldPos, @Nullable SchematicPlacement placementFilter) {
        List<SchematicPlacementManager.PlacementPart> parts = DataManager.getSchematicPlacementManager()
                .getAllPlacementsTouchingChunk(worldPos);

        for (SchematicPlacementManager.PlacementPart part : parts) {
            if ((placementFilter != null && part.getPlacement() != placementFilter)
                    || !part.getBox().contains(worldPos)) {
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

        Rotation rotationCombined = schematicPlacement.getRotation().getRotated(placement.getRotation());
        Mirror mirrorMain = schematicPlacement.getMirror();
        Mirror mirrorSub = placement.getMirror();

        if (mirrorSub != Mirror.NONE
                && (schematicPlacement.getRotation() == Rotation.CLOCKWISE_90
                || schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90)) {
            mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }

        if (mirrorMain != Mirror.NONE) {
            state = state.mirror(mirrorMain);
        }
        if (mirrorSub != Mirror.NONE) {
            state = state.mirror(mirrorSub);
        }
        if (rotationCombined != Rotation.NONE) {
            state = state.rotate(rotationCombined);
        }

        return state;
    }

    private static Container getMergedDoubleChestInventory(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        ChestType chestType = getChestType(state);

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = ChestBlock.getConnectedBlockPos(pos, state);
        BlockEntity current = world.getBlockEntity(pos);
        BlockEntity adjacent = world.getBlockEntity(adjacentPos);

        if (!(current instanceof Container currentInventory)
                || !(adjacent instanceof Container adjacentInventory)) {
            return null;
        }

        return chestType == ChestType.RIGHT
                ? mergeInventories(currentInventory, adjacentInventory)
                : mergeInventories(adjacentInventory, currentInventory);
    }

    private static Container mergeInventories(Container first, Container second) {
        SimpleContainer inventory = new SimpleContainer(first.getContainerSize() + second.getContainerSize());

        for (int i = 0; i < first.getContainerSize(); i++) {
            inventory.setItem(i, first.getItem(i).copy());
        }

        for (int i = 0; i < second.getContainerSize(); i++) {
            inventory.setItem(first.getContainerSize() + i, second.getItem(i).copy());
        }

        return inventory;
    }

    private static ChestType getChestType(BlockState state) {
        return state.getBlock() instanceof ChestBlock ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
    }

    private static Set<Integer> getDisabledSlots(BlockEntity blockEntity, CompoundTag nbt) {
        // 投影和实际世界都优先按 NBT 里的 disabled_slots 比较，避免两边来源不同导致合成器锁槽误判。
        if (nbt != null && nbt.contains("disabled_slots")) {
            return readDisabledSlotsFromNbt(nbt);
        }

        if (blockEntity instanceof CrafterBlockEntity crafter) {
            return Set.copyOf(BlockUtils.getDisabledSlots(crafter));
        }

        return Set.of();
    }

    private static Set<Integer> readDisabledSlotsFromNbt(CompoundTag nbt) {
        Set<Integer> disabledSlots = new HashSet<>();
        int[] slots = nbt.getIntArray("disabled_slots").orElse(new int[0]);
        for (int slot : slots) {
            disabledSlots.add(slot);
        }
        return Set.copyOf(disabledSlots);
    }

    private static SimpleContainer copyInventory(Container source) {
        SimpleContainer copy = new SimpleContainer(source.getContainerSize());

        for (int i = 0; i < source.getContainerSize(); i++) {
            copy.setItem(i, source.getItem(i).copy());
        }

        return copy;
    }

    private static void renderInventoryOverlay(
            BlockInfoAlignment align,
            LeftRight side,
            int offY,
            MismatchType mismatchType,
            Container inventory,
            BlockState state,
            Set<Integer> disabledSlots,
            List<SlotMismatch> slotMismatches,
            boolean renderGhostStacks,
            double mouseX,
            double mouseY,
            Minecraft mc,
            GuiGraphicsExtractor drawContext
    ) {
        InventoryOverlayType type = getInventoryType(inventory, state);
        InventoryOverlay.InventoryProperties props = InventoryOverlay.getInventoryPropsTemp(type, inventory.getContainerSize());
        GuiContext guiContext = GuiContext.fromGuiGraphics(drawContext);
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

        InventoryOverlay.renderInventoryBackground(guiContext, type, xInv, yInv, props.slotsPerRow, props.totalSlots);
        drawSlotHighlights(drawContext, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        InventoryOverlay.renderInventoryStacks(guiContext, type, inventory, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, 0, inventory.getContainerSize(), disabledSlots);

        if (renderGhostStacks) {
            drawMissingGhostStacks(drawContext, mc, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        }
    }

    private static void drawSlotHighlights(
            GuiGraphicsExtractor drawContext,
            InventoryOverlayType type,
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
            GuiGraphicsExtractor drawContext,
            Minecraft mc,
            InventoryOverlayType type,
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
            InventoryOverlayType type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            int slot
    ) {
        // 炉子类和酿造台在 malilib 的 InventoryOverlay 里不是普通网格槽位。
        if (type == InventoryOverlayType.FURNACE) {
            return switch (slot) {
                case 0 -> new SlotPosition(xSlots + 8, ySlots + 8);
                case 1 -> new SlotPosition(xSlots + 8, ySlots + 44);
                case 2 -> new SlotPosition(xSlots + 68, ySlots + 26);
                default -> getGridSlotPosition(xSlots, ySlots, slotsPerRow, slot);
            };
        }

        if (type == InventoryOverlayType.BREWING_STAND) {
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

    private static void drawOutline(GuiGraphicsExtractor drawContext, int x, int y, int width, int height, int color) {
        drawContext.fill(x, y, x + width, y + 1, color);
        drawContext.fill(x, y + height - 1, x + width, y + height, color);
        drawContext.fill(x, y + 1, x + 1, y + height - 1, color);
        drawContext.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private static InventoryOverlayType getInventoryType(Container inventory, BlockState state) {
        if (state != null) {
            if (state.getBlock() instanceof AbstractFurnaceBlock) {
                return InventoryOverlayType.FURNACE;
            }
            if (state.getBlock() instanceof BrewingStandBlock) {
                return InventoryOverlayType.BREWING_STAND;
            }
            if (state.getBlock() instanceof CrafterBlock) {
                return InventoryOverlayType.CRAFTER;
            }
            if (state.getBlock() instanceof DispenserBlock) {
                return InventoryOverlayType.DISPENSER;
            }
            if (state.getBlock() instanceof HopperBlock) {
                return InventoryOverlayType.HOPPER;
            }
        }

        return switch (inventory.getContainerSize()) {
            case 3 -> InventoryOverlayType.FURNACE;
            case 5 -> InventoryOverlayType.HOPPER;
            case 9 -> InventoryOverlayType.DISPENSER;
            case 27 -> InventoryOverlayType.FIXED_27;
            case 54 -> InventoryOverlayType.FIXED_54;
            default -> InventoryOverlayType.GENERIC;
        };
    }

    private static boolean isInventoryEmpty(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
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
            Container expectedInventory,
            Container foundInventory,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            List<SlotMismatch> slotMismatches
    ) {
        public Pair<Container, Container> inventories() {
            return Pair.of(this.expectedInventory, this.foundInventory);
        }

        public ContainerMismatchKey key() {
            return new ContainerMismatchKey(this.type, this.pos, this.slotMismatchSignature());
        }

        private String slotMismatchSignature() {
            StringBuilder builder = new StringBuilder();

            builder.append(this.type.ordinal())
                    .append('|')
                    .append(this.expectedState)
                    .append('|')
                    .append(this.foundState)
                    .append('|')
                    .append(this.expectedDisabledSlots.stream().sorted().toList())
                    .append('|')
                    .append(this.foundDisabledSlots.stream().sorted().toList())
                    .append('|');

            for (SlotMismatch mismatch : this.slotMismatches) {
                builder.append(mismatch.slot())
                        .append(':')
                        .append(mismatch.status().name())
                        .append(':')
                        .append(mismatch.expectedStack().getCount())
                        .append(':')
                        .append(mismatch.foundStack().getCount())
                        .append(':')
                        .append(getItemStackSignature(mismatch.expectedStack()))
                        .append(':')
                        .append(getItemStackSignature(mismatch.foundStack()))
                        .append(';');
            }

            return builder.toString();
        }
    }

    public record ExpectedContainer(
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            Container inventory,
            Set<Integer> disabledSlots
    ) {
    }

    public enum ActualInventoryReadStatus {
        NOT_READ,
        NO_WORLD,
        INTEGRATED_DIRECT,
        DIRECT_INVENTORY,
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

        List<ContainerMismatch> quickcraft$getContainerMismatches();

        List<ItemStack> quickcraft$getMissingContainerStacks();

        int quickcraft$getWrongInventoryCount();

        int quickcraft$getContainerMismatchCount(MismatchType type);

        int quickcraft$getExpectedContainerCount();

        int quickcraft$getCheckedContainerCount();

        int quickcraft$getPendingContainerCount();

        List<ContainerMismatch> quickcraft$refreshContainerMismatchAt(BlockPos pos, Container foundInventory, Set<Integer> foundDisabledSlots);
    }

    /**
     * 给 Litematica 的 BlockMismatch 挂接容器校验附加数据。
     * 这里保存容器对比结果、库存引用和禁用槽位信息，供列表与悬浮预览复用。
     */
    public interface BlockMismatchExtension {
        void quickcraft$setInventories(Pair<Container, Container> inventories);

        Pair<Container, Container> quickcraft$getInventories();

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
                GuiGraphicsExtractor context,
                Minecraft client,
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

            context.item(stack, x, y);
            context.itemDecorations(client.font, stack, x, y);
            int maskAlpha = Math.round((1.0F - Math.max(0.0F, Math.min(1.0F, alpha))) * 255.0F);
            context.fill(x, y, x + 16, y + 16, (maskAlpha << 24) | (MISSING_SLOT_GHOST_MASK & 0x00FFFFFF));
        }
    }
}
