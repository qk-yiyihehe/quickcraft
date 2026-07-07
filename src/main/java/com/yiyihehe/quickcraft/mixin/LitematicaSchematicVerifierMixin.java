package com.yiyihehe.quickcraft.mixin;

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
import fi.dy.masa.litematica.data.EntitiesDataStorage;
import fi.dy.masa.litematica.scheduler.tasks.TaskBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把容器内容校验并入 Litematica 原版验证流程。
 * 负责收集容器错填、维护统计与选中状态，并把结果包装成原版可显示的数据结构。
 */
@Mixin(value = SchematicVerifier.class, remap = false)
public abstract class LitematicaSchematicVerifierMixin extends TaskBase implements VerifierExtension {
    @Unique
    private static final Logger QUICKCRAFT_LOGGER = LoggerFactory.getLogger("QuickCraft-ContainerVerifier");

    @Shadow
    @Final
    private static BlockPos.Mutable MUTABLE_POS;

    @Shadow
    private ClientWorld worldClient;

    @Shadow
    private SchematicPlacement schematicPlacement;

    @Shadow
    @Final
    private List<MismatchRenderPos> mismatchPositionsForRender;

    @Shadow
    @Final
    private List<BlockPos> mismatchBlockPositionsForRender;

    @Shadow
    @Final
    private Set<MismatchType> selectedCategories;

    @Shadow
    @Final
    private HashMultimap<MismatchType, BlockMismatch> selectedEntries;

    @Shadow
    private void updateMismatchOverlays() {
    }

    @Shadow
    public abstract boolean isMismatchEntrySelected(BlockMismatch mismatch);

    @Unique
    private final Map<MismatchType, List<ContainerMismatch>> quickcraft$containerMismatches = new HashMap<>();

    @Unique
    private final Map<MismatchType, List<BlockPos>> quickcraft$containerPositionsClosest = new HashMap<>();

    @Unique
    private final Map<ContainerMismatchKey, ContainerMismatch> quickcraft$containerMismatchesByKey = new HashMap<>();

    @Unique
    private final List<BlockMismatch> quickcraft$selectedContainerMismatches = new ArrayList<>();

    @Unique
    private final Set<BlockPos> quickcraft$expectedContainerPositions = new HashSet<>();

    @Unique
    private final Set<BlockPos> quickcraft$checkedContainerPositions = new HashSet<>();

    @Unique
    private final Set<BlockPos> quickcraft$pendingContainerPositions = new HashSet<>();

    @Unique
    private final Map<BlockPos, String> quickcraft$pendingContainerReasons = new HashMap<>();

    @Unique
    private final Set<ChunkPos> quickcraft$requestedContainerDataChunks = new HashSet<>();

    @Unique
    private long quickcraft$lastContainerDebugLogTick = Long.MIN_VALUE;

    @Unique
    private String quickcraft$lastPendingContainerSampleLog = "";

    @Unique
    private int quickcraft$debugNoDataChannelChunks;

    @Unique
    private int quickcraft$debugWorldMismatchChunks;

    @Unique
    private int quickcraft$debugCompletedChunks;

    @Unique
    private int quickcraft$debugAlreadyPendingChunks;

    @Unique
    private int quickcraft$debugIssuedChunkRequests;

    @Unique
    private int quickcraft$debugFailedChunkRequests;

    @Unique
    private int quickcraft$debugFoundBlockEntityMissing;

    @Unique
    private int quickcraft$debugActualNoCacheNbt;

    @Unique
    private int quickcraft$debugActualCacheWithoutItems;

    @Unique
    private int quickcraft$debugActualCacheParseFailed;

    @Unique
    private int quickcraft$debugActualSizeMismatch;

    @Unique
    private int quickcraft$debugActualOtherFailure;

    @Unique
    private int quickcraft$refreshCursor;

    @Override
    public List<BlockMismatch> quickcraft$getSelectedInventoryMismatches() {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return List.of();
        }

        return Collections.unmodifiableList(this.quickcraft$selectedContainerMismatches);
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
    public int quickcraft$getPendingContainerCount() {
        return QuickLitematicaContainerVerifier.isEnabled() ? this.quickcraft$pendingContainerPositions.size() : 0;
    }

    @Override
    public List<ContainerMismatch> quickcraft$refreshContainerMismatchAt(BlockPos pos, Inventory foundInventory, Set<Integer> foundDisabledSlots) {
        if (!QuickLitematicaContainerVerifier.isEnabled() || foundInventory == null) {
            return null;
        }

        World bestWorld = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(MinecraftClient.getInstance());
        List<ContainerMismatch> mismatches = bestWorld != null
                ? this.quickcraft$collectContainerMismatchesFromInventory(bestWorld, pos, foundInventory, foundDisabledSlots)
                : null;

        if (mismatches != null) {
            this.quickcraft$markContainerChecked(pos);
        } else {
            this.quickcraft$markContainerPending(pos);
        }

        if (mismatches != null && this.quickcraft$replaceContainerMismatchesAt(pos, mismatches)) {
            this.updateMismatchOverlays();
        }

        return mismatches;
    }

