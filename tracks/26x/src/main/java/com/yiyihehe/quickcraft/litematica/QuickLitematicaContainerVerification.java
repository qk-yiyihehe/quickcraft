package com.yiyihehe.quickcraft.litematica;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaVerifierPalette;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.BlockMismatchExtension;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatchKey;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ExpectedContainer;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.VerifierExtension;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaVerifierAccess;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 容器校验的状态、数据请求和结果编排；宿主只提供原版验证器的状态。 */
public final class QuickLitematicaContainerVerification implements VerifierExtension {

    public interface Host {
        ClientLevel quickcraft$verificationWorldClient();
        SchematicPlacement quickcraft$verificationSchematicPlacement();
        Set<ChunkPos> quickcraft$verificationRequiredChunks();
        List<MismatchRenderPos> quickcraft$verificationMismatchPositionsForRender();
        List<BlockPos> quickcraft$verificationMismatchBlockPositionsForRender();
        Set<MismatchType> quickcraft$verificationSelectedCategories();
        HashMultimap<MismatchType, BlockMismatch> quickcraft$verificationSelectedEntries();
        List<String> quickcraft$verificationInfoHudLines();
        boolean quickcraft$verificationFinished();
        void quickcraft$setTotalRequiredChunks(int count);
        void quickcraft$updateVerificationOverlays();
        boolean quickcraft$isVerificationEntrySelected(BlockMismatch mismatch);
        SchematicVerifier quickcraft$verifier();
        List<SchematicVerifier> quickcraft$activeVerifiers();
    }

    private final Host host;

    public QuickLitematicaContainerVerification(Host host) {
        this.host = host;
    }

    private static final Logger QUICKCRAFT_LOGGER = LoggerFactory.getLogger("QuickCraft-ContainerVerifier");

    private final Map<MismatchType, List<ContainerMismatch>> quickcraft$containerMismatches = new HashMap<>();

    private final Map<MismatchType, List<BlockPos>> quickcraft$containerPositionsClosest = new HashMap<>();

    private final Map<ContainerMismatchKey, ContainerMismatch> quickcraft$containerMismatchesByKey = new HashMap<>();

    private final List<BlockMismatch> quickcraft$selectedContainerMismatches = new ArrayList<>();

    private final Set<BlockPos> quickcraft$expectedContainerPositions = new HashSet<>();

    private final Set<BlockPos> quickcraft$checkedContainerPositions = new HashSet<>();

    private final Set<BlockPos> quickcraft$pendingContainerPositions = new HashSet<>();

    private final Map<BlockPos, List<ItemStack>> quickcraft$missingContainerStacks = new HashMap<>();

    private final Set<ChunkPos> quickcraft$requestedContainerDataChunks = new HashSet<>();

    private final Set<ChunkPos> quickcraft$containerDataChunks = new HashSet<>();

    private boolean quickcraft$containerOnly;

    private int quickcraft$refreshCursor;

    private boolean quickcraft$diagnosticLogPending;

    private int quickcraft$unsupportedExpectedContainers;

    private int quickcraft$missingActualBlockEntities;

    private int quickcraft$unsupportedActualContainers;

    private int quickcraft$unavailableWorldBoxes;

    private int quickcraft$unavailableFoundInventories;

    private int quickcraft$inventorySizeMismatches;

    private final List<String> quickcraft$diagnosticSamples = new ArrayList<>();

    @Override
    public List<BlockMismatch> quickcraft$getSelectedInventoryMismatches() {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return List.of();
        }

