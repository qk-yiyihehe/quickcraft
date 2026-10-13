package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaSaveContext;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class LitematicaGlobalSaveMixin {
    @WrapOperation(method = {"takeBlocksFromWorld", "takeBlocksFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;", remap = true))
    private BlockState quickcraft$saveBaselineState(World world, BlockPos pos, Operation<BlockState> original) {
        if (!(world instanceof WorldSchematic)) { return original.call(world, pos); }
        var previous = QuickLitematicaSaveContext.begin(world, pos);
        try { return original.call(world, pos); }
        finally { QuickLitematicaSaveContext.end(previous); }
    }

    @WrapOperation(method = {"takeBlocksFromWorld", "takeBlocksFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlockEntity(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/entity/BlockEntity;", remap = true))
    private BlockEntity quickcraft$saveBaselineEntity(World world, BlockPos pos, Operation<BlockEntity> original) {
        if (!(world instanceof WorldSchematic)) { return original.call(world, pos); }
        var previous = QuickLitematicaSaveContext.begin(world, pos);
        try { return original.call(world, pos); }
        finally { QuickLitematicaSaveContext.end(previous); }
    }

    @WrapOperation(method = {"takeEntitiesFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;saveData(Lnet/minecraft/storage/WriteView;)Z", remap = true))
    private boolean quickcraft$saveBaselineEntityContents(net.minecraft.entity.Entity entity, net.minecraft.storage.WriteView output, Operation<Boolean> original) {
        boolean saved = original.call(entity, output);
        if (saved) { quickcraft$restore(entity, output); }
        return saved;
    }

    @WrapOperation(method = {"takeEntitiesFromWorld"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;writeData(Lnet/minecraft/storage/WriteView;)V", remap = true))
    private void quickcraft$writeBaselineEntityContents(net.minecraft.entity.Entity entity, net.minecraft.storage.WriteView output, Operation<Void> original) {
        original.call(entity, output);
        quickcraft$restore(entity, output);
    }

    private static void quickcraft$restore(net.minecraft.entity.Entity entity, net.minecraft.storage.WriteView output) {
        if (entity.getWorld() instanceof WorldSchematic && output instanceof net.minecraft.storage.NbtWriteView nbt) {
            QuickLitematicaGlobalReplacements.restoreSavedEntityItems(entity, nbt.getNbt());
        }
    }
}
