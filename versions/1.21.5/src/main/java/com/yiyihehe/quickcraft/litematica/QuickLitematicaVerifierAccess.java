package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.BlockMismatchExtension;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntitiesDataStorage;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.malilib.util.IntBoundingBox;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class QuickLitematicaVerifierAccess {
    private QuickLitematicaVerifierAccess() {
    }

    public static MismatchType getMismatchType(BlockMismatch mismatch) {
        return mismatch.mismatchType;
    }

    public static BlockState getExpectedState(BlockMismatch mismatch) {
        return mismatch.stateExpected;
    }

    public static BlockState getFoundState(BlockMismatch mismatch) {
        return mismatch.stateFound;
    }

    public static BlockMismatchExtension getMismatchExtension(BlockMismatch mismatch) {
        return (BlockMismatchExtension) mismatch;
    }

    public static MismatchType getRenderType(MismatchRenderPos position) {
        return position.type;
    }

    public static BlockPos getRenderPos(MismatchRenderPos position) {
        return position.pos;
    }

    public static ChunkPos getBoxChunkPos(IntBoundingBox box) {
        return new ChunkPos(box.minX >> 4, box.minZ >> 4);
    }

    public static boolean boxContainsPos(IntBoundingBox box, BlockPos pos) {
        return box.containsPos(pos);
    }

    public static boolean isPositionWithinPlacementChunk(SchematicPlacement placement, int chunkX, int chunkZ, BlockPos pos) {
        if (placement == null) {
            return false;
        }
        for (IntBoundingBox box : placement.getBoxesWithinChunk(chunkX, chunkZ).values()) {
            if (box.containsPos(pos)) {
                return true;
            }
        }
        return false;
    }

    public static int getWorldMaxY(World world) {
        return world.getBottomY() + world.getHeight();
    }

    public static int[] getPlacementChunkYRange(SchematicPlacement placement, int chunkX, int chunkZ, int defaultMinY, int defaultMaxY) {
        if (placement == null) {
            return new int[] {defaultMinY, defaultMaxY};
        }
        Map<String, IntBoundingBox> boxes = placement.getBoxesWithinChunk(chunkX, chunkZ);
        if (boxes == null || boxes.isEmpty()) {
            return new int[] {defaultMinY, defaultMaxY};
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (IntBoundingBox box : boxes.values()) {
            minY = Math.min(minY, box.minY);
            maxY = Math.max(maxY, box.maxY);
        }
        return new int[] {minY, maxY};
    }

    public static boolean hasServuxServer() {
        return EntitiesDataStorage.getInstance().hasServuxServer();
    }

    public static boolean hasBackupStatus() {
        return EntitiesDataStorage.getInstance().hasBackupStatus();
    }

    public static boolean getIfReceivedBackupPackets() {
        return EntitiesDataStorage.getInstance().getIfReceivedBackupPackets();
    }

    public static boolean hasCompletedChunk(ChunkPos chunkPos) {
        return EntitiesDataStorage.getInstance().hasCompletedChunk(chunkPos);
    }

    public static boolean hasPendingChunk(ChunkPos chunkPos) {
        return EntitiesDataStorage.getInstance().hasPendingChunk(chunkPos);
    }

    public static boolean isStorageWorldMatching(ClientWorld worldClient) {
        return Objects.equals(EntitiesDataStorage.getInstance().getWorld(), worldClient);
    }

    public static NbtCompound getBlockEntityNbtFromStorage(BlockPos pos) {
        return EntitiesDataStorage.getInstance().getFromBlockEntityCacheNbt(pos);
    }

    public static BlockEntity getBlockEntityFromStorage(BlockPos pos) {
        return EntitiesDataStorage.getInstance().getFromBlockEntityCache(pos);
    }

    public static void requestBlockEntity(World world, BlockPos pos) {
        ensureEntityDataSyncEnabled();
        EntitiesDataStorage.getInstance().requestBlockEntity(world, pos);
    }

    public static void requestServuxBulkEntityData(ChunkPos chunkPos, int minY, int maxY) {
        ensureEntityDataSyncEnabled();
        EntitiesDataStorage.getInstance().requestServuxBulkEntityData(chunkPos, minY, maxY);
    }

    public static void requestBackupBulkEntityData(ChunkPos chunkPos, int minY, int maxY) {
        ensureEntityDataSyncEnabled();
        EntitiesDataStorage.getInstance().requestBackupBulkEntityData(chunkPos, minY, maxY);
    }

    private static void ensureEntityDataSyncEnabled() {
        Configs.Generic.ENTITY_DATA_SYNC.setBooleanValue(true);
        Configs.Generic.ENTITY_DATA_SYNC_BACKUP.setBooleanValue(true);
    }

    public static void logContainerVerificationProblems(
            Logger logger,
            int expected,
            int checked,
            int pending,
            int unsupportedExpected,
            int missingActualBlockEntities,
            int unsupportedActual,
            int unavailableWorldBoxes,
            int unavailableInventories,
            int sizeMismatches,
            int requestedChunks,
            boolean integratedServer,
            boolean entityDataSync,
            List<String> samples
    ) {
        EntitiesDataStorage storage = EntitiesDataStorage.getInstance();
        logger.warn(
                "[ContainerVerifier] Container data was not fully comparable: expectedContainers={}, checked={}, pending={}, unsupportedExpected={}, missingActualBlockEntities={}, unsupportedActual={}, unavailableWorldBoxes={}, unavailableInventories={}, sizeMismatches={}, requestedChunks={}, integratedServer={}, servux={}, backupPackets={}, entityDataSync={}, samples={}",
                expected,
                checked,
                pending,
                unsupportedExpected,
                missingActualBlockEntities,
                unsupportedActual,
                unavailableWorldBoxes,
                unavailableInventories,
                sizeMismatches,
                requestedChunks,
                integratedServer,
                storage.hasServuxServer(),
                storage.getIfReceivedBackupPackets(),
                entityDataSync,
                samples
        );
    }

    public static ItemStack parseItemStack(RegistryWrapper.WrapperLookup registries, NbtCompound nbt) {
        if (nbt == null || registries == null) {
            return ItemStack.EMPTY;
        }
        return ItemStack.fromNbt(registries, nbt).orElse(ItemStack.EMPTY);
    }

    public static String getItemStackSignature(RegistryWrapper.WrapperLookup registries, ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        return stack.toNbt(registries).toString();
    }

    public static ItemStack parseRecordStack(RegistryWrapper.WrapperLookup registries, NbtCompound cachedNbt) {
        if (cachedNbt == null) {
            return ItemStack.EMPTY;
        }
        return cachedNbt.getCompound("RecordItem")
                .flatMap(nbt -> ItemStack.fromNbt(registries, nbt))
                .orElse(ItemStack.EMPTY);
    }

    public static NbtList getItemsList(NbtCompound nbt) {
        if (nbt == null || !nbt.contains("Items")) {
            return null;
        }
        return nbt.getList("Items").orElse(null);
    }

    public static NbtCompound getCompound(NbtList list, int index) {
        if (list == null || index < 0 || index >= list.size()) {
            return null;
        }
        return list.getCompound(index).orElse(null);
    }

    public static int getSlotByte(NbtCompound nbt) {
        if (nbt == null) {
            return 0;
        }
        return nbt.getByte("Slot").orElse((byte) 0) & 255;
    }

    public static boolean isTrustedCachedInventory(NbtCompound cachedNbt, Inventory cachedInventory) {
        if (cachedNbt == null) {
            return false;
        }
        return cachedNbt.contains("Items") || (cachedInventory != null && !cachedInventory.isEmpty());
    }

    public static boolean isServerEmptyContainerNbt(NbtCompound cachedNbt, Inventory expected) {
        return false;
    }

    public static boolean hasCachedContainerData(NbtCompound cachedNbt, Inventory expected) {
        return cachedNbt != null;
    }

    public static boolean sameEnchantmentsTooltip(ItemEnchantmentsComponent expected, ItemEnchantmentsComponent found) {
        return true;
    }

    public static NbtCompound getSchematicBlockEntityNbt(LitematicaSchematic schematic, String region, BlockPos pos) {
        if (schematic == null || region == null || pos == null) {
            return null;
        }
        Map<BlockPos, NbtCompound> map = schematic.getBlockEntityMapForRegion(region);
        return map != null ? map.get(pos) : null;
    }
}