    @Redirect(
            method = "verifyChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/world/ClientWorld;getChunk(II)Lnet/minecraft/world/chunk/WorldChunk;",
                    remap = true
            )
    )
    private WorldChunk quickcraft$useBestWorldForSinglePlayer(ClientWorld clientWorld, int chunkX, int chunkZ) {
        World bestWorld = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(MinecraftClient.getInstance());
        return bestWorld != null ? bestWorld.getChunk(chunkX, chunkZ) : clientWorld.getChunk(chunkX, chunkZ);
    }

    @Redirect(
            method = "verifyChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/world/ChunkManagerSchematic;isChunkLoaded(II)Z"
            )
    )
    private boolean quickcraft$waitForContainerData(ChunkManagerSchematic chunkManager, int chunkX, int chunkZ) {
        return chunkManager.isChunkLoaded(chunkX, chunkZ)
                && this.quickcraft$canProcessContainerDataChunk(new ChunkPos(chunkX, chunkZ));
    }

    @Inject(
            method = "verifyChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;checkBlockStates(IIILnet/minecraft/block/BlockState;Lnet/minecraft/block/BlockState;)V",
                    remap = true,
                    shift = At.Shift.AFTER
            )
    )
    private void quickcraft$checkContainerInventory(Chunk chunkClient, Chunk chunkSchematic, fi.dy.masa.malilib.util.IntBoundingBox box, CallbackInfoReturnable<Boolean> cir) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }

        BlockPos pos = MUTABLE_POS.toImmutable();
        World foundWorld = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(MinecraftClient.getInstance());
        BlockEntity expectedBlockEntity = chunkSchematic.getBlockEntity(pos);

        if (foundWorld == null || !(expectedBlockEntity instanceof Inventory)) {
            return;
        }

        this.quickcraft$expectedContainerPositions.add(pos);
        List<ContainerMismatch> mismatches = this.quickcraft$collectContainerMismatchesFromChunks(
                foundWorld,
                chunkClient,
                chunkSchematic,
                pos
        );

        if (mismatches != null) {
            this.quickcraft$markContainerChecked(pos);
            this.quickcraft$removeContainerMismatchesAt(pos);
            mismatches.forEach(this::quickcraft$addContainerMismatch);
        } else {
            this.quickcraft$markContainerPending(pos);
            this.quickcraft$requestContainerInventoryData(this.worldClient, pos);
        }
    }

    @Inject(method = "getMismatchOverviewFor", at = @At("HEAD"), cancellable = true)
    private void quickcraft$getInventoryMismatchOverview(MismatchType type, CallbackInfoReturnable<List<BlockMismatch>> cir) {
        if (QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            if (!QuickLitematicaContainerVerifier.isEnabled()) {
                cir.setReturnValue(new ArrayList<>());
                return;
            }

            List<BlockMismatch> list = this.quickcraft$createBlockMismatchesFor(type);
            Collections.sort(list);
            cir.setReturnValue(list);
        }
    }

    @Inject(method = "getMismatchOverviewCombined", at = @At("RETURN"))
    private void quickcraft$addInventoryMismatchOverview(CallbackInfoReturnable<List<BlockMismatch>> cir) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }

        List<BlockMismatch> list = cir.getReturnValue();

        Collections.sort(list);
    }

    @Inject(method = "getMapForMismatchType", at = @At("HEAD"), cancellable = true)
    private void quickcraft$getInventoryMismatchMap(MismatchType type, CallbackInfoReturnable<ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos>> cir) {
        if (QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> map = ArrayListMultimap.create();

            if (!QuickLitematicaContainerVerifier.isEnabled()) {
                cir.setReturnValue(map);
                return;
            }

            for (ContainerMismatch mismatch : this.quickcraft$containerMismatches.getOrDefault(type, List.of())) {
                map.put(Pair.of(mismatch.expectedState(), mismatch.foundState()), mismatch.pos());
            }

            cir.setReturnValue(map);
        }
    }

    @Inject(method = "updateClosestPositions", at = @At("TAIL"))
    private void quickcraft$updateClosestInventoryPositions(BlockPos centerPos, int maxEntries, CallbackInfo ci) {
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

    @Inject(method = "combineClosestPositions", at = @At("TAIL"))
    private void quickcraft$combineClosestInventoryPositions(BlockPos centerPos, int maxEntries, CallbackInfo ci) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }

        for (MismatchType type : QuickLitematicaContainerVerifier.getContainerMismatchTypes()) {
            List<BlockPos> positions = this.quickcraft$containerPositionsClosest.getOrDefault(type, List.of());

            for (BlockPos pos : positions) {
                if (this.mismatchPositionsForRender.size() >= maxEntries) {
                    return;
                }

                this.mismatchPositionsForRender.add(new MismatchRenderPos(type, pos));
            }
        }
    }

    @Inject(method = "getClosestMismatchedPositionsFor", at = @At("HEAD"), cancellable = true)
    private void quickcraft$getClosestInventoryPositions(MismatchType type, CallbackInfoReturnable<List<BlockPos>> cir) {
        if (QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            cir.setReturnValue(QuickLitematicaContainerVerifier.isEnabled()
                    ? this.quickcraft$containerPositionsClosest.getOrDefault(type, List.of())
                    : List.of());
        }
    }

    @Inject(method = "toggleMismatchEntrySelected", at = @At("TAIL"))
    private void quickcraft$trackSelectedInventoryMismatch(BlockMismatch mismatch, CallbackInfo ci) {
        if (!QuickLitematicaContainerVerifier.isEnabled()
                || !QuickLitematicaContainerVerifier.isContainerMismatchType(mismatch.mismatchType)) {
            return;
        }

        if (this.isMismatchEntrySelected(mismatch)) {
            if (!this.quickcraft$selectedContainerMismatches.contains(mismatch)) {
                this.quickcraft$selectedContainerMismatches.add(mismatch);
            }
        } else {
            this.quickcraft$selectedContainerMismatches.remove(mismatch);
        }
    }

    @Inject(method = "removeSelectedEntriesOfType", at = @At("HEAD"))
    private void quickcraft$removeSelectedInventoryMismatches(MismatchType type, CallbackInfo ci) {
        if (QuickLitematicaContainerVerifier.isEnabled()
                && QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            this.quickcraft$selectedContainerMismatches.removeIf(mismatch -> mismatch.mismatchType == type);
        }
    }

    @Inject(method = "ignoreStateMismatch(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$BlockMismatch;Z)V", at = @At("HEAD"), cancellable = true)
    private void quickcraft$forgetIgnoredInventoryMismatch(BlockMismatch mismatch, boolean updateOverlay, CallbackInfo ci) {
        if (!QuickLitematicaContainerVerifier.isEnabled()
                || !QuickLitematicaContainerVerifier.isContainerMismatchType(mismatch.mismatchType)) {
            return;
        }

        ContainerMismatchKey key = ((BlockMismatchExtension) mismatch).quickcraft$getContainerMismatchKey();

        if (key != null) {
            ContainerMismatch removed = this.quickcraft$containerMismatchesByKey.remove(key);

            if (removed != null) {
                this.quickcraft$containerMismatches.getOrDefault(removed.type(), List.of()).remove(removed);
            }
        }

        this.quickcraft$selectedContainerMismatches.remove(mismatch);

        if (updateOverlay) {
            this.updateMismatchOverlays();
        }

        ci.cancel();
    }

    @Inject(method = "clearData", at = @At("HEAD"))
    private void quickcraft$clearInventoryData(CallbackInfo ci) {
        this.quickcraft$clearContainerData();
    }

    @Inject(method = "startVerification", at = @At("TAIL"))
    private void quickcraft$requestContainerDataOnStart(
            ClientWorld worldClient,
            WorldSchematic worldSchematic,
            SchematicPlacement schematicPlacement,
            ICompletionListener completionListener,
            CallbackInfo ci
    ) {
        if (!QuickLitematicaContainerVerifier.isEnabled() || schematicPlacement == null) {
            return;
        }

        // 开始验证时先按本次原理图触碰的 chunk 拉一轮容器 NBT。
        this.quickcraft$requestContainerInventoryDataChunks(worldClient, schematicPlacement.getTouchedChunks());
        this.quickcraft$logContainerDebug("start", 0L, worldClient);
    }

    @Inject(method = "execute", at = @At("TAIL"))
    private void quickcraft$refreshContainerMismatches(CallbackInfoReturnable<Boolean> cir) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            if (!this.quickcraft$containerMismatchesByKey.isEmpty()) {
                this.quickcraft$clearContainerData();
                this.updateMismatchOverlays();
            }
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world == null || client.world.getTime() % 10 != 0) {
            return;
        }

        World bestWorld = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        this.quickcraft$logContainerDebug("tick", client.world.getTime(), bestWorld);

        if (bestWorld == null
                || (this.quickcraft$containerMismatchesByKey.isEmpty() && this.quickcraft$pendingContainerPositions.isEmpty())) {
            return;
        }

        List<BlockPos> positions = new ArrayList<>();

        // 多人服容器 NBT 可能稍后才由 Servux/OP 查询返回，pending 位置也要持续复查。
        for (BlockPos pos : this.quickcraft$pendingContainerPositions) {
            if (!positions.contains(pos)) {
                positions.add(pos);
            }
        }

        for (ContainerMismatch mismatch : this.quickcraft$containerMismatchesByKey.values()) {
            if (!positions.contains(mismatch.pos())) {
                positions.add(mismatch.pos());
            }
        }

        if (positions.isEmpty()) {
            return;
        }

        boolean changed = false;
        int checks = Math.min(this.quickcraft$pendingContainerPositions.isEmpty() ? 8 : 128, positions.size());

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
            this.updateMismatchOverlays();
        }
    }

    @Inject(method = "updateMismatchPositionStringList", at = @At("TAIL"))
    private void quickcraft$splitInventoryHudLines(@Nullable MismatchType mismatchType, List<MismatchRenderPos> positionList, CallbackInfo ci) {
        if (!QuickLitematicaContainerVerifier.isEnabled()
                || positionList.stream().noneMatch(pos -> QuickLitematicaContainerVerifier.isContainerMismatchType(pos.type))) {
            return;
        }

        this.infoHudLines.clear();
        String rst = GuiBase.TXT_RST;
        List<MismatchRenderPos> vanilla = positionList.stream()
                .filter(pos -> !QuickLitematicaContainerVerifier.isContainerMismatchType(pos.type))
                .toList();
        List<MismatchRenderPos> containers = positionList.stream()
                .filter(pos -> QuickLitematicaContainerVerifier.isContainerMismatchType(pos.type))
                .toList();
        int maxLines = Configs.InfoOverlays.INFO_HUD_MAX_LINES.getIntegerValue();

        if (!vanilla.isEmpty()) {
            String title = mismatchType != null && !QuickLitematicaContainerVerifier.isContainerMismatchType(mismatchType)
                    ? mismatchType.getFormattingCode() + mismatchType.getDisplayname()
                    : GuiBase.TXT_BOLD + StringUtils.translate("litematica.gui.title.schematic_verifier_errors");
            this.infoHudLines.add(title + rst);
            this.quickcraft$addHudPositions(vanilla, maxLines);
        }

        if (!containers.isEmpty()) {
            this.infoHudLines.add(QuickLitematicaVerifierPalette.formatSectionTitle(
                    StringUtils.translate("quickcraft.litematica.verifier.title.container_errors")
            ));
            this.quickcraft$addHudPositions(containers, maxLines);
        }
    }

    @Unique
    private List<ContainerMismatch> quickcraft$collectContainerMismatchesFromChunks(
            World foundWorld,
            Chunk chunkClient,
            Chunk chunkSchematic,
            BlockPos pos
    ) {
        BlockEntity expectedBlockEntity = chunkSchematic.getBlockEntity(pos);
        BlockEntity foundBlockEntity = chunkClient.getBlockEntity(pos);

        if (!(expectedBlockEntity instanceof Inventory expectedInventory)) {
            return List.of();
        }

        if (!(foundBlockEntity instanceof Inventory foundInventory)) {
            if (!fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
                if (this.quickcraft$shouldWaitForMissingInventory(foundWorld, pos, foundBlockEntity)) {
                    this.quickcraft$debugFoundBlockEntityMissing++;
                    this.quickcraft$rememberPendingReason(pos, this.quickcraft$getMissingBlockEntityReason(foundWorld, pos, foundBlockEntity));
                    this.quickcraft$requestContainerInventoryData(foundWorld, pos);
                    return null;
                }
            }

            return List.of();
        }

        Inventory expected = QuickLitematicaContainerVerifier.getExpectedInventory(expectedBlockEntity, expectedInventory);
        Inventory found = QuickLitematicaContainerVerifier.getActualInventory(
                foundWorld,
                pos,
                foundInventory,
                expected
        );
        if (found == null || found.size() != expected.size()) {
            this.quickcraft$countActualInventoryFailure(foundWorld, pos, found, expected.size());
            return null;
        }

        List<ContainerMismatch> mismatches = QuickLitematicaContainerVerifier.findMismatches(
                pos,
                chunkSchematic.getBlockState(pos),
                chunkClient.getBlockState(pos),
                expectedBlockEntity,
                foundBlockEntity,
                QuickLitematicaContainerVerifier.getDisabledSlots(expectedBlockEntity),
                QuickLitematicaContainerVerifier.getDisabledSlots(foundBlockEntity),
                expected,
                found
        );
        return mismatches;
    }

    @Unique
    private List<ContainerMismatch> quickcraft$collectContainerMismatchesFromWorld(World foundWorld, BlockPos pos) {
        if (this.worldClient == null) {
            return null;
        }

        ExpectedContainer expected = QuickLitematicaContainerVerifier.getExpectedContainerAt(foundWorld, pos);
        BlockEntity foundBlockEntity = foundWorld.getBlockEntity(pos);

        if (expected == null) {
            return List.of();
        }

        if (!(foundBlockEntity instanceof Inventory foundInventory)) {
            if (!fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
                if (this.quickcraft$shouldWaitForMissingInventory(foundWorld, pos, foundBlockEntity)) {
                    this.quickcraft$debugFoundBlockEntityMissing++;
                    this.quickcraft$rememberPendingReason(pos, this.quickcraft$getMissingBlockEntityReason(foundWorld, pos, foundBlockEntity));
                    this.quickcraft$requestContainerInventoryData(foundWorld, pos);
                    return null;
                }
            }

            return List.of();
        }

        Inventory found = QuickLitematicaContainerVerifier.getActualInventory(
                foundWorld,
                pos,
                foundInventory,
                expected.inventory()
        );

        if (found == null || found.size() != expected.inventory().size()) {
            this.quickcraft$countActualInventoryFailure(foundWorld, pos, found, expected.inventory().size());
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

    @Unique
    private List<ContainerMismatch> quickcraft$collectContainerMismatchesFromInventory(
            World foundWorld,
            BlockPos pos,
            Inventory found,
            Set<Integer> foundDisabledSlots
    ) {
        ExpectedContainer expected = QuickLitematicaContainerVerifier.getExpectedContainerAt(foundWorld, pos);
        BlockEntity foundBlockEntity = foundWorld.getBlockEntity(pos);

        if (expected == null) {
            return List.of();
        }

        if (found.size() != expected.inventory().size()) {
            this.quickcraft$rememberPendingReason(pos, "screenSizeMismatch found=" + found.size()
                    + " expected=" + expected.inventory().size());
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

    @Unique
    private void quickcraft$addContainerMismatch(ContainerMismatch mismatch) {
        this.quickcraft$containerMismatches.computeIfAbsent(mismatch.type(), type -> new ArrayList<>()).add(mismatch);
        this.quickcraft$containerMismatchesByKey.put(mismatch.key(), mismatch);
        QuickLitematicaContainerVerifier.setSuppressInventorySlotHighlights(false);
    }

    @Unique
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

    @Unique
    private void quickcraft$removeContainerMismatchesAt(BlockPos pos) {
        this.quickcraft$containerMismatchesByKey.entrySet().removeIf(entry -> entry.getValue().pos().equals(pos));

        for (List<ContainerMismatch> mismatches : this.quickcraft$containerMismatches.values()) {
            mismatches.removeIf(mismatch -> mismatch.pos().equals(pos));
        }

        QuickLitematicaContainerVerifier.setSuppressInventorySlotHighlights(false);
    }

    @Unique
    private void quickcraft$clearContainerData() {
        this.quickcraft$containerMismatches.clear();
        this.quickcraft$containerPositionsClosest.clear();
        this.quickcraft$containerMismatchesByKey.clear();
        this.quickcraft$selectedContainerMismatches.clear();
        this.quickcraft$expectedContainerPositions.clear();
        this.quickcraft$checkedContainerPositions.clear();
        this.quickcraft$pendingContainerPositions.clear();
        this.quickcraft$pendingContainerReasons.clear();
        this.quickcraft$requestedContainerDataChunks.clear();
        this.quickcraft$lastContainerDebugLogTick = Long.MIN_VALUE;
        this.quickcraft$lastPendingContainerSampleLog = "";
        this.quickcraft$debugNoDataChannelChunks = 0;
        this.quickcraft$debugWorldMismatchChunks = 0;
        this.quickcraft$debugCompletedChunks = 0;
        this.quickcraft$debugAlreadyPendingChunks = 0;
        this.quickcraft$debugIssuedChunkRequests = 0;
        this.quickcraft$debugFailedChunkRequests = 0;
        this.quickcraft$debugFoundBlockEntityMissing = 0;
        this.quickcraft$debugActualNoCacheNbt = 0;
        this.quickcraft$debugActualCacheWithoutItems = 0;
        this.quickcraft$debugActualCacheParseFailed = 0;
        this.quickcraft$debugActualSizeMismatch = 0;
        this.quickcraft$debugActualOtherFailure = 0;
        this.selectedCategories.removeIf(QuickLitematicaContainerVerifier::isContainerMismatchType);
        this.selectedEntries.keySet().removeIf(QuickLitematicaContainerVerifier::isContainerMismatchType);
        QuickLitematicaContainerVerifier.setSuppressInventorySlotHighlights(false);
    }

    @Unique
    private void quickcraft$requestContainerInventoryData(World world, BlockPos pos) {
        QuickLitematicaContainerVerifier.requestInventoryData(world, pos);

        if (world == null || fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
            return;
        }

        ChunkPos chunkPos = new ChunkPos(pos);

        if (!this.quickcraft$requestedContainerDataChunks.contains(chunkPos)
                && this.quickcraft$requestContainerInventoryDataChunk(world, chunkPos)) {
            this.quickcraft$requestedContainerDataChunks.add(chunkPos);
        }
    }

    @Unique
    private void quickcraft$requestContainerInventoryDataChunks(World world, Collection<ChunkPos> chunkPositions) {
        if (world == null
                || chunkPositions == null
                || chunkPositions.isEmpty()
                || fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
            return;
        }

        int issued = 0;

        for (ChunkPos chunkPos : chunkPositions) {
            if (!this.quickcraft$requestedContainerDataChunks.contains(chunkPos)
                    && this.quickcraft$requestContainerInventoryDataChunk(world, chunkPos)) {
                this.quickcraft$requestedContainerDataChunks.add(chunkPos);
                issued++;
            }
        }

        QUICKCRAFT_LOGGER.info(
                "container verifier bulk request: chunks={} issued={} requestedTotal={}",
                chunkPositions.size(),
                issued,
                this.quickcraft$requestedContainerDataChunks.size()
        );
    }

    @Unique
    private boolean quickcraft$canProcessContainerDataChunk(ChunkPos chunkPos) {
        if (!QuickLitematicaContainerVerifier.isEnabled()
                || this.worldClient == null
                || this.schematicPlacement == null
                || fi.dy.masa.litematica.data.DataManager.getInstance().hasIntegratedServer()) {
            return true;
        }

        EntitiesDataStorage storage = EntitiesDataStorage.getInstance();

        if (!storage.hasServuxServer() && !storage.getIfReceivedBackupPackets()) {
            this.quickcraft$debugNoDataChannelChunks++;
            return true;
        }
        if (!Objects.equals(storage.getWorld(), this.worldClient)) {
            this.quickcraft$debugWorldMismatchChunks++;
            return true;
        }
        if (storage.hasCompletedChunk(chunkPos)) {
            this.quickcraft$debugCompletedChunks++;
            return true;
        }
        if (storage.hasPendingChunk(chunkPos)) {
            this.quickcraft$debugAlreadyPendingChunks++;
            return false;
        }

        if (this.quickcraft$requestContainerInventoryDataChunk(this.worldClient, chunkPos)) {
            this.quickcraft$debugIssuedChunkRequests++;
            return false;
        }

        this.quickcraft$debugFailedChunkRequests++;
        return true;
    }

    @Unique
    private boolean quickcraft$requestContainerInventoryDataChunk(World world, ChunkPos chunkPos) {
        if (world == null || chunkPos == null) {
            return false;
        }

        int minY = world.getBottomY();
        int maxY = world.getTopY();

        if (this.schematicPlacement != null) {
            Map<String, IntBoundingBox> boxes = this.schematicPlacement.getBoxesWithinChunk(chunkPos.x, chunkPos.z);

            if (!boxes.isEmpty()) {
                minY = Integer.MAX_VALUE;
                maxY = Integer.MIN_VALUE;

                for (IntBoundingBox box : boxes.values()) {
                    minY = Math.min(minY, box.minY);
                    maxY = Math.max(maxY, box.maxY);
                }
            }
        }

        return QuickLitematicaContainerVerifier.requestInventoryDataChunk(world, chunkPos, minY, maxY);
    }

    @Unique
    private String quickcraft$getMissingBlockEntityReason(World world, BlockPos pos, @Nullable BlockEntity foundBlockEntity) {
        String block = world != null ? String.valueOf(world.getBlockState(pos).getBlock()) : "unknown";
        String blockEntity = foundBlockEntity != null ? foundBlockEntity.getClass().getSimpleName() : "null";

        return "foundBlockEntityMissing block=" + block + " blockEntity=" + blockEntity;
    }

    @Unique
    private boolean quickcraft$shouldWaitForMissingInventory(World world, BlockPos pos, @Nullable BlockEntity foundBlockEntity) {
        if (world == null) {
            return true;
        }

        BlockState state = world.getBlockState(pos);
        return foundBlockEntity == null && state.hasBlockEntity();
    }

    @Unique
    private void quickcraft$rememberPendingReason(BlockPos pos, String reason) {
        this.quickcraft$pendingContainerReasons.put(pos.toImmutable(), reason);
    }

    @Unique
    private void quickcraft$countActualInventoryFailure(World world, BlockPos pos, Inventory found, int expectedSize) {
        if (found != null && found.size() != expectedSize) {
            this.quickcraft$debugActualSizeMismatch++;
            this.quickcraft$rememberPendingReason(pos, "actualSizeMismatch found=" + found.size()
                    + " expected=" + expectedSize);
            return;
        }

        QuickLitematicaContainerVerifier.ActualInventoryReadStatus status =
                QuickLitematicaContainerVerifier.getLastActualInventoryReadStatus();
        this.quickcraft$rememberPendingReason(pos, this.quickcraft$getActualReadFailureReason(world, pos, status, expectedSize));

        switch (status) {
            case NO_CACHE_NBT -> this.quickcraft$debugActualNoCacheNbt++;
            case CACHE_WITHOUT_ITEMS -> this.quickcraft$debugActualCacheWithoutItems++;
            case CACHE_PARSE_FAILED -> this.quickcraft$debugActualCacheParseFailed++;
            default -> this.quickcraft$debugActualOtherFailure++;
        }
    }

    @Unique
    private String quickcraft$getActualReadFailureReason(
            World world,
            BlockPos pos,
            QuickLitematicaContainerVerifier.ActualInventoryReadStatus status,
            int expectedSize
    ) {
        StringBuilder reason = new StringBuilder("actualRead=")
                .append(status)
                .append(" expected=")
                .append(expectedSize);

        if (world != null) {
            reason.append(" block=").append(world.getBlockState(pos).getBlock());
        }

        if (status == QuickLitematicaContainerVerifier.ActualInventoryReadStatus.CACHE_PARSE_FAILED
                || status == QuickLitematicaContainerVerifier.ActualInventoryReadStatus.CACHE_WITHOUT_ITEMS) {
            NbtCompound cachedNbt = EntitiesDataStorage.getInstance().getFromBlockEntityCacheNbt(pos);
            reason.append(" nbtKeys=").append(cachedNbt != null ? cachedNbt.getKeys() : "null");

            if (cachedNbt != null && cachedNbt.contains("Items")) {
                reason.append(" items=").append(cachedNbt.getList("Items", 10).size());
            }
        }

        return reason.toString();
    }

    @Unique
    private void quickcraft$logContainerDebug(String phase, long tick, World bestWorld) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }
        if (!"start".equals(phase)
                && tick >= 0
                && this.quickcraft$lastContainerDebugLogTick != Long.MIN_VALUE
                && tick - this.quickcraft$lastContainerDebugLogTick < 100) {
            return;
        }
        boolean hasChunkDebugCounters = this.quickcraft$debugNoDataChannelChunks != 0
                || this.quickcraft$debugWorldMismatchChunks != 0
                || this.quickcraft$debugCompletedChunks != 0
                || this.quickcraft$debugAlreadyPendingChunks != 0
                || this.quickcraft$debugIssuedChunkRequests != 0
                || this.quickcraft$debugFailedChunkRequests != 0
                || this.quickcraft$debugFoundBlockEntityMissing != 0
                || this.quickcraft$debugActualNoCacheNbt != 0
                || this.quickcraft$debugActualCacheWithoutItems != 0
                || this.quickcraft$debugActualCacheParseFailed != 0
                || this.quickcraft$debugActualSizeMismatch != 0
                || this.quickcraft$debugActualOtherFailure != 0;

        if (!"start".equals(phase)
                && this.quickcraft$expectedContainerPositions.isEmpty()
                && this.quickcraft$requestedContainerDataChunks.isEmpty()
                && !hasChunkDebugCounters) {
            return;
        }

        this.quickcraft$lastContainerDebugLogTick = tick;
        EntitiesDataStorage storage = EntitiesDataStorage.getInstance();
        boolean storageWorldSame = this.worldClient != null && Objects.equals(storage.getWorld(), this.worldClient);
        int wrong = this.quickcraft$getWrongInventoryCount();

        QUICKCRAFT_LOGGER.info(
                "container verifier {}: expected={} checked={} pending={} wrong={} requestedChunks={} servux={} backup={} storageWorldSame={} bestWorld={} cacheBE={} pendingBE={} chunks(noChannel={}, worldMismatch={}, completed={}, alreadyPending={}, issued={}, failed={}) actual(foundMissing={}, noCache={}, noItems={}, parseFailed={}, sizeMismatch={}, other={})",
                phase,
                this.quickcraft$expectedContainerPositions.size(),
                this.quickcraft$checkedContainerPositions.size(),
                this.quickcraft$pendingContainerPositions.size(),
                wrong,
                this.quickcraft$requestedContainerDataChunks.size(),
                storage.hasServuxServer(),
                storage.getIfReceivedBackupPackets(),
                storageWorldSame,
                bestWorld != null ? bestWorld.getClass().getSimpleName() : "null",
                storage.getBlockEntityCacheCount(),
                storage.getPendingBlockEntitiesCount(),
                this.quickcraft$debugNoDataChannelChunks,
                this.quickcraft$debugWorldMismatchChunks,
                this.quickcraft$debugCompletedChunks,
                this.quickcraft$debugAlreadyPendingChunks,
                this.quickcraft$debugIssuedChunkRequests,
                this.quickcraft$debugFailedChunkRequests,
                this.quickcraft$debugFoundBlockEntityMissing,
                this.quickcraft$debugActualNoCacheNbt,
                this.quickcraft$debugActualCacheWithoutItems,
                this.quickcraft$debugActualCacheParseFailed,
                this.quickcraft$debugActualSizeMismatch,
                this.quickcraft$debugActualOtherFailure
        );

        this.quickcraft$debugNoDataChannelChunks = 0;
        this.quickcraft$debugWorldMismatchChunks = 0;
        this.quickcraft$debugCompletedChunks = 0;
        this.quickcraft$debugAlreadyPendingChunks = 0;
        this.quickcraft$debugIssuedChunkRequests = 0;
        this.quickcraft$debugFailedChunkRequests = 0;
        this.quickcraft$debugFoundBlockEntityMissing = 0;
        this.quickcraft$debugActualNoCacheNbt = 0;
        this.quickcraft$debugActualCacheWithoutItems = 0;
        this.quickcraft$debugActualCacheParseFailed = 0;
        this.quickcraft$debugActualSizeMismatch = 0;
        this.quickcraft$debugActualOtherFailure = 0;
    }

    @Unique
    private void quickcraft$markContainerChecked(BlockPos pos) {
        this.quickcraft$expectedContainerPositions.add(pos.toImmutable());
        this.quickcraft$checkedContainerPositions.add(pos.toImmutable());
        this.quickcraft$pendingContainerPositions.remove(pos);
        this.quickcraft$pendingContainerReasons.remove(pos);
        this.quickcraft$logPendingContainerSamples("pending-resolved");
    }

    @Unique
    private void quickcraft$markContainerPending(BlockPos pos) {
        BlockPos immutablePos = pos.toImmutable();
        this.quickcraft$expectedContainerPositions.add(immutablePos);
        this.quickcraft$checkedContainerPositions.remove(pos);
        this.quickcraft$pendingContainerPositions.add(immutablePos);
        this.quickcraft$pendingContainerReasons.putIfAbsent(immutablePos, "unknown");
        this.quickcraft$logPendingContainerSamples("pending-added");
    }

    @Unique
    private void quickcraft$logPendingContainerSamples(String phase) {
        // 尾部少量待读取最难判断，直接把坐标和最后一次失败原因打出来。
        if (this.quickcraft$pendingContainerPositions.isEmpty()
                || this.quickcraft$pendingContainerPositions.size() > 20) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        this.quickcraft$pendingContainerPositions.stream()
                .sorted((left, right) -> {
                    int result = Integer.compare(left.getX(), right.getX());
                    if (result != 0) {
                        return result;
                    }

                    result = Integer.compare(left.getY(), right.getY());
                    return result != 0 ? result : Integer.compare(left.getZ(), right.getZ());
                })
                .forEach(pos -> {
                    if (!builder.isEmpty()) {
                        builder.append("; ");
                    }
                    builder.append(pos.toShortString())
                            .append(" -> ")
                            .append(this.quickcraft$pendingContainerReasons.getOrDefault(pos, "unknown"));
                });

        String sample = builder.toString();
        if (sample.equals(this.quickcraft$lastPendingContainerSampleLog)) {
            return;
        }

        this.quickcraft$lastPendingContainerSampleLog = sample;
        QUICKCRAFT_LOGGER.info(
                "container verifier {} pending samples: count={} {}",
                phase,
                this.quickcraft$pendingContainerPositions.size(),
                sample
        );
    }

    @Unique
    private List<BlockMismatch> quickcraft$createBlockMismatchesFor(MismatchType type) {
        List<BlockMismatch> list = new ArrayList<>();

        for (ContainerMismatch mismatch : this.quickcraft$containerMismatches.getOrDefault(type, List.of())) {
            list.add(this.quickcraft$createBlockMismatch(mismatch));
        }

        return list;
    }

    @Unique
    private BlockMismatch quickcraft$createBlockMismatch(ContainerMismatch mismatch) {
        BlockMismatch blockMismatch = new BlockMismatch(
                mismatch.type(),
                mismatch.expectedState(),
                mismatch.foundState(),
                1
        );
        ((BlockMismatchExtension) blockMismatch).quickcraft$setContainerMismatch(mismatch);
        return blockMismatch;
    }

    @Unique
    private List<BlockPos> quickcraft$getSelectedContainerPositionsForType(MismatchType type) {
        List<BlockPos> positions = new ArrayList<>();

        if (this.selectedCategories.contains(type)) {
            this.quickcraft$containerMismatches.getOrDefault(type, List.of()).stream()
                    .map(ContainerMismatch::pos)
                    .distinct()
                    .forEach(positions::add);
            return positions;
        }

        Collection<BlockMismatch> selected = this.selectedEntries.get(type);

        for (BlockMismatch mismatch : selected) {
            ContainerMismatchKey key = ((BlockMismatchExtension) mismatch).quickcraft$getContainerMismatchKey();
            ContainerMismatch containerMismatch = key != null ? this.quickcraft$containerMismatchesByKey.get(key) : null;

            if (containerMismatch != null && !positions.contains(containerMismatch.pos())) {
                positions.add(containerMismatch.pos());
            }
        }

        return positions;
    }

    @Unique
    private void quickcraft$addHudPositions(List<MismatchRenderPos> positions, int maxLines) {
        int count = Math.min(positions.size(), maxLines);
        String rst = GuiBase.TXT_RST;

        for (int i = 0; i < count; i++) {
            MismatchRenderPos entry = positions.get(i);
            BlockPos pos = entry.pos;
            String pre = quickcraft$getHudColorCode(entry.type);
            this.infoHudLines.add(String.format("%sx: %5d, y: %3d, z: %5d%s", pre, pos.getX(), pos.getY(), pos.getZ(), rst));
        }
    }

    @Unique
    private String quickcraft$getHudColorCode(MismatchType type) {
        if (QuickLitematicaContainerVerifier.isContainerMismatchType(type)) {
            return QuickLitematicaVerifierPalette.formattingCode(type);
        }

        return type.getColorCode();
    }

    @Unique
    private boolean quickcraft$isContainerPosSelected(BlockPos pos) {
        for (BlockMismatch mismatch : this.quickcraft$selectedContainerMismatches) {
            ContainerMismatch containerMismatch =
                    ((BlockMismatchExtension) mismatch).quickcraft$getContainerMismatch();

            if (containerMismatch != null && containerMismatch.pos().equals(pos)) {
                return true;
            }
        }

        return false;
    }

    @Unique
    private void quickcraft$restoreContainerSelection(BlockPos pos, boolean wasSelected) {
        this.quickcraft$selectedContainerMismatches.removeIf(mismatch -> {
            ContainerMismatch containerMismatch =
                    ((BlockMismatchExtension) mismatch).quickcraft$getContainerMismatch();
            return containerMismatch != null && containerMismatch.pos().equals(pos);
        });
        this.selectedEntries.values().removeIf(mismatch -> {
            ContainerMismatch containerMismatch =
                    ((BlockMismatchExtension) mismatch).quickcraft$getContainerMismatch();
            return containerMismatch != null && containerMismatch.pos().equals(pos);
        });

        if (!wasSelected) {
            return;
        }

        for (ContainerMismatch mismatch : this.quickcraft$containerMismatchesByKey.values()) {
            if (mismatch.pos().equals(pos)) {
                BlockMismatch blockMismatch = this.quickcraft$createBlockMismatch(mismatch);
                this.quickcraft$selectedContainerMismatches.add(blockMismatch);
                this.selectedEntries.put(blockMismatch.mismatchType, blockMismatch);
                return;
            }
        }
    }

    @Unique
    private String quickcraft$getContainerMismatchSignature(ContainerMismatch mismatch) {
        StringBuilder builder = new StringBuilder();

        builder.append(mismatch.type().ordinal()).append('|');

        for (QuickLitematicaContainerVerifier.SlotMismatch slotMismatch : mismatch.slotMismatches()) {
            builder.append(slotMismatch.slot())
                    .append(':')
                    .append(slotMismatch.status().name())
                    .append(':')
                    .append(slotMismatch.expectedStack().getCount())
                    .append(':')
                    .append(slotMismatch.foundStack().getCount())
                    .append(':')
                    .append(slotMismatch.expectedStack().isEmpty() ? "empty" : slotMismatch.expectedStack().getItem())
                    .append(':')
                    .append(slotMismatch.foundStack().isEmpty() ? "empty" : slotMismatch.foundStack().getItem())
                    .append(';');
        }

        return builder.toString();
    }
}
