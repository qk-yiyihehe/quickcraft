package com.yiyihehe.quickcraft.mixin;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerification;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.VerifierExtension;
import fi.dy.masa.litematica.scheduler.tasks.TaskBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.IntBoundingBox;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

/**
 * 把容器内容校验并入 Litematica 原版验证流程。
 * 注入点保留原版调用时机，容器业务由独立核心维护。
 */
@Mixin(value = SchematicVerifier.class, remap = false)
public abstract class LitematicaSchematicVerifierMixin extends TaskBase implements VerifierExtension, QuickLitematicaContainerVerification.Host {

    @Shadow
    @Final
    private static List<SchematicVerifier> ACTIVE_VERIFIERS;

    @Shadow
    private ClientWorld worldClient;

    @Shadow
    private SchematicPlacement schematicPlacement;

    @Shadow
    @Final
    private Set<ChunkPos> requiredChunks;

    @Shadow
    private int totalRequiredChunks;

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

    @Override
    public List<BlockMismatch> quickcraft$getSelectedInventoryMismatches() {
        return this.quickcraft$containerVerification.quickcraft$getSelectedInventoryMismatches();
    }

    @Override
    public List<ContainerMismatch> quickcraft$getContainerMismatches() {
        return this.quickcraft$containerVerification.quickcraft$getContainerMismatches();
    }

    @Override
    public List<ItemStack> quickcraft$getMissingContainerStacks() {
        return this.quickcraft$containerVerification.quickcraft$getMissingContainerStacks();
    }

    @Override
    public int quickcraft$getWrongInventoryCount() {
        return this.quickcraft$containerVerification.quickcraft$getWrongInventoryCount();
    }

    @Override
    public int quickcraft$getContainerMismatchCount(MismatchType type) {
        return this.quickcraft$containerVerification.quickcraft$getContainerMismatchCount(type);
    }

    @Override
    public int quickcraft$getExpectedContainerCount() {
        return this.quickcraft$containerVerification.quickcraft$getExpectedContainerCount();
    }

    @Override
    public int quickcraft$getCheckedContainerCount() {
        return this.quickcraft$containerVerification.quickcraft$getCheckedContainerCount();
    }

    @Override
    public void quickcraft$setContainerOnly(boolean containerOnly) {
        this.quickcraft$containerVerification.quickcraft$setContainerOnly(containerOnly);
    }

    @Override
    public int quickcraft$getPendingContainerCount() {
        return this.quickcraft$containerVerification.quickcraft$getPendingContainerCount();
    }

    @Override
    public List<ContainerMismatch> quickcraft$refreshContainerMismatchAt(BlockPos pos, Inventory foundInventory, Set<Integer> foundDisabledSlots) {
        return this.quickcraft$containerVerification.quickcraft$refreshContainerMismatchAt(pos, foundInventory, foundDisabledSlots);
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
        return this.quickcraft$containerVerification.quickcraft$useBestWorldForSinglePlayer(clientWorld, chunkX, chunkZ);
    }

