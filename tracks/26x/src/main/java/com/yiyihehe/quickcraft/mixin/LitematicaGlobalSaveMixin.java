package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaSaveContext;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class LitematicaGlobalSaveMixin {
    @WrapOperation(method = {"takeBlocksFromWorld", "takeBlocksFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", remap = true))
    private BlockState quickcraft$saveBaselineState(Level world, BlockPos pos, Operation<BlockState> original) {
        if (!(world instanceof WorldSchematic)) { return original.call(world, pos); }
        var previous = QuickLitematicaSaveContext.begin(world, pos);
        try { return original.call(world, pos); }
        finally { QuickLitematicaSaveContext.end(previous); }
    }

    @WrapOperation(method = {"takeBlocksFromWorld", "takeBlocksFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", remap = true))
    private BlockEntity quickcraft$saveBaselineEntity(Level world, BlockPos pos, Operation<BlockEntity> original) {
        if (!(world instanceof WorldSchematic)) { return original.call(world, pos); }
        var previous = QuickLitematicaSaveContext.begin(world, pos);
        try { return original.call(world, pos); }
        finally { QuickLitematicaSaveContext.end(previous); }
    }

    @WrapOperation(method = {"takeEntitiesFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;save(Lnet/minecraft/world/level/storage/ValueOutput;)Z", remap = true))
    private boolean quickcraft$saveBaselineEntityContents(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.storage.ValueOutput output, Operation<Boolean> original) {
        boolean saved = original.call(entity, output);
        if (saved) { quickcraft$restore(entity, output); }
        return saved;
    }

    @WrapOperation(method = {"takeEntitiesFromWorld"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;)V", remap = true))
    private void quickcraft$writeBaselineEntityContents(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.storage.ValueOutput output, Operation<Void> original) {
        original.call(entity, output);
        quickcraft$restore(entity, output);
    }

    private static void quickcraft$restore(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.storage.ValueOutput output) {
        if (entity.level() instanceof WorldSchematic && output instanceof net.minecraft.world.level.storage.TagValueOutput nbt) {
            QuickLitematicaGlobalReplacements.restoreSavedEntityItems(entity, nbt.buildResult());
        }
    }
}
