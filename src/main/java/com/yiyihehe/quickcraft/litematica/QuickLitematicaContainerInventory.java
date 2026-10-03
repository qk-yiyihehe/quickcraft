package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.util.game.BlockUtils;
import fi.dy.masa.malilib.util.nbt.NbtBlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.*;

/** 容器库存读取、数据请求和可信缓存；缓存随世界切换失效。 */
final class QuickLitematicaContainerInventory {
    private QuickLitematicaContainerInventory() {
    }

    private static ActualInventoryReadStatus lastActualInventoryReadStatus = ActualInventoryReadStatus.NOT_READ;
    private static World trustedCacheWorld;
    private static final Map<BlockPos, SimpleInventory> trustedInventoryCache = new HashMap<>();

    static Inventory getExpectedInventory(BlockEntity expectedBlockEntity, Inventory directInventory) {
        if (expectedBlockEntity == null) {
            return directInventory;
        }

        World blockEntityWorld = expectedBlockEntity.getWorld();
        if (blockEntityWorld == null) {
            return directInventory;
        }

        NbtCompound nbt = expectedBlockEntity.createNbtWithIdentifyingData(blockEntityWorld.getRegistryManager());

        if (nbt.contains("Items")) {
            Inventory nbtInventory = getNbtInventoryPreservingComponents(
                    nbt,
                    directInventory != null ? directInventory.size() : -1,
                    blockEntityWorld.getRegistryManager()
            );

            if (nbtInventory != null) {
                return nbtInventory;
            }
        }

        return directInventory;
    }

    static Inventory getActualInventory(World world, BlockPos pos, Inventory directInventory, Inventory expected) {
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
                && directInventory.size() == expected.size()
                && !isInventoryEmpty(directInventory)) {
            lastActualInventoryReadStatus = ActualInventoryReadStatus.DIRECT_INVENTORY;
            return directInventory;
        }

        if (DataManager.getInstance().hasIntegratedServer()) {
            Inventory mergedOrDirect = getDirectInventory(world, pos, expected != null ? expected.size() : -1);
            lastActualInventoryReadStatus = mergedOrDirect != null || directInventory != null
                    ? ActualInventoryReadStatus.INTEGRATED_DIRECT
                    : ActualInventoryReadStatus.NO_DIRECT_INVENTORY;
            return mergedOrDirect != null ? mergedOrDirect : directInventory;
        }

        NbtCompound cachedNbt = QuickLitematicaVerifierAccess.getBlockEntityNbtFromStorage(pos);
        if (QuickLitematicaVerifierAccess.isServerEmptyContainerNbt(cachedNbt, expected)) {
            trustedInventoryCache.put(pos.toImmutable(), new SimpleInventory(expected.size()));
            lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_INVENTORY;
            return new SimpleInventory(expected.size());
        }
        if (QuickLitematicaVerifierAccess.hasCachedContainerData(cachedNbt, expected)) {
            Inventory cachedInventory = getCachedInventory(world, pos, expected != null ? expected.size() : -1);

            if (cachedInventory != null && isTrustedCachedInventory(pos, cachedNbt, cachedInventory, expected)) {
                trustedInventoryCache.put(pos.toImmutable(), copyInventory(cachedInventory));
                lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_INVENTORY;
                return cachedInventory;
            }

            lastActualInventoryReadStatus = cachedInventory != null
                    ? ActualInventoryReadStatus.CACHE_WITHOUT_ITEMS
                    : ActualInventoryReadStatus.CACHE_PARSE_FAILED;
        } else {
            lastActualInventoryReadStatus = ActualInventoryReadStatus.NO_CACHE_NBT;
        }

        if (!QuickLitematicaVerifierAccess.hasServuxServer()
                && !QuickLitematicaVerifierAccess.hasBackupStatus()
                && expected != null) {
            SimpleInventory trusted = trustedInventoryCache.get(pos);
            if (trusted != null && trusted.size() == expected.size()) {
                lastActualInventoryReadStatus = ActualInventoryReadStatus.CACHE_INVENTORY;
                return copyInventory(trusted);
            }
        }