    @Redirect(
            method = "verifyChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/world/ChunkManagerSchematic;isChunkLoaded(II)Z"
            )
    )
    private boolean quickcraft$waitForContainerData(ChunkManagerSchematic chunkManager, int chunkX, int chunkZ) {
        return this.quickcraft$containerVerification.quickcraft$waitForContainerData(chunkManager, chunkX, chunkZ);
    }

    // 独立的容器材料验证器在 HEAD 直接收集方块实体，再结束原版的逐坐标体积扫描。
    // 若 Litematica 改变 verifyChunk 的调用约定，最明显的症状会是投影容器材料页没有结果。
    @Inject(
            method = "verifyChunk",
            at = @At("HEAD"),
            cancellable = true
    )
    private void quickcraft$skipBlockVolumeForContainerOnlyVerification(
            Chunk chunkClient,
            Chunk chunkSchematic,
            IntBoundingBox box,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Boolean result = this.quickcraft$containerVerification.quickcraft$skipBlockVolumeForContainerOnlyVerification(chunkClient, chunkSchematic, box);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(
            method = "verifyChunk",
            at = @At("RETURN")
    )
    private void quickcraft$checkContainerInventoriesAfterBlockVerification(
            Chunk chunkClient,
            Chunk chunkSchematic,
            IntBoundingBox box,
            CallbackInfoReturnable<Boolean> cir
    ) {
        this.quickcraft$containerVerification.quickcraft$checkContainerInventoriesAfterBlockVerification(chunkClient, chunkSchematic, box);
    }

    @Inject(method = "getMismatchOverviewFor", at = @At("HEAD"), cancellable = true)
    private void quickcraft$getInventoryMismatchOverview(MismatchType type, CallbackInfoReturnable<List<BlockMismatch>> cir) {
        List<BlockMismatch> result = this.quickcraft$containerVerification.quickcraft$getInventoryMismatchOverview(type);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "getMismatchOverviewCombined", at = @At("RETURN"))
    private void quickcraft$addInventoryMismatchOverview(CallbackInfoReturnable<List<BlockMismatch>> cir) {
        this.quickcraft$containerVerification.quickcraft$addInventoryMismatchOverview(cir.getReturnValue());
    }

    @Inject(method = "getMapForMismatchType", at = @At("HEAD"), cancellable = true)
    private void quickcraft$getInventoryMismatchMap(MismatchType type, CallbackInfoReturnable<ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos>> cir) {
        ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> result = this.quickcraft$containerVerification.quickcraft$getInventoryMismatchMap(type);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "updateClosestPositions", at = @At("TAIL"))
    private void quickcraft$updateClosestInventoryPositions(BlockPos centerPos, int maxEntries, CallbackInfo ci) {
        this.quickcraft$containerVerification.quickcraft$updateClosestInventoryPositions(centerPos, maxEntries);
    }

    @Inject(method = "combineClosestPositions", at = @At("TAIL"))
    private void quickcraft$combineClosestInventoryPositions(BlockPos centerPos, int maxEntries, CallbackInfo ci) {
        this.quickcraft$containerVerification.quickcraft$combineClosestInventoryPositions(centerPos, maxEntries);
    }

    @Inject(method = "getClosestMismatchedPositionsFor", at = @At("HEAD"), cancellable = true)
    private void quickcraft$getClosestInventoryPositions(MismatchType type, CallbackInfoReturnable<List<BlockPos>> cir) {
        List<BlockPos> result = this.quickcraft$containerVerification.quickcraft$getClosestInventoryPositions(type);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "toggleMismatchEntrySelected", at = @At("TAIL"))
    private void quickcraft$trackSelectedInventoryMismatch(BlockMismatch mismatch, CallbackInfo ci) {
        this.quickcraft$containerVerification.quickcraft$trackSelectedInventoryMismatch(mismatch);
    }

    @Inject(method = "removeSelectedEntriesOfType", at = @At("HEAD"))
    private void quickcraft$removeSelectedInventoryMismatches(MismatchType type, CallbackInfo ci) {
        this.quickcraft$containerVerification.quickcraft$removeSelectedInventoryMismatches(type);
    }

    @Inject(method = "ignoreStateMismatch(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$BlockMismatch;Z)V", at = @At("HEAD"), cancellable = true)
    private void quickcraft$forgetIgnoredInventoryMismatch(BlockMismatch mismatch, boolean updateOverlay, CallbackInfo ci) {
        if (this.quickcraft$containerVerification.quickcraft$forgetIgnoredInventoryMismatch(mismatch, updateOverlay)) {
            ci.cancel();
        }
    }

    @Inject(method = "clearData", at = @At("HEAD"))
    private void quickcraft$clearInventoryData(CallbackInfo ci) {
        this.quickcraft$containerVerification.quickcraft$clearInventoryData();
    }

    @Inject(method = "startVerification", at = @At("TAIL"))
    private void quickcraft$requestContainerDataOnStart(
            ClientWorld worldClient,
            WorldSchematic worldSchematic,
            SchematicPlacement schematicPlacement,
            ICompletionListener completionListener,
            CallbackInfo ci
    ) {
        this.quickcraft$containerVerification.quickcraft$requestContainerDataOnStart(worldClient, worldSchematic, schematicPlacement, completionListener);
    }

    @Inject(method = "verifyChunks", at = @At("RETURN"))
    private void quickcraft$logContainerVerificationProblems(CallbackInfoReturnable<Boolean> cir) {
        this.quickcraft$containerVerification.quickcraft$logContainerVerificationProblems(cir.getReturnValue());
    }

    @Inject(method = "execute", at = @At("TAIL"))
    private void quickcraft$refreshContainerMismatches(CallbackInfoReturnable<Boolean> cir) {
        this.quickcraft$containerVerification.quickcraft$refreshContainerMismatches();
    }

    @Inject(method = "updateMismatchPositionStringList", at = @At("TAIL"))
    private void quickcraft$splitInventoryHudLines(@Nullable MismatchType mismatchType, List<MismatchRenderPos> positionList, CallbackInfo ci) {
        this.quickcraft$containerVerification.quickcraft$splitInventoryHudLines(mismatchType, positionList);
    }

    @Inject(method = "execute", at = @At("RETURN"), cancellable = true)
    private void quickcraft$removeFinishedContainerOnlyVerifier(CallbackInfoReturnable<Boolean> cir) {
        Boolean result = this.quickcraft$containerVerification.quickcraft$removeFinishedContainerOnlyVerifier();
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Unique
    private final QuickLitematicaContainerVerification quickcraft$containerVerification =
            new QuickLitematicaContainerVerification(this);

    @Override
    public ClientWorld quickcraft$verificationWorldClient() {
        return this.worldClient;
    }

    @Override
    public SchematicPlacement quickcraft$verificationSchematicPlacement() {
        return this.schematicPlacement;
    }

    @Override
    public Set<ChunkPos> quickcraft$verificationRequiredChunks() {
        return this.requiredChunks;
    }

    @Override
    public List<MismatchRenderPos> quickcraft$verificationMismatchPositionsForRender() {
        return this.mismatchPositionsForRender;
    }

    @Override
    public List<BlockPos> quickcraft$verificationMismatchBlockPositionsForRender() {
        return this.mismatchBlockPositionsForRender;
    }

    @Override
    public Set<MismatchType> quickcraft$verificationSelectedCategories() {
        return this.selectedCategories;
    }

    @Override
    public HashMultimap<MismatchType, BlockMismatch> quickcraft$verificationSelectedEntries() {
        return this.selectedEntries;
    }

    @Override
    public List<String> quickcraft$verificationInfoHudLines() {
        return this.infoHudLines;
    }

    @Override
    public boolean quickcraft$verificationFinished() {
        return this.finished;
    }

    @Override
    public void quickcraft$setTotalRequiredChunks(int count) {
        this.totalRequiredChunks = count;
    }

    @Override
    public void quickcraft$updateVerificationOverlays() {
        this.updateMismatchOverlays();
    }

    @Override
    public boolean quickcraft$isVerificationEntrySelected(BlockMismatch mismatch) {
        return this.isMismatchEntrySelected(mismatch);
    }

    @Override
    public SchematicVerifier quickcraft$verifier() {
        return (SchematicVerifier) (Object) this;
    }

    @Override
    public List<SchematicVerifier> quickcraft$activeVerifiers() {
        return ACTIVE_VERIFIERS;
    }
}
