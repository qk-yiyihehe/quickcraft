package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import fi.dy.masa.litematica.data.EntityDataManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 高亮只记录库存是否非空；界面快照补足多人客户端未同步的容器库存。 */
public final class QuickLitematicaContainerFillState {
    // 读取与请求各扫描至多 32 个候选位置；每 tick 最多请求一个库存，同位置间隔至少一秒。
    private static final int CHECKS_PER_TICK = 32;
    private static final int REFRESH_TICKS = 20;
    private static final Map<BlockPos, State> STATES = new HashMap<>();
    private static Level cachedWorld;
    private static Level inventoryWorld;
    private static List<QuickLitematicaContainerHighlight.ProjectedContainer> candidates;
    private static int cursor;
    private static int requestCursor;
    private static long tick;
    private static BlockPos pendingPos;
    private static int pendingTicks;
    private static AbstractContainerMenu activeHandler;
    private static BlockPos activePos;
    private static List<InventoryRead> pendingRead;

    private QuickLitematicaContainerFillState() { }

    static void clear() {
        STATES.clear();
        cachedWorld = null;
        inventoryWorld = null;
        candidates = null;
        cursor = 0;
        requestCursor = 0;
        tick = 0;
        pendingPos = null;
        pendingTicks = 0;
        activeHandler = null;
        activePos = null;
        pendingRead = null;
    }

    private static boolean prepare(Minecraft client) {
        if (client.level != cachedWorld) {
            clear();
            cachedWorld = client.level;
        }
        inventoryWorld = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        return client.level != null && inventoryWorld != null
                && QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS.getBooleanValue();
    }

    public static void rememberContainerUse(Minecraft client, BlockHitResult hit) {
        if (!prepare(client)) { return; }
        captureScreen(client);
        pendingPos = client.level.getBlockEntity(hit.getBlockPos()) instanceof Container
                ? hit.getBlockPos().immutable() : null;
        pendingTicks = 40;
    }

    public static void onContentsReceived(int syncId) {
        Minecraft client = Minecraft.getInstance();
        if (!prepare(client) || client.player == null || pendingPos == null || pendingTicks <= 0) { return; }
        AbstractContainerMenu handler = client.player.containerMenu;
        if (handler.containerId == syncId && QuickLitematicaContainerScreenBinding.isSupportedContainerHandler(handler)) {
            activeHandler = handler;
            activePos = pendingPos;
            pendingPos = null;
            captureScreen(client);
        }
    }

    private static void captureScreen(Minecraft client) {
        if (activeHandler == null || activePos == null) { return; }
        boolean nonempty = activeHandler.slots.stream()
                .anyMatch(slot -> !(slot.container instanceof Inventory) && slot.hasItem());
        rememberScreenState(activePos, nonempty);
        BlockPos paired = pairedPos(client.level, activePos);
        if (paired != null) { rememberScreenState(paired, nonempty); }
        // 自动填充可能已关箱，处理器仍保留最后一次操作的槽位内容。
        if (client.player == null || client.player.containerMenu != activeHandler) {
            activeHandler = null;
            activePos = null;
        }
    }

    private static void rememberScreenState(BlockPos pos, boolean nonempty) {
        State state = stateAt(pos);
        if (state == null) { return; }
        state.nonempty = nonempty;
        state.screenRevision++;
        state.nbt = EntityDataManager.getInstance().getCache().getBlockEntityNbtFromCache(pos);
        BlockPos paired = pairedPos(cachedWorld, pos);
        state.pairedNbt = paired != null ? EntityDataManager.getInstance().getCache().getBlockEntityNbtFromCache(paired) : null;
    }

