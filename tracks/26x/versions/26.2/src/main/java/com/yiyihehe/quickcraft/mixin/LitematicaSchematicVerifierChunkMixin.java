package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.VerifierExtension;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2–26.3 区块验证切入点（使用新版 malilib util position IntBoundingBox）。
 */
@Mixin(value = SchematicVerifier.class, remap = false)
public abstract class LitematicaSchematicVerifierChunkMixin {
    @Inject(method = "verifyChunk", at = @At("HEAD"), cancellable = true)
    private void quickcraft$skipBlockVolumeForContainerOnlyVerification(
            ChunkAccess chunkClient,
            ChunkAccess chunkSchematic,
            IntBoundingBox box,
            CallbackInfoReturnable<Boolean> cir
    ) {
        VerifierExtension extension = (VerifierExtension) this;
        if (extension.quickcraft$isContainerOnly()) {
            extension.quickcraft$collectContainerInventories(
                    chunkClient,
                    chunkSchematic,
                    box.minX(),
                    box.minY(),
                    box.minZ(),
                    box.maxX(),
                    box.maxY(),
                    box.maxZ()
            );
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "verifyChunk", at = @At("RETURN"))
    private void quickcraft$checkContainerInventoriesAfterBlockVerification(
            ChunkAccess chunkClient,
            ChunkAccess chunkSchematic,
            IntBoundingBox box,
            CallbackInfoReturnable<Boolean> cir
    ) {
        ((VerifierExtension) this).quickcraft$collectContainerInventories(
                chunkClient,
                chunkSchematic,
                box.minX(),
                box.minY(),
                box.minZ(),
                box.maxX(),
                box.maxY(),
                box.maxZ()
        );
    }
}