        // 多人没有实体数据时不要拿客户端空壳库存硬比，避免把未知误报成错误填充。
        QuickLitematicaVerifierAccess.requestBlockEntity(world, pos);
        return null;
    }

    static Inventory getCachedInventory(World world, BlockPos pos, int expectedSize) {
        Inventory merged = getMergedCachedDoubleChestInventory(world, pos, expectedSize);

        if (merged != null) {
            return merged;
        }

        NbtCompound cachedNbt = QuickLitematicaVerifierAccess.getBlockEntityNbtFromStorage(pos);
        Inventory special = getCachedSpecialInventory(world, cachedNbt, expectedSize);
        if (special != null) {
            return special;
        }
        BlockEntity cachedBlockEntity = QuickLitematicaVerifierAccess.getBlockEntityFromStorage(pos);

        if (cachedBlockEntity instanceof Inventory inventory
                && (expectedSize <= 0 || inventory.size() == expectedSize)) {
            return copyInventory(inventory);
        }

        return cachedNbt != null
                ? getNbtInventoryPreservingComponents(
                        cachedNbt,
                        expectedSize,
                        world.getRegistryManager()
                )
                : null;
    }

    static Inventory getCachedSpecialInventory(World world, NbtCompound cachedNbt, int expectedSize) {
        if (world == null || cachedNbt == null || expectedSize != 1 || !cachedNbt.contains("RecordItem")) {
            return null;
        }

        ItemStack recordStack = QuickLitematicaVerifierAccess.parseRecordStack(world.getRegistryManager(), cachedNbt);
        if (recordStack.isEmpty()) {
            return null;
        }
        SimpleInventory inventory = new SimpleInventory(1);
        inventory.setStack(0, recordStack);
        return inventory;
    }

    static boolean isTrustedCachedInventory(
            BlockPos pos,
            NbtCompound cachedNbt,
            Inventory cachedInventory,
            Inventory expected
    ) {
        if (QuickLitematicaVerifierAccess.isTrustedCachedInventory(cachedNbt, cachedInventory)) {
            return true;
        }

        if (expected != null && isInventoryEmpty(expected)) {
            return true;
        }

        // 无物品字段既可能表示服务器确认的空容器，也可能只是客户端空壳；仅在整区块数据已回齐时信任为空。
        return (QuickLitematicaVerifierAccess.hasServuxServer() || QuickLitematicaVerifierAccess.getIfReceivedBackupPackets())
                && QuickLitematicaVerifierAccess.hasCompletedChunk(new ChunkPos(pos));
    }

    static Inventory getMergedCachedDoubleChestInventory(World world, BlockPos pos, int expectedSize) {
        if (expectedSize != 54) {
            return null;
        }

        BlockState state = world.getBlockState(pos);
        ChestType chestType = getChestType(state);

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = pos.add(ChestBlock.getFacing(state).getVector());
        NbtCompound currentNbt = QuickLitematicaVerifierAccess.getBlockEntityNbtFromStorage(pos);
        NbtCompound adjacentNbt = QuickLitematicaVerifierAccess.getBlockEntityNbtFromStorage(adjacentPos);

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

    static Inventory getNbtInventoryPreservingComponents(
            NbtCompound nbt,
            int expectedSize,
            RegistryWrapper.WrapperLookup registryLookup
    ) {
        NbtList items = QuickLitematicaVerifierAccess.getItemsList(nbt);
        if (items == null || registryLookup == null) {
            return null;
        }

        int size = expectedSize > 0 ? expectedSize : inferInventorySize(items);
        if (size <= 0) {
            return null;
        }

        SimpleInventory inventory = new SimpleInventory(size);
        for (int i = 0; i < items.size(); i++) {
            NbtCompound itemNbt = QuickLitematicaVerifierAccess.getCompound(items, i);
            if (itemNbt == null) {
                continue;
            }
            int slot = QuickLitematicaVerifierAccess.getSlotByte(itemNbt);
            if (slot < 0 || slot >= size) {
                continue;
            }

            ItemStack stack = QuickLitematicaVerifierAccess.parseItemStack(registryLookup, itemNbt);
            if (!stack.isEmpty()) {
                inventory.setStack(slot, stack);
            }
        }

        return inventory;
    }

    static int inferInventorySize(NbtList items) {
        int size = 0;
        for (int i = 0; i < items.size(); i++) {
            NbtCompound itemNbt = QuickLitematicaVerifierAccess.getCompound(items, i);
            if (itemNbt != null) {
                size = Math.max(size, QuickLitematicaVerifierAccess.getSlotByte(itemNbt) + 1);
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

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            try {
                return QuickLitematicaVerifierAccess.getItemStackSignature(client.world.getRegistryManager(), stack);
            } catch (RuntimeException ignored) {
                // 组件损坏时仍保留可比较的本地表示，不能让刷新验证结果的路径崩溃。
            }
        }

        return stack.getItem() + "|" + stack.getComponents();
    }

    static void requestInventoryData(World world, BlockPos pos) {
        if (world != null) {
            QuickLitematicaVerifierAccess.requestBlockEntity(world, pos);
        }
    }

    static boolean requestInventoryDataChunk(World world, ChunkPos chunkPos, int minY, int maxY) {
        if (world == null || DataManager.getInstance().hasIntegratedServer()) {
            return false;
        }

        if (QuickLitematicaVerifierAccess.hasServuxServer()) {
            QuickLitematicaVerifierAccess.requestServuxBulkEntityData(chunkPos, minY, maxY);
            return true;
        }
        if (QuickLitematicaVerifierAccess.getIfReceivedBackupPackets()) {
            QuickLitematicaVerifierAccess.requestBackupBulkEntityData(chunkPos, minY, maxY);
            return true;
        }

        return false;
    }

    static Inventory getDirectInventory(World world, BlockPos pos, int expectedSize) {
        Inventory merged = getMergedDoubleChestInventory(world, pos);

        if (merged != null && (expectedSize < 0 || merged.size() == expectedSize)) {
            return merged;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof Inventory inventory ? inventory : null;
    }

    static Set<Integer> getDisabledSlots(BlockEntity blockEntity) {
        if (blockEntity != null && blockEntity.getWorld() != null) {
            NbtCompound nbt = blockEntity.createNbtWithIdentifyingData(blockEntity.getWorld().getRegistryManager());
            return getDisabledSlots(blockEntity, nbt);
        }

        if (blockEntity instanceof CrafterBlockEntity crafter) {
            return Set.copyOf(BlockUtils.getDisabledSlots(crafter));
        }

        return Set.of();
    }

    static Inventory getMergedDoubleChestInventory(World world, BlockPos pos) {
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

    static Inventory mergeInventories(Inventory first, Inventory second) {
        SimpleInventory inventory = new SimpleInventory(first.size() + second.size());

        for (int i = 0; i < first.size(); i++) {
            inventory.setStack(i, first.getStack(i).copy());
        }

        for (int i = 0; i < second.size(); i++) {
            inventory.setStack(first.size() + i, second.getStack(i).copy());
        }

        return inventory;
    }

    static ChestType getChestType(BlockState state) {
        return state.getBlock() instanceof ChestBlock ? state.get(ChestBlock.CHEST_TYPE) : ChestType.SINGLE;
    }

    static Set<Integer> getDisabledSlots(BlockEntity blockEntity, NbtCompound nbt) {
        // 投影和实际世界都优先按 NBT 里的 disabled_slots 比较，避免两边来源不同导致合成器锁槽误判。
        if (nbt != null && nbt.contains("disabled_slots")) {
            return Set.copyOf(NbtBlockUtils.getDisabledSlotsFromNbt(nbt));
        }

        if (blockEntity instanceof CrafterBlockEntity crafter) {
            return Set.copyOf(BlockUtils.getDisabledSlots(crafter));
        }

        return Set.of();
    }

    static SimpleInventory copyInventory(Inventory source) {
        SimpleInventory copy = new SimpleInventory(source.size());

        for (int i = 0; i < source.size(); i++) {
            copy.setStack(i, source.getStack(i).copy());
        }

        return copy;
    }

    static boolean isInventoryEmpty(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                return false;
            }
        }

        return true;
    }
}