    static void tick(Minecraft client, List<QuickLitematicaContainerHighlight.ProjectedContainer> positions) {
        if (!prepare(client)) { clear(); return; }
        tick++;
        if (pendingTicks > 0 && --pendingTicks == 0) { pendingPos = null; }
        if (candidates != positions) {
            candidates = positions;
            var retained = new HashSet<BlockPos>();
            positions.forEach(container -> retained.add(container.pos()));
            STATES.keySet().retainAll(retained);
        }
        if (positions.isEmpty() || client.player == null) { captureScreen(client); return; }
        Level world = inventoryWorld;
        boolean integrated = world instanceof ServerLevel;
        int maxDistance = (client.options.renderDistance().get() + 2) * 16;
        BlockPos playerPos = client.player.blockPosition();
        if (integrated && pendingRead != null) { captureScreen(client); return; }
        List<BlockPos> integratedPositions = new ArrayList<>();
        for (int checked = 0; checked < Math.min(CHECKS_PER_TICK, positions.size()); checked++) {
            var container = positions.get(cursor++ % positions.size());
            if (cursor >= positions.size()) { cursor = 0; }
            BlockPos pos = container.pos();
            if (!canCheck(client, container, playerPos, maxDistance)) { continue; }
            if (integrated) {
                integratedPositions.add(pos);
                continue;
            }
            State state = stateAt(pos);
            if (state == null) { continue; }
            BlockPos paired = pairedPos(world, pos);
            if (EntityDataManager.getInstance().getClientWorld() == client.level) {
                CompoundTag nbt = EntityDataManager.getInstance().getCache().getBlockEntityNbtFromCache(pos);
                CompoundTag pairedNbt = paired != null ? EntityDataManager.getInstance().getCache().getBlockEntityNbtFromCache(paired) : null;
                // 已打开界面的快照优先于同一份旧缓存；只有新的库存数据才能覆盖它。
                if (nbt != state.nbt || pairedNbt != state.pairedNbt) {
                    Boolean known = combine(cachedNonempty(pos, nbt), paired != null ? cachedNonempty(paired, pairedNbt) : Boolean.FALSE);
                    if (known != null) { state.nonempty = known; }
                    state.nbt = nbt;
                    state.pairedNbt = pairedNbt;
                }
            }
        }
        if (world instanceof ServerLevel serverWorld) {
            requestIntegratedInventory(client, serverWorld, integratedPositions);
        } else {
            requestNextInventory(client, positions, playerPos, maxDistance);
        }
        // 界面是当前操作的直接结果，必须最后记录，不能被本 tick 的旧库存覆盖。
        captureScreen(client);
    }

    private static boolean canCheck(Minecraft client,
                                    QuickLitematicaContainerHighlight.ProjectedContainer container,
                                    BlockPos playerPos, int maxDistance) {
        BlockPos pos = container.pos();
        return Math.abs(pos.getX() - playerPos.getX()) <= maxDistance
                && Math.abs(pos.getZ() - playerPos.getZ()) <= maxDistance
                && DataManager.getRenderLayerRange().isPositionWithinRange(pos)
                && client.level.hasChunkAt(pos)
                && client.level.getBlockState(pos).getBlock() == container.block();
    }

    private static void requestNextInventory(Minecraft client,
                                            List<QuickLitematicaContainerHighlight.ProjectedContainer> positions,
                                            BlockPos playerPos, int maxDistance) {
        // 独立轮转请求游标，避免大投影中每批只有第一个容器能获得库存数据。
        for (int checked = 0; checked < Math.min(CHECKS_PER_TICK, positions.size()); checked++) {
            var container = positions.get(requestCursor++ % positions.size());
            if (requestCursor >= positions.size()) { requestCursor = 0; }
            if (!canCheck(client, container, playerPos, maxDistance)) { continue; }
            BlockPos pos = container.pos();
            State state = stateAt(pos);
            if (state == null || tick < state.nextRequestTick) { continue; }
            BlockPos paired = pairedPos(client.level, pos);
            BlockPos target = paired != null && state.requestPaired && client.level.hasChunkAt(paired) ? paired : pos;
            QuickLitematicaContainerInventory.ensureEntityDataSyncEnabled();
            QuickLitematicaContainerInventory.requestInventoryData(client.level, target);
            state.requestPaired = !state.requestPaired;
            state.nextRequestTick = tick + REFRESH_TICKS;
            return;
        }
    }

