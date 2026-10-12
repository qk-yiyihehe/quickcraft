package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionMaterials;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksBase;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TaskCountBlocksPlacement.class, remap = false)
public abstract class LitematicaProjectionCountPlacementMixin extends TaskCountBlocksBase
        implements QuickLitematicaProjectionMaterials.OriginalCountSource {
    @Shadow @Final protected SchematicPlacement schematicPlacement;
    @Shadow @Final protected boolean ignoreState;
    @Unique private final Object2IntOpenHashMap<BlockState> quickcraft$originalTotal = new Object2IntOpenHashMap<>();
    @Unique private final Object2IntOpenHashMap<BlockState> quickcraft$originalMissing = new Object2IntOpenHashMap<>();
    @Unique private final Object2IntOpenHashMap<BlockState> quickcraft$originalMismatch = new Object2IntOpenHashMap<>();

    protected LitematicaProjectionCountPlacementMixin(IMaterialList list, String name) { super(list, name); }

    @Inject(method = "countAtPosition", at = @At("HEAD"), cancellable = true)
    private void quickcraft$countWithoutGlobals(BlockPos pos, CallbackInfo ci) {
        if (!com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements.hasRuntimeRules()) { return; }
        BlockState actual = com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements.baselineState(this.schematicPlacement, pos);
        ci.cancel();
        if (actual.isAir()) { return; }
        this.countsTotal.addTo(actual, 1);
        BlockState client = this.clientWorld.getBlockState(pos);
        if (client.isAir()) { this.countsMissing.addTo(actual, 1); }
        else if (client != actual && (!this.ignoreState || client.getBlock() != actual.getBlock())) {
            this.countsMissing.addTo(actual, 1);
            this.countsMismatch.addTo(actual, 1);
        }
        this.quickcraft$collectOriginal(pos, actual);
    }

    @Inject(method = "countAtPosition", at = @At("TAIL"))
    private void quickcraft$countOriginal(BlockPos pos, CallbackInfo ci) {
        this.quickcraft$collectOriginal(pos, this.schematicWorld.getBlockState(pos));
    }

    @Unique
    private void quickcraft$collectOriginal(BlockPos pos, BlockState actual) {
        if (actual.isAir()) { return; }
        BlockState source = com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements.originalState(this.schematicPlacement, pos);
        this.quickcraft$originalTotal.addTo(source, 1);
        BlockState client = this.clientWorld.getBlockState(pos);
        if (client.isAir()) { this.quickcraft$originalMissing.addTo(source, 1); }
        else if (client != actual && (!this.ignoreState || client.getBlock() != actual.getBlock())) {
            this.quickcraft$originalMissing.addTo(source, 1);
            this.quickcraft$originalMismatch.addTo(source, 1);
        }
    }

    @Override
    public void quickcraft$prepareOriginalRows() {
        if (this.finished && this.isInWorld() && this.materialList instanceof MaterialListBase list) {
            QuickLitematicaProjectionMaterials.prepareEditor(list,
                    this.quickcraft$originalTotal, this.quickcraft$originalMissing, this.quickcraft$originalMismatch);
        }
    }
}