        return Collections.unmodifiableList(this.quickcraft$selectedContainerMismatches);
    }

    @Override
    public List<ContainerMismatch> quickcraft$getContainerMismatches() {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(this.quickcraft$containerMismatchesByKey.values()));
    }

    @Override
    public List<ItemStack> quickcraft$getMissingContainerStacks() {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return List.of();
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (List<ItemStack> containerStacks : this.quickcraft$missingContainerStacks.values()) {
            containerStacks.forEach(stack -> stacks.add(stack.copy()));
        }
        return Collections.unmodifiableList(stacks);
    }

    @Override
    public int quickcraft$getWrongInventoryCount() {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return 0;
        }

        int count = 0;

        for (List<ContainerMismatch> mismatches : this.quickcraft$containerMismatches.values()) {
            count += mismatches.size();
        }

        return count;
    }

    @Override
    public int quickcraft$getContainerMismatchCount(MismatchType type) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return 0;
        }

        return this.quickcraft$containerMismatches.getOrDefault(type, List.of()).size();
    }

    @Override
    public int quickcraft$getExpectedContainerCount() {
        return QuickLitematicaContainerVerifier.isEnabled() ? this.quickcraft$expectedContainerPositions.size() : 0;
    }

    @Override
    public int quickcraft$getCheckedContainerCount() {
        return QuickLitematicaContainerVerifier.isEnabled() ? this.quickcraft$checkedContainerPositions.size() : 0;
    }

    @Override
    public void quickcraft$setContainerOnly(boolean containerOnly) {
        this.quickcraft$containerOnly = containerOnly;
    }

    @Override
    public boolean quickcraft$isContainerOnly() {
        return this.quickcraft$containerOnly;
    }

    @Override
    public int quickcraft$getPendingContainerCount() {
        return QuickLitematicaContainerVerifier.isEnabled() ? this.quickcraft$pendingContainerPositions.size() : 0;
    }

    @Override
    public List<ContainerMismatch> quickcraft$refreshContainerMismatchAt(BlockPos pos, Container foundInventory, Set<Integer> foundDisabledSlots) {
        if (!QuickLitematicaContainerVerifier.isEnabled() || foundInventory == null) {
            return null;
        }

        Level bestWorld = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(Minecraft.getInstance());
        List<ContainerMismatch> mismatches = bestWorld != null
                ? this.quickcraft$collectContainerMismatchesFromInventory(bestWorld, pos, foundInventory, foundDisabledSlots)
                : null;

        if (mismatches != null) {
            this.quickcraft$markContainerChecked(pos);
        } else {
            this.quickcraft$markContainerPending(pos);
        }

        if (mismatches != null && this.quickcraft$replaceContainerMismatchesAt(pos, mismatches)) {
            this.host.quickcraft$updateVerificationOverlays();
        }

        return mismatches;
    }

    public LevelChunk quickcraft$useBestWorldForSinglePlayer(ClientLevel clientWorld, int chunkX, int chunkZ){
        Level bestWorld = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(Minecraft.getInstance());
        return bestWorld != null ? bestWorld.getChunk(chunkX, chunkZ) : clientWorld.getChunk(chunkX, chunkZ);
    }

    public boolean quickcraft$waitForContainerData(ChunkManagerSchematic chunkManager, int chunkX, int chunkZ){
        return chunkManager.hasChunk(chunkX, chunkZ)
                && this.quickcraft$canProcessContainerDataChunk(new ChunkPos(chunkX, chunkZ));
    }

    @Override
    public void quickcraft$collectContainerInventories(
            ChunkAccess chunkClient,
            ChunkAccess chunkSchematic,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }

        ChunkPos chunkPos = new ChunkPos(minX >> 4, minZ >> 4);

        if (!this.quickcraft$containerDataChunks.contains(chunkPos)) {
            return;
        }

        Level foundWorld = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(Minecraft.getInstance());

        if (foundWorld == null) {
            this.quickcraft$unavailableWorldBoxes++;
            if (this.quickcraft$diagnosticSamples.size() < 5) {
                this.quickcraft$diagnosticSamples.add("world_unavailable@" + chunkPos);
            }
            return;
        }

        boolean renderLayers = this.host.quickcraft$verificationSchematicPlacement().getSchematicVerifierType() == BlockInfoListType.RENDER_LAYERS;

        for (BlockPos candidate : chunkSchematic.getBlockEntitiesPos()) {
            if (candidate.getX() < minX || candidate.getX() > maxX
                    || candidate.getY() < minY || candidate.getY() > maxY
                    || candidate.getZ() < minZ || candidate.getZ() > maxZ
                    || (renderLayers && !DataManager.getRenderLayerRange().isPositionWithinRange(candidate))) {
                continue;
            }

            BlockPos pos = candidate.immutable();
            BlockState schematicState = chunkSchematic.getBlockState(pos);
            BlockEntity expectedBlockEntity = chunkSchematic.getBlockEntity(pos);
            ExpectedContainer expectedContainer = QuickLitematicaContainerVerifier.getExpectedContainerPartAt(
                    this.host.quickcraft$verificationSchematicPlacement(),
                    pos
            );
            if (expectedContainer == null && !(expectedBlockEntity instanceof Container)) {
                continue;
            }

            this.quickcraft$expectedContainerPositions.add(pos);
            List<ContainerMismatch> mismatches = this.quickcraft$collectContainerMismatchesDuringVerification(
                    foundWorld,
                    chunkClient,
                    pos,
                    schematicState,
                    expectedBlockEntity,
                    expectedContainer
            );

            if (mismatches != null) {
                this.quickcraft$markContainerChecked(pos);
                this.quickcraft$removeContainerMismatchesAt(pos);
                mismatches.forEach(this::quickcraft$addContainerMismatch);
            } else {
                this.quickcraft$markContainerPending(pos);
                this.quickcraft$requestContainerInventoryData(this.host.quickcraft$verificationWorldClient(), pos);
            }
        }
    }

    public List<BlockMismatch> quickcraft$getInventoryMismatchOverview(MismatchType type) {
        if (QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            if (!QuickLitematicaContainerVerifier.isEnabled()) {
                return new ArrayList<>();
            }

            List<BlockMismatch> list = this.quickcraft$createBlockMismatchesFor(type);
            Collections.sort(list);
            return list;
        }
        return null;
    }

    public void quickcraft$addInventoryMismatchOverview(List<BlockMismatch> vanillaResult) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }

        List<BlockMismatch> list = vanillaResult;

        Collections.sort(list);
    }

    public ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> quickcraft$getInventoryMismatchMap(MismatchType type) {
        if (QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> map = ArrayListMultimap.create();

            if (!QuickLitematicaContainerVerifier.isEnabled()) {
                return map;
            }

            for (ContainerMismatch mismatch : this.quickcraft$containerMismatches.getOrDefault(type, List.of())) {
                map.put(Pair.of(mismatch.expectedState(), mismatch.foundState()), mismatch.pos());
            }

            return map;
        }
        return null;
    }

    public void quickcraft$updateClosestInventoryPositions(BlockPos centerPos, int maxEntries) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            this.quickcraft$containerPositionsClosest.clear();
            return;
        }

        PositionUtils.BLOCK_POS_COMPARATOR.setReferencePosition(centerPos);
        PositionUtils.BLOCK_POS_COMPARATOR.setClosestFirst(true);

        for (MismatchType type : QuickLitematicaContainerVerifier.getContainerMismatchTypes()) {
            List<BlockPos> positions = this.quickcraft$getSelectedContainerPositionsForType(type);
            positions.sort(PositionUtils.BLOCK_POS_COMPARATOR);
            this.quickcraft$containerPositionsClosest.put(type, positions);
        }
    }

    public void quickcraft$combineClosestInventoryPositions(BlockPos centerPos, int maxEntries) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }

        for (MismatchType type : QuickLitematicaContainerVerifier.getContainerMismatchTypes()) {
            List<BlockPos> positions = this.quickcraft$containerPositionsClosest.getOrDefault(type, List.of());

            for (BlockPos pos : positions) {
                if (this.host.quickcraft$verificationMismatchPositionsForRender().size() >= maxEntries) {
                    return;
                }

                this.host.quickcraft$verificationMismatchPositionsForRender().add(new MismatchRenderPos(type, pos));
            }
        }
    }

    public List<BlockPos> quickcraft$getClosestInventoryPositions(MismatchType type) {
        if (QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            return QuickLitematicaContainerVerifier.isEnabled()
                    ? this.quickcraft$containerPositionsClosest.getOrDefault(type, List.of())
                    : List.of();
        }
        return null;
    }

    public void quickcraft$trackSelectedInventoryMismatch(BlockMismatch mismatch) {
        if (!QuickLitematicaContainerVerifier.isEnabled()
                || !QuickLitematicaContainerVerifier.isContainerMismatchType(QuickLitematicaVerifierAccess.getMismatchType(mismatch))) {
            return;
        }

        if (this.host.quickcraft$isVerificationEntrySelected(mismatch)) {
            if (!this.quickcraft$selectedContainerMismatches.contains(mismatch)) {
                this.quickcraft$selectedContainerMismatches.add(mismatch);
            }
        } else {
            this.quickcraft$selectedContainerMismatches.remove(mismatch);
        }
    }

    public void quickcraft$removeSelectedInventoryMismatches(MismatchType type) {
        if (QuickLitematicaContainerVerifier.isEnabled()
                && QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            this.quickcraft$selectedContainerMismatches.removeIf(mismatch -> QuickLitematicaVerifierAccess.getMismatchType(mismatch) == type);
        }
    }

    public boolean quickcraft$forgetIgnoredInventoryMismatch(BlockMismatch mismatch, boolean updateOverlay) {
        if (!QuickLitematicaContainerVerifier.isEnabled()
                || !QuickLitematicaContainerVerifier.isContainerMismatchType(QuickLitematicaVerifierAccess.getMismatchType(mismatch))) {
            return false;
        }

        ContainerMismatchKey key = ((BlockMismatchExtension) (Object) mismatch).quickcraft$getContainerMismatchKey();

        if (key != null) {
            ContainerMismatch removed = this.quickcraft$containerMismatchesByKey.remove(key);

            if (removed != null) {
                this.quickcraft$containerMismatches.getOrDefault(removed.type(), List.of()).remove(removed);
            }
        }

        this.quickcraft$selectedContainerMismatches.remove(mismatch);

        if (updateOverlay) {
            this.host.quickcraft$updateVerificationOverlays();
        }

        return true;
    }

    public void quickcraft$clearInventoryData() {
        this.quickcraft$clearContainerData();
    }

    public void quickcraft$requestContainerDataOnStart(ClientLevel worldClient, WorldSchematic worldSchematic, SchematicPlacement schematicPlacement, ICompletionListener completionListener) {
        if (schematicPlacement == null) {
            return;
        }

        boolean enabled = QuickLitematicaContainerVerifier.isEnabled();
        this.quickcraft$diagnosticLogPending = enabled;
        this.quickcraft$unsupportedExpectedContainers = 0;
        this.quickcraft$missingActualBlockEntities = 0;
        this.quickcraft$unsupportedActualContainers = 0;
        this.quickcraft$unavailableWorldBoxes = 0;
        this.quickcraft$unavailableFoundInventories = 0;
        this.quickcraft$inventorySizeMismatches = 0;
        this.quickcraft$diagnosticSamples.clear();

        if (enabled) {
            this.quickcraft$rebuildContainerDataChunks(worldSchematic, schematicPlacement);
            this.quickcraft$requestContainerInventoryDataChunks(worldClient, this.quickcraft$containerDataChunks);
        }
        if (this.quickcraft$containerOnly) {
            this.host.quickcraft$verificationRequiredChunks().retainAll(this.quickcraft$containerDataChunks);
            this.host.quickcraft$setTotalRequiredChunks(this.host.quickcraft$verificationRequiredChunks().size());
            SchematicVerifier verifier = this.host.quickcraft$verifier();
            this.host.quickcraft$activeVerifiers().remove(verifier);
            InfoHud.getInstance().removeInfoHudRenderer(verifier, false);
        }
    }

    public void quickcraft$logContainerVerificationProblems(Boolean vanillaResult) {
        if (!this.quickcraft$diagnosticLogPending || !Boolean.TRUE.equals(vanillaResult)) {
            return;
        }

        this.quickcraft$diagnosticLogPending = false;
        if (this.quickcraft$unsupportedExpectedContainers == 0
                && this.quickcraft$missingActualBlockEntities == 0
                && this.quickcraft$unsupportedActualContainers == 0
                && this.quickcraft$unavailableWorldBoxes == 0
                && this.quickcraft$unavailableFoundInventories == 0
                && this.quickcraft$inventorySizeMismatches == 0
                && this.quickcraft$pendingContainerPositions.isEmpty()) {
            return;
        }

        EntityDataManager storage = EntityDataManager.getInstance();
        QUICKCRAFT_LOGGER.warn(
                "[ContainerVerifier] Container data was not fully comparable: expectedContainers={}, checked={}, pending={}, unsupportedExpected={}, missingActualBlockEntities={}, unsupportedActual={}, unavailableWorldBoxes={}, unavailableInventories={}, sizeMismatches={}, requestedChunks={}, integratedServer={}, servux={}, backupPackets={}, entityDataSync={}, samples={}",
                this.quickcraft$expectedContainerPositions.size(),
                this.quickcraft$checkedContainerPositions.size(),
                this.quickcraft$pendingContainerPositions.size(),
                this.quickcraft$unsupportedExpectedContainers,
                this.quickcraft$missingActualBlockEntities,
                this.quickcraft$unsupportedActualContainers,
                this.quickcraft$unavailableWorldBoxes,
                this.quickcraft$unavailableFoundInventories,
                this.quickcraft$inventorySizeMismatches,
                this.quickcraft$requestedContainerDataChunks.size(),
                DataManager.getInstance().hasIntegratedServer(),
                storage.hasServuxServer(),
                storage.getIfReceivedBackupPackets(),
                Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue(),
                this.quickcraft$diagnosticSamples
        );
    }

    public void quickcraft$refreshContainerMismatches() {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            if (!this.quickcraft$containerMismatchesByKey.isEmpty()) {
                this.quickcraft$clearContainerData();
                this.host.quickcraft$updateVerificationOverlays();
            }
            return;
        }

        Minecraft client = Minecraft.getInstance();

        if (client.level == null || client.level.getGameTime() % 10 != 0) {
            return;
        }

        Level bestWorld = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);

        if (bestWorld == null || this.quickcraft$pendingContainerPositions.isEmpty()) {
            return;
        }

        List<BlockPos> positions = new ArrayList<>();

        // 多人服容器 NBT 可能稍后才由 Servux/OP 查询返回，pending 位置也要持续复查。
        for (BlockPos pos : this.quickcraft$pendingContainerPositions) {
            if (!positions.contains(pos)) {
                positions.add(pos);
            }
        }

        if (positions.isEmpty()) {
            return;
        }

        boolean changed = false;
        int checks = Math.min(128, positions.size());

        for (int i = 0; i < checks; i++) {
            if (this.quickcraft$refreshCursor >= positions.size()) {
                this.quickcraft$refreshCursor = 0;
            }

            BlockPos pos = positions.get(this.quickcraft$refreshCursor++);
            List<ContainerMismatch> mismatches = this.quickcraft$collectContainerMismatchesFromWorld(bestWorld, pos);

            if (mismatches != null) {
                this.quickcraft$markContainerChecked(pos);
                changed |= this.quickcraft$replaceContainerMismatchesAt(pos, mismatches);
            } else {
                this.quickcraft$markContainerPending(pos);
                this.quickcraft$requestContainerInventoryData(bestWorld, pos);
            }
        }

        if (changed) {
            this.host.quickcraft$updateVerificationOverlays();
        }
    }

    public void quickcraft$splitInventoryHudLines(@Nullable MismatchType mismatchType, List<MismatchRenderPos> positionList) {
        if (!QuickLitematicaContainerVerifier.isEnabled()
                || positionList.stream().noneMatch(pos -> QuickLitematicaContainerVerifier.isContainerMismatchType(QuickLitematicaVerifierAccess.getRenderType(pos)))) {
            return;
        }

        this.host.quickcraft$verificationInfoHudLines().clear();
        String rst = GuiBase.TXT_RST;
        List<MismatchRenderPos> vanilla = positionList.stream()
                .filter(pos -> !QuickLitematicaContainerVerifier.isContainerMismatchType(QuickLitematicaVerifierAccess.getRenderType(pos)))
                .toList();
        List<MismatchRenderPos> containers = positionList.stream()
                .filter(pos -> QuickLitematicaContainerVerifier.isContainerMismatchType(QuickLitematicaVerifierAccess.getRenderType(pos)))
                .toList();
        int maxLines = Configs.InfoOverlays.INFO_HUD_MAX_LINES.getIntegerValue();

        if (!vanilla.isEmpty()) {
            String title = mismatchType != null && !QuickLitematicaContainerVerifier.isContainerMismatchType(mismatchType)
                    ? mismatchType.getFormattingCode() + mismatchType.getDisplayname()
                    : GuiBase.TXT_BOLD + StringUtils.translate("litematica.gui.title.schematic_verifier_errors");
            this.host.quickcraft$verificationInfoHudLines().add(title + rst);
            this.quickcraft$addHudPositions(vanilla, maxLines);
        }

        if (!containers.isEmpty()) {
            this.host.quickcraft$verificationInfoHudLines().add(QuickLitematicaVerifierPalette.formatSectionTitle(
                    StringUtils.translate("quickcraft.litematica.verifier.title.container_errors")
            ));
            this.quickcraft$addHudPositions(containers, maxLines);
        }
    }

    private List<ContainerMismatch> quickcraft$collectContainerMismatchesDuringVerification(
            Level foundWorld,
            ChunkAccess chunkClient,
            BlockPos pos,
            BlockState schematicState,
            BlockEntity expectedBlockEntity,
            ExpectedContainer expectedContainer
    ) {
        BlockEntity foundBlockEntity = chunkClient.getBlockEntity(pos);
        Container directExpectedInventory = expectedBlockEntity instanceof Container inventory ? inventory : null;

        if (expectedContainer == null && directExpectedInventory == null) {
            return List.of();
        }

        Container expected = expectedContainer != null
                ? expectedContainer.inventory()
                : QuickLitematicaContainerVerifier.getExpectedInventory(expectedBlockEntity, directExpectedInventory);
        BlockEntity expectedData = expectedContainer != null
                ? expectedContainer.blockEntity()
                : expectedBlockEntity;
        BlockState expectedState = expectedContainer != null ? expectedContainer.state() : schematicState;
        BlockState foundState = chunkClient.getBlockState(pos);

        if (!(foundBlockEntity instanceof Container foundInventory)) {
            if (foundState.getBlock() == expectedState.getBlock()) {
                if (foundBlockEntity == null) {
                    this.quickcraft$missingActualBlockEntities++;
                    this.quickcraft$recordDiagnostic("missing_block_entity", pos, expectedState, foundState);
                } else {
                    this.quickcraft$unsupportedActualContainers++;
                    this.quickcraft$recordDiagnostic(
                            "unsupported_actual=" + foundBlockEntity.getType(),
                            pos,
                            expectedState,
                            foundState
                    );
                }
            }
            if (!fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
                if (this.quickcraft$shouldWaitForMissingInventory(foundWorld, pos, foundBlockEntity)) {
                    this.quickcraft$requestContainerInventoryData(foundWorld, pos);
                    return null;
                }
            }

            this.quickcraft$rememberMissingContainer(pos, expected);
            return List.of();
        }

        this.quickcraft$missingContainerStacks.remove(pos);
        Container found = QuickLitematicaContainerVerifier.getActualInventory(
                foundWorld,
                pos,
                foundInventory,
                expected
        );

        if (found == null) {
            this.quickcraft$unavailableFoundInventories++;
            this.quickcraft$recordDiagnostic(
                    "inventory_unavailable=" + QuickLitematicaContainerVerifier.getLastActualInventoryReadStatus(),
                    pos,
                    expectedState,
                    foundState
            );
            return null;
        }

        if (found.getContainerSize() != expected.getContainerSize()) {
            this.quickcraft$inventorySizeMismatches++;
            this.quickcraft$recordDiagnostic(
                    "size_mismatch=" + expected.getContainerSize() + "/" + found.getContainerSize(),
                    pos,
                    expectedState,
                    foundState
            );
            return null;
        }

        return QuickLitematicaContainerVerifier.findMismatches(
                pos,
                expectedState,
                foundState,
                expectedData,
                foundBlockEntity,
                expectedContainer != null
                        ? expectedContainer.disabledSlots()
                        : QuickLitematicaContainerVerifier.getDisabledSlots(expectedBlockEntity),
                QuickLitematicaContainerVerifier.getDisabledSlots(foundBlockEntity),
                expected,
                found
        );
    }

    private void quickcraft$recordDiagnostic(String reason, BlockPos pos, BlockState expected, BlockState found) {
        if (this.quickcraft$diagnosticSamples.size() < 5) {
            this.quickcraft$diagnosticSamples.add(
                    reason + "@" + pos + " expected=" + expected.getBlock() + " found=" + found.getBlock()
            );
        }
    }

    public Boolean quickcraft$removeFinishedContainerOnlyVerifier() {
        if (this.quickcraft$containerOnly && this.host.quickcraft$verificationFinished()) {
            return true;
        }
        return null;
    }

    private List<ContainerMismatch> quickcraft$collectContainerMismatchesFromWorld(Level foundWorld, BlockPos pos) {
        if (this.host.quickcraft$verificationWorldClient() == null) {
            return null;
        }

        ExpectedContainer expected = QuickLitematicaContainerVerifier.getExpectedContainerAt(
                foundWorld,
                pos,
                this.host.quickcraft$verificationSchematicPlacement()
        );
        BlockEntity foundBlockEntity = foundWorld.getBlockEntity(pos);

        if (expected == null) {
            return List.of();
        }

        if (!(foundBlockEntity instanceof Container foundInventory)) {
            if (!fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
                if (this.quickcraft$shouldWaitForMissingInventory(foundWorld, pos, foundBlockEntity)) {
                    this.quickcraft$requestContainerInventoryData(foundWorld, pos);
                    return null;
                }
            }

            ExpectedContainer expectedPart = QuickLitematicaContainerVerifier.getExpectedContainerPartAt(
                    this.host.quickcraft$verificationSchematicPlacement(), pos);
            this.quickcraft$rememberMissingContainer(
                    pos, expectedPart != null ? expectedPart.inventory() : expected.inventory());
            return List.of();
        }

        this.quickcraft$missingContainerStacks.remove(pos);

        Container found = QuickLitematicaContainerVerifier.getActualInventory(
                foundWorld,
                pos,
                foundInventory,
                expected.inventory()
        );

        if (found == null || found.getContainerSize() != expected.inventory().getContainerSize()) {
            return null;
        }

        return QuickLitematicaContainerVerifier.findMismatches(
                pos,
                expected.state(),
                foundWorld.getBlockState(pos),
                expected.blockEntity(),
                foundBlockEntity,
                expected.disabledSlots(),
                QuickLitematicaContainerVerifier.getDisabledSlots(foundBlockEntity),
                expected.inventory(),
                found
        );
    }

    private List<ContainerMismatch> quickcraft$collectContainerMismatchesFromInventory(
            Level foundWorld,
            BlockPos pos,
            Container found,
            Set<Integer> foundDisabledSlots
    ) {
        ExpectedContainer expected = QuickLitematicaContainerVerifier.getExpectedContainerAt(
                foundWorld,
                pos,
                this.host.quickcraft$verificationSchematicPlacement()
        );
        BlockEntity foundBlockEntity = foundWorld.getBlockEntity(pos);

        if (expected == null) {
            return List.of();
        }

        this.quickcraft$missingContainerStacks.remove(pos);

        if (found.getContainerSize() != expected.inventory().getContainerSize()) {
            return null;
        }

        return QuickLitematicaContainerVerifier.findMismatches(
                pos,
                expected.state(),
                foundWorld.getBlockState(pos),
                expected.blockEntity(),
                foundBlockEntity,
                expected.disabledSlots(),
                foundDisabledSlots != null ? foundDisabledSlots : foundBlockEntity != null ? QuickLitematicaContainerVerifier.getDisabledSlots(foundBlockEntity) : Set.of(),
                expected.inventory(),
                found
        );
    }

    private void quickcraft$addContainerMismatch(ContainerMismatch mismatch) {
        this.quickcraft$containerMismatches.computeIfAbsent(mismatch.type(), type -> new ArrayList<>()).add(mismatch);
        this.quickcraft$containerMismatchesByKey.put(mismatch.key(), mismatch);
        QuickLitematicaContainerVerifier.setSuppressInventorySlotHighlights(false);
    }

    private boolean quickcraft$replaceContainerMismatchesAt(BlockPos pos, List<ContainerMismatch> mismatches) {
        List<ContainerMismatchKey> oldKeys = this.quickcraft$containerMismatchesByKey.entrySet().stream()
                .filter(entry -> entry.getValue().pos().equals(pos))
                .map(Map.Entry::getKey)
                .toList();
        List<ContainerMismatchKey> newKeys = mismatches.stream()
                .map(ContainerMismatch::key)
                .toList();

        List<String> oldSignatures = this.quickcraft$containerMismatchesByKey.values().stream()
                .filter(mismatch -> mismatch.pos().equals(pos))
                .map(this::quickcraft$getContainerMismatchSignature)
                .toList();
        List<String> newSignatures = mismatches.stream()
                .map(this::quickcraft$getContainerMismatchSignature)
                .toList();

        if (oldKeys.equals(newKeys) && oldSignatures.equals(newSignatures)) {
            return false;
        }

        boolean wasSelected = this.quickcraft$isContainerPosSelected(pos);
        this.quickcraft$removeContainerMismatchesAt(pos);
        mismatches.forEach(this::quickcraft$addContainerMismatch);
        this.quickcraft$restoreContainerSelection(pos, wasSelected);
        return true;
    }

    private void quickcraft$removeContainerMismatchesAt(BlockPos pos) {
        this.quickcraft$containerMismatchesByKey.entrySet().removeIf(entry -> entry.getValue().pos().equals(pos));

        for (List<ContainerMismatch> mismatches : this.quickcraft$containerMismatches.values()) {
            mismatches.removeIf(mismatch -> mismatch.pos().equals(pos));
        }

        QuickLitematicaContainerVerifier.setSuppressInventorySlotHighlights(false);
    }

    private void quickcraft$clearContainerData() {
        this.quickcraft$containerMismatches.clear();
        this.quickcraft$containerPositionsClosest.clear();
        this.quickcraft$containerMismatchesByKey.clear();
        this.quickcraft$selectedContainerMismatches.clear();
        this.quickcraft$expectedContainerPositions.clear();
        this.quickcraft$checkedContainerPositions.clear();
        this.quickcraft$pendingContainerPositions.clear();
        this.quickcraft$missingContainerStacks.clear();
        this.quickcraft$requestedContainerDataChunks.clear();
        this.quickcraft$containerDataChunks.clear();
        this.host.quickcraft$verificationSelectedCategories().removeIf(QuickLitematicaContainerVerifier::isContainerMismatchType);
        this.host.quickcraft$verificationSelectedEntries().keySet().removeIf(QuickLitematicaContainerVerifier::isContainerMismatchType);
        QuickLitematicaContainerVerifier.setSuppressInventorySlotHighlights(false);
    }

    private void quickcraft$requestContainerInventoryData(Level world, BlockPos pos) {
        QuickLitematicaContainerVerifier.requestInventoryData(world, pos);

        if (world == null || fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
            return;
        }

        ChunkPos chunkPos = ChunkPos.containing(pos);

        if (!this.quickcraft$requestedContainerDataChunks.contains(chunkPos)
                && this.quickcraft$requestContainerInventoryDataChunk(world, chunkPos)) {
            this.quickcraft$requestedContainerDataChunks.add(chunkPos);
        }
    }

    private void quickcraft$requestContainerInventoryDataChunks(Level world, Collection<ChunkPos> chunkPositions) {
        if (world == null
                || chunkPositions == null
                || chunkPositions.isEmpty()
                || fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
            return;
        }

        for (ChunkPos chunkPos : chunkPositions) {
            if (!this.quickcraft$requestedContainerDataChunks.contains(chunkPos)
                    && this.quickcraft$requestContainerInventoryDataChunk(world, chunkPos)) {
                this.quickcraft$requestedContainerDataChunks.add(chunkPos);
            }
        }
    }

    private boolean quickcraft$canProcessContainerDataChunk(ChunkPos chunkPos) {
        if (!QuickLitematicaContainerVerifier.isEnabled()
                || this.host.quickcraft$verificationWorldClient() == null
                || this.host.quickcraft$verificationSchematicPlacement() == null
                || fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
            return true;
        }

        if (!this.quickcraft$containerDataChunks.contains(chunkPos)) {
            return true;
        }

        EntityDataManager storage = EntityDataManager.getInstance();

        if (!storage.hasServuxServer() && !storage.getIfReceivedBackupPackets()) {
            return true;
        }
        if (!Objects.equals(storage.getBestWorld(), this.host.quickcraft$verificationWorldClient())) {
            return true;
        }
        if (storage.hasCompletedChunk(chunkPos)) {
            return true;
        }
        if (storage.hasPendingChunk(chunkPos)) {
            return false;
        }

        if (this.quickcraft$requestContainerInventoryDataChunk(this.host.quickcraft$verificationWorldClient(), chunkPos)) {
            return false;
        }

        return true;
    }

    private void quickcraft$rebuildContainerDataChunks(WorldSchematic worldSchematic, SchematicPlacement placement) {
        this.quickcraft$containerDataChunks.clear();
        Set<ChunkPos> touchedChunks = placement.getTouchedChunks();

        for (ChunkPos chunkPos : touchedChunks) {
            ChunkAccess chunkSchematic = worldSchematic.getChunk(chunkPos.x(), chunkPos.z());
            var withinPlacement = QuickLitematicaVerifierAccess.placementChunkMatcher(placement, chunkPos.x(), chunkPos.z());
            boolean hasContainer = false;

            for (BlockPos pos : chunkSchematic.getBlockEntitiesPos()) {
                if (!withinPlacement.test(pos)) {
                    continue;
                }

                BlockEntity expectedBlockEntity = chunkSchematic.getBlockEntity(pos);
                ExpectedContainer expectedContainer = QuickLitematicaContainerVerifier.getExpectedContainerPartAt(
                        placement,
                        pos
                );
                if (expectedContainer != null || expectedBlockEntity instanceof Container) {
                    hasContainer = true;
                } else if (quickcraft$hasSerializedContainerData(worldSchematic, expectedBlockEntity)) {
                    this.quickcraft$unsupportedExpectedContainers++;
                    BlockState state = chunkSchematic.getBlockState(pos);
                    this.quickcraft$recordDiagnostic(
                            "unsupported_expected=" + expectedBlockEntity.getType(),
                            pos,
                            state,
                            state
                    );
                }
            }

            if (hasContainer) {
                this.quickcraft$containerDataChunks.add(chunkPos);
            }
        }
    }

    private static boolean quickcraft$hasSerializedContainerData(WorldSchematic world, BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }

        try {
            CompoundTag nbt = blockEntity.saveWithFullMetadata(world.registryAccess());
            return nbt.contains("Items") || nbt.contains("RecordItem") || nbt.contains("item");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean quickcraft$requestContainerInventoryDataChunk(Level world, ChunkPos chunkPos) {
        if (world == null || chunkPos == null) {
            return false;
        }

        int minY = world.getMinY();
        int maxY = world.getMinY() + world.getHeight();

        int[] yRange = QuickLitematicaVerifierAccess.getPlacementChunkYRange(
                this.host.quickcraft$verificationSchematicPlacement(),
                chunkPos.x(),
                chunkPos.z(),
                minY,
                maxY
        );

        return QuickLitematicaContainerVerifier.requestInventoryDataChunk(world, chunkPos, yRange[0], yRange[1]);
    }

    private boolean quickcraft$shouldWaitForMissingInventory(Level world, BlockPos pos, @Nullable BlockEntity foundBlockEntity) {
        if (world == null) {
            return true;
        }

        BlockState state = world.getBlockState(pos);
        return foundBlockEntity == null && state.hasBlockEntity();
    }

    private void quickcraft$markContainerChecked(BlockPos pos) {
        this.quickcraft$expectedContainerPositions.add(pos.immutable());
        this.quickcraft$checkedContainerPositions.add(pos.immutable());
        this.quickcraft$pendingContainerPositions.remove(pos);
    }

    private void quickcraft$markContainerPending(BlockPos pos) {
        BlockPos immutablePos = pos.immutable();
        this.quickcraft$expectedContainerPositions.add(immutablePos);
        this.quickcraft$checkedContainerPositions.remove(pos);
        this.quickcraft$missingContainerStacks.remove(pos);
        this.quickcraft$pendingContainerPositions.add(immutablePos);
    }

    private void quickcraft$rememberMissingContainer(BlockPos pos, Container expected) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < expected.getContainerSize(); slot++) {
            ItemStack stack = expected.getItem(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        this.quickcraft$missingContainerStacks.put(pos.immutable(), List.copyOf(stacks));
    }

    private List<BlockMismatch> quickcraft$createBlockMismatchesFor(MismatchType type) {
        List<BlockMismatch> list = new ArrayList<>();

        for (ContainerMismatch mismatch : this.quickcraft$containerMismatches.getOrDefault(type, List.of())) {
            list.add(this.quickcraft$createBlockMismatch(mismatch));
        }

        return list;
    }

    private BlockMismatch quickcraft$createBlockMismatch(ContainerMismatch mismatch) {
        BlockMismatch blockMismatch = new BlockMismatch(
                mismatch.type(),
                mismatch.expectedState(),
                mismatch.foundState(),
                1
        );
        ((BlockMismatchExtension) (Object) blockMismatch).quickcraft$setContainerMismatch(mismatch);
        return blockMismatch;
    }

    private List<BlockPos> quickcraft$getSelectedContainerPositionsForType(MismatchType type) {
        List<BlockPos> positions = new ArrayList<>();

        if (this.host.quickcraft$verificationSelectedCategories().contains(type)) {
            this.quickcraft$containerMismatches.getOrDefault(type, List.of()).stream()
                    .map(ContainerMismatch::pos)
                    .distinct()
                    .forEach(positions::add);
            return positions;
        }

        Collection<BlockMismatch> selected = this.host.quickcraft$verificationSelectedEntries().get(type);

        for (BlockMismatch mismatch : selected) {
            ContainerMismatchKey key = ((BlockMismatchExtension) (Object) mismatch).quickcraft$getContainerMismatchKey();
            ContainerMismatch containerMismatch = key != null ? this.quickcraft$containerMismatchesByKey.get(key) : null;

            if (containerMismatch != null && !positions.contains(containerMismatch.pos())) {
                positions.add(containerMismatch.pos());
            }
        }

        return positions;
    }

    private void quickcraft$addHudPositions(List<MismatchRenderPos> positions, int maxLines) {
        int count = Math.min(positions.size(), maxLines);
        String rst = GuiBase.TXT_RST;

        for (int i = 0; i < count; i++) {
            MismatchRenderPos entry = positions.get(i);
            BlockPos pos = QuickLitematicaVerifierAccess.getRenderPos(entry);
            String pre = quickcraft$getHudColorCode(QuickLitematicaVerifierAccess.getRenderType(entry));
            this.host.quickcraft$verificationInfoHudLines().add(String.format("%sx: %5d, y: %3d, z: %5d%s", pre, pos.getX(), pos.getY(), pos.getZ(), rst));
        }
    }

    private String quickcraft$getHudColorCode(MismatchType type) {
        if (QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            return QuickLitematicaVerifierPalette.formattingCode(type);
        }

        return type.getColorCode();
    }

    private boolean quickcraft$isContainerPosSelected(BlockPos pos) {
        for (BlockMismatch mismatch : this.quickcraft$selectedContainerMismatches) {
            ContainerMismatch containerMismatch =
                    ((BlockMismatchExtension) (Object) mismatch).quickcraft$getContainerMismatch();

            if (containerMismatch != null && containerMismatch.pos().equals(pos)) {
                return true;
            }
        }

        return false;
    }

    private void quickcraft$restoreContainerSelection(BlockPos pos, boolean wasSelected) {
        this.quickcraft$selectedContainerMismatches.removeIf(mismatch -> {
            ContainerMismatch containerMismatch =
                    ((BlockMismatchExtension) (Object) mismatch).quickcraft$getContainerMismatch();
            return containerMismatch != null && containerMismatch.pos().equals(pos);
        });
        this.host.quickcraft$verificationSelectedEntries().values().removeIf(mismatch -> {
            ContainerMismatch containerMismatch =
                    ((BlockMismatchExtension) (Object) mismatch).quickcraft$getContainerMismatch();
            return containerMismatch != null && containerMismatch.pos().equals(pos);
        });

        if (!wasSelected) {
            return;
        }

        for (ContainerMismatch mismatch : this.quickcraft$containerMismatchesByKey.values()) {
            if (mismatch.pos().equals(pos)) {
                BlockMismatch blockMismatch = this.quickcraft$createBlockMismatch(mismatch);
                this.quickcraft$selectedContainerMismatches.add(blockMismatch);
                this.host.quickcraft$verificationSelectedEntries().put(QuickLitematicaVerifierAccess.getMismatchType(blockMismatch), blockMismatch);
                return;
            }
        }
    }

    private String quickcraft$getContainerMismatchSignature(ContainerMismatch mismatch) {
        StringBuilder builder = new StringBuilder();

        builder.append(mismatch.type().ordinal())
                .append('|')
                .append(mismatch.expectedState())
                .append('|')
                .append(mismatch.foundState())
                .append('|')
                .append(mismatch.expectedDisabledSlots().stream().sorted().toList())
                .append('|')
                .append(mismatch.foundDisabledSlots().stream().sorted().toList())
                .append('|');

        for (QuickLitematicaContainerVerifier.SlotMismatch slotMismatch : mismatch.slotMismatches()) {
            builder.append(slotMismatch.slot())
                    .append(':')
                    .append(slotMismatch.status().name())
                    .append(':')
                    .append(slotMismatch.expectedStack().getCount())
                    .append(':')
                    .append(slotMismatch.foundStack().getCount())
                    .append(':')
                    .append(QuickLitematicaContainerVerifier.getItemStackSignature(slotMismatch.expectedStack()))
                    .append(':')
                    .append(QuickLitematicaContainerVerifier.getItemStackSignature(slotMismatch.foundStack()))
                    .append(';');
        }

        return builder.toString();
    }
}
