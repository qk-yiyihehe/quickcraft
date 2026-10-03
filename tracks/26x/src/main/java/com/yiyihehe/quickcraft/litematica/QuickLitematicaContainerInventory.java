package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.malilib.util.game.BlockUtils;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.*;

/** 容器库存读取、数据请求和可信缓存；缓存随世界切换失效。 */
final class QuickLitematicaContainerInventory {
    private QuickLitematicaContainerInventory() {
    }

    private static ActualInventoryReadStatus lastActualInventoryReadStatus = ActualInventoryReadStatus.NOT_READ;
    private static Level trustedCacheWorld;
    private static final Map<BlockPos, SimpleContainer> trustedInventoryCache = new HashMap<>();

    static Container getExpectedInventory(BlockEntity expectedBlockEntity, Container directInventory) {
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

    static Container getActualInventory(Level world, BlockPos pos, Container directInventory, Container expected) {
        lastActualInventoryReadStatus = ActualInventoryReadStatus.NOT_READ;

        if (world == null) {
            lastActualInventoryReadStatus = ActualInventoryReadStatus.NO_WORLD;
            return null;
        }

        if (trustedCacheWorld != world) {
            trustedCacheWorld = world;
            trustedInventoryCache.clear();
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

        if (cachedNbt != null && !cachedNbt.contains("Items") && !cachedNbt.contains("RecordItem")
                && expected != null && isInventoryEmpty(expected)) {
            // 服务器空容器 NBT 可能只带 x/y/z/id，没有 Items；这表示已读到空库存。
            trustedInventoryCache.put(pos.immutable(), new SimpleContainer(expected.getContainerSize()));
            lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_INVENTORY;
            return new SimpleContainer(expected.getContainerSize());
        }

        if (cachedNbt != null && (cachedNbt.contains("Items") || cachedNbt.contains("RecordItem") || isInventoryEmpty(expected))) {
            Container cachedInventory = getCachedInventory(world, pos, storage, expected != null ? expected.getContainerSize() : -1);

            if (cachedInventory != null && isTrustedCachedInventory(storage, pos, cachedNbt, cachedInventory, expected)) {
                trustedInventoryCache.put(pos.immutable(), copyInventory(cachedInventory));
                lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_INVENTORY;
                return cachedInventory;
            }

            lastActualInventoryReadStatus = cachedInventory != null
                    ? ActualInventoryReadStatus.CACHE_WITHOUT_ITEMS
                    : ActualInventoryReadStatus.CACHE_PARSE_FAILED;
        } else {
            lastActualInventoryReadStatus = ActualInventoryReadStatus.NO_CACHE_NBT;
        }

        if (!storage.hasServuxServer()
                && !storage.hasBackupStatus()
                && expected != null) {
            SimpleContainer trusted = trustedInventoryCache.get(pos);
            if (trusted != null && trusted.getContainerSize() == expected.getContainerSize()) {
                lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_INVENTORY;
                return copyInventory(trusted);
            }
        }

        // 多人没有实体数据时不要拿客户端空壳库存硬比，避免把未知误报成错误填充。
        storage.requestBlockEntityWrapped(world, pos);
        return null;
    }

    static Container getCachedInventory(Level world, BlockPos pos, EntityDataManager storage, int expectedSize) {
        Container merged = getMergedCachedDoubleChestInventory(world, pos, storage, expectedSize);

        if (merged != null) {
            return merged;
        }

        CompoundTag cachedNbt = storage.getCache().getBlockEntityNbtFromCache(pos);
        Container special = getCachedSpecialInventory(world, cachedNbt, expectedSize);
        if (special != null) {
            return special;
        }
        BlockEntity cachedBlockEntity = storage.getFromBlockEntityCache(pos);

        if (cachedBlockEntity instanceof Container inventory
                && (expectedSize <= 0 || inventory.getContainerSize() == expectedSize)) {
            return copyInventory(inventory);
        }

        return cachedNbt != null
                ? getNbtInventoryPreservingComponents(
                        cachedNbt,
                        expectedSize,
                        world.registryAccess()
                )
                : null;
    }

    static Container getCachedSpecialInventory(Level world, CompoundTag cachedNbt, int expectedSize) {
        if (world == null || cachedNbt == null || expectedSize != 1 || !cachedNbt.contains("RecordItem")) {
            return null;
        }

        ItemStack recordStack = cachedNbt.getCompound("RecordItem")
                .map(nbt -> itemStackFromNbt(world.registryAccess(), nbt))
                .orElse(ItemStack.EMPTY);
        if (recordStack.isEmpty()) {
            return null;
        }
        SimpleContainer inventory = new SimpleContainer(1);
        inventory.setItem(0, recordStack);
        return inventory;
    }

    static boolean isTrustedCachedInventory(
            EntityDataManager storage,
            BlockPos pos,
            CompoundTag cachedNbt,
            Container cachedInventory,
            Container expected
    ) {
        if (cachedNbt.contains("Items") || !isInventoryEmpty(cachedInventory)) {
            return true;
        }

        if (expected != null && isInventoryEmpty(expected)) {
            return true;
        }

        // 无物品字段既可能表示服务器确认的空容器，也可能只是客户端空壳；仅在整区块数据已回齐时信任为空。
        return (storage.hasServuxServer() || storage.getIfReceivedBackupPackets())
                && storage.hasCompletedChunk(ChunkPos.containing(pos));
    }

    static Container getMergedCachedDoubleChestInventory(Level world, BlockPos pos, EntityDataManager storage, int expectedSize) {
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

    static Container getNbtInventoryPreservingComponents(
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

    static ItemStack itemStackFromNbt(HolderLookup.Provider registryLookup, CompoundTag nbt) {
        return ItemStack.OPTIONAL_CODEC
                .parse(registryLookup.createSerializationContext(NbtOps.INSTANCE), nbt)
                .result()
                .orElse(ItemStack.EMPTY);
    }

    static int inferInventorySize(ListTag items) {
        int size = 0;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemNbt = items.getCompound(i).orElse(null);
            if (itemNbt != null) {
                size = Math.max(size, (itemNbt.getByte("Slot").orElse((byte) 0) & 255) + 1);
            }
        }
        return size;
    }

    static ActualInventoryReadStatus getLastActualInventoryReadStatus() {
        return lastActualInventoryReadStatus;
    }

    static String getItemStackSignature(ItemStack stack) {
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

    static void requestInventoryData(Level world, BlockPos pos) {
        if (world != null) {
            EntityDataManager.getInstance().requestBlockEntityWrapped(world, pos);
        }
    }

    static boolean requestInventoryDataChunk(Level world, ChunkPos chunkPos, int minY, int maxY) {
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

    static void ensureEntityDataSyncEnabled() {
        // 容器验证依赖 Litematica 的实体数据缓存和备份查询；只在实际请求数据时开启，避免污染逐方块热路径。
        Configs.Generic.ENTITY_DATA_SYNC.setBooleanValue(true);
        Configs.Generic.ENTITY_DATA_SYNC_BACKUP.setBooleanValue(true);
    }

    static Container getDirectInventory(Level world, BlockPos pos, int expectedSize) {
        Container merged = getMergedDoubleChestInventory(world, pos);

        if (merged != null && (expectedSize < 0 || merged.getContainerSize() == expectedSize)) {
            return merged;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof Container inventory ? inventory : null;
    }

    static Set<Integer> getDisabledSlots(BlockEntity blockEntity) {
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

    static Container getMergedDoubleChestInventory(Level world, BlockPos pos) {
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

    static Container mergeInventories(Container first, Container second) {
        SimpleContainer inventory = new SimpleContainer(first.getContainerSize() + second.getContainerSize());

        for (int i = 0; i < first.getContainerSize(); i++) {
            inventory.setItem(i, first.getItem(i).copy());
        }

        for (int i = 0; i < second.getContainerSize(); i++) {
            inventory.setItem(first.getContainerSize() + i, second.getItem(i).copy());
        }

        return inventory;
    }

    static ChestType getChestType(BlockState state) {
        return state.getBlock() instanceof ChestBlock ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
    }

    static Set<Integer> getDisabledSlots(BlockEntity blockEntity, CompoundTag nbt) {
        // 投影和实际世界都优先按 NBT 里的 disabled_slots 比较，避免两边来源不同导致合成器锁槽误判。
        if (nbt != null && nbt.contains("disabled_slots")) {
            return readDisabledSlotsFromNbt(nbt);
        }

        if (blockEntity instanceof CrafterBlockEntity crafter) {
            return Set.copyOf(BlockUtils.getDisabledSlots(crafter));
        }

        return Set.of();
    }

    static Set<Integer> readDisabledSlotsFromNbt(CompoundTag nbt) {
        Set<Integer> disabledSlots = new HashSet<>();
        int[] slots = nbt.getIntArray("disabled_slots").orElse(new int[0]);
        for (int slot : slots) {
            disabledSlots.add(slot);
        }
        return Set.copyOf(disabledSlots);
    }

    static SimpleContainer copyInventory(Container source) {
        SimpleContainer copy = new SimpleContainer(source.getContainerSize());

        for (int i = 0; i < source.getContainerSize(); i++) {
            copy.setItem(i, source.getItem(i).copy());
        }

        return copy;
    }

    static boolean isInventoryEmpty(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                return false;
            }
        }

        return true;
    }
}
