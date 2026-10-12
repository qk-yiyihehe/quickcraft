package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class LitematicaGlobalSaveMixin {
    @Redirect(method = {"takeBlocksFromWorld", "takeBlocksFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;", remap = true))
    private BlockState quickcraft$saveBaselineState(World world, BlockPos pos) {
        return world instanceof WorldSchematic ? QuickLitematicaGlobalReplacements.baselineWorldState(pos) : world.getBlockState(pos);
    }

    @Redirect(method = {"takeBlocksFromWorld", "takeBlocksFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlockEntity(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/entity/BlockEntity;", remap = true))
    private BlockEntity quickcraft$saveBaselineEntity(World world, BlockPos pos) {
        return world instanceof WorldSchematic ? QuickLitematicaGlobalReplacements.baselineWorldEntity(pos, world) : world.getBlockEntity(pos);
    }

    @Redirect(method = {"takeEntitiesFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;saveData(Lnet/minecraft/storage/WriteView;)Z", remap = true))
    private boolean quickcraft$saveBaselineEntityContents(net.minecraft.entity.Entity entity, net.minecraft.storage.WriteView output) {
        boolean saved = entity.saveData(output);
        if (saved) { quickcraft$restore(entity, output); }
        return saved;
    }

    @Redirect(method = {"takeEntitiesFromWorld"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;writeData(Lnet/minecraft/storage/WriteView;)V", remap = true))
    private void quickcraft$writeBaselineEntityContents(net.minecraft.entity.Entity entity, net.minecraft.storage.WriteView output) {
        entity.writeData(output);
        quickcraft$restore(entity, output);
    }

    private static void quickcraft$restore(net.minecraft.entity.Entity entity, net.minecraft.storage.WriteView output) {
        if (entity.getWorld() instanceof WorldSchematic && output instanceof net.minecraft.storage.NbtWriteView nbt) {
            QuickLitematicaGlobalReplacements.restoreSavedEntityItems(entity, nbt.getNbt());
        }
    }
}