    static Boolean combine(Boolean first, Boolean second) {
        if (Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second)) { return true; }
        return first != null && second != null ? false : null;
    }

    static void requestIntegratedInventory(Minecraft client, ServerLevel world, List<BlockPos> positions) {
        if (pendingRead != null || positions.isEmpty()) { return; }
        List<InventoryRead> reads = new ArrayList<>();
        for (BlockPos pos : positions) {
            State state = stateAt(pos);
            if (state != null) { reads.add(new InventoryRead(pos, state, state.screenRevision)); }
        }
        if (reads.isEmpty()) { return; }
        pendingRead = reads;
        Level clientWorld = cachedWorld;
        // World/Level.getBlockEntity 在非服务端线程会返回 null；只在所属线程读取库存。
        world.getServer().execute(() -> {
            Map<InventoryRead, Boolean> results = new HashMap<>();
            for (InventoryRead read : reads) {
                Boolean first = directNonempty(world, read.pos());
                BlockPos paired = first != null ? pairedPos(world, read.pos()) : null;
                Boolean second = paired != null ? directNonempty(world, paired) : Boolean.FALSE;
                results.put(read, combine(first, second));
            }
            client.execute(() -> {
                if (pendingRead != reads) { return; }
                pendingRead = null;
                if (client.level != clientWorld || inventoryWorld != world) { return; }
                results.forEach((read, nonempty) -> {
                    // 不让较早的服务端快照覆盖此后发生的界面操作。
                    if (STATES.get(read.pos()) == read.state()
                            && read.state().screenRevision == read.screenRevision()) {
                        read.state().nonempty = nonempty;
                    }
                });
            });
        });
    }

    private static Boolean directNonempty(Level world, BlockPos pos) {
        return world.hasChunkAt(pos) && world.getBlockEntity(pos) instanceof Container inventory
                ? !inventory.isEmpty() : null;
    }

    private static Boolean cachedNonempty(BlockPos pos, CompoundTag nbt) {
        if (nbt == null || !(EntityDataManager.getInstance().getCache().getBlockEntityFromCache(pos) instanceof Container inventory)) {
            return null;
        }
        // 请求可能先缓存客户端空壳；没有服务器库存通道时不能用它覆盖开箱快照。
        if (!inventory.isEmpty()) { return true; }
        boolean synchronizedInventory = EntityDataManager.getInstance().hasServuxServer()
                || EntityDataManager.getInstance().hasBackupStatus()
                || EntityDataManager.getInstance().getIfReceivedBackupPackets();
        return nbt.contains("Items") && synchronizedInventory ? Boolean.FALSE : null;
    }

    private static BlockPos pairedPos(Level world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return QuickLitematicaContainerInventory.getChestType(state) != ChestType.SINGLE
                ? ChestBlock.getConnectedBlockPos(pos, state) : null;
    }

    private static State stateAt(BlockPos pos) {
        BlockEntity entity = cachedWorld.getBlockEntity(pos);
        if (!(entity instanceof Container)) { STATES.remove(pos); return null; }
        State state = STATES.get(pos);
        if (state == null || state.entity != entity) {
            state = new State(entity);
            STATES.put(pos.immutable(), state);
        }
        return state;
    }

    static boolean isFilled(BlockPos pos) {
        State state = STATES.get(pos);
        return state != null && Boolean.TRUE.equals(state.nonempty)
                && cachedWorld != null && cachedWorld.getBlockEntity(pos) == state.entity;
    }

    private static final class State {
        private final BlockEntity entity;
        private Boolean nonempty;
        private long screenRevision;
        private CompoundTag nbt;
        private CompoundTag pairedNbt;
        private long nextRequestTick;
        private boolean requestPaired;

        private State(BlockEntity entity) { this.entity = entity; }
    }

    private record InventoryRead(BlockPos pos, State state, long screenRevision) { }
}
