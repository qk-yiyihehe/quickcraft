package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class LitematicaGlobalSaveMixin {
    @Redirect(method = {"takeBlocksFromWorld", "takeBlocksFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", remap = true))
    private BlockState quickcraft$saveBaselineState(Level world, BlockPos pos) {
        return world instanceof WorldSchematic ? QuickLitematicaGlobalReplacements.baselineWorldState(pos) : world.getBlockState(pos);
    }

    @Redirect(method = {"takeBlocksFromWorld", "takeBlocksFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", remap = true))
    private BlockEntity quickcraft$saveBaselineEntity(Level world, BlockPos pos) {
        return world instanceof WorldSchematic ? QuickLitematicaGlobalReplacements.baselineWorldEntity(pos, world) : world.getBlockEntity(pos);
    }

    @Redirect(method = {"takeEntitiesFromWorldWithinChunk"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;save(Lnet/minecraft/world/level/storage/ValueOutput;)Z", remap = true))
    private boolean quickcraft$saveBaselineEntityContents(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.storage.ValueOutput output) {
        boolean saved = entity.save(output);
        if (saved) { quickcraft$restore(entity, output); }
        return saved;
    }

    @Redirect(method = {"takeEntitiesFromWorld"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;)V", remap = true))
    private void quickcraft$writeBaselineEntityContents(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.storage.ValueOutput output) {
        entity.saveWithoutId(output);
        quickcraft$restore(entity, output);
    }

    private static void quickcraft$restore(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.storage.ValueOutput output) {
        if (entity.level() instanceof WorldSchematic && output instanceof net.minecraft.world.level.storage.TagValueOutput nbt) {
            QuickLitematicaGlobalReplacements.restoreSavedEntityItems(entity, nbt.buildResult());
        }
    }
}
