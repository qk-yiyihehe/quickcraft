package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceInteractions;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 扩展 Litematica 轻松放置的水源施工与原版交互退让。 */
@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaWorldUtilsEasyPlaceMixin {
    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$placeIceOverProjectedSourceWater(
            Minecraft client, CallbackInfoReturnable<InteractionResult> cir) {
        if (!QuickCraftConfigs.isEasyPlaceIceOverSourceWaterAllowed()
                || client.player == null || client.level == null || client.gameMode == null) {
            return;
        }

        Entity traceEntity = QuickFreeCameraInteractions.getEasyPlaceTraceEntity(client, client.player);
        double range = WorldUtils.getValidBlockRange(client);
        RayTraceUtils.RayTraceWrapper trace = RayTraceUtils.getGenericTrace(
                client.level, traceEntity, range, true, true, false);
        if (trace == null || trace.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {
            return;
        }

        BlockHitResult schematicHit = trace.getBlockHitResult();
        Level schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicHit == null || schematicWorld == null) {
            return;
        }

        BlockPos targetPos = schematicHit.getBlockPos();
        BlockState schematicState = schematicWorld.getBlockState(targetPos);
        BlockState worldState = client.level.getBlockState(targetPos);
        if (!schematicState.is(Blocks.WATER) || !schematicState.getFluidState().isSource()
                || (!worldState.isAir()
                && (!worldState.is(Blocks.WATER) || !worldState.getFluidState().isSource()))) {
            return;
        }

        ItemStack iceStack = new ItemStack(Blocks.ICE);
        InventoryUtils.schematicWorldPickBlock(iceStack, targetPos, schematicWorld, client);
        InteractionHand hand = fi.dy.masa.litematica.util.EntityUtils.getUsedHandForItem(client.player, iceStack);
        if (hand == null) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        BlockHitResult placementHit = schematicHit;
        HitResult vanillaTrace = RayTraceUtils.getRayTraceFromEntity(client.level, traceEntity, false, range);
        if (vanillaTrace instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().relative(blockHit.getDirection()).equals(targetPos)) {
            placementHit = blockHit;
        }

        InteractionResult result = client.gameMode.useItemOn(client.player, hand, placementHit);
        if (result instanceof InteractionResult.Success success
                && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            client.player.swing(hand);
        }
        cir.setReturnValue(result == InteractionResult.FAIL ? InteractionResult.FAIL : InteractionResult.SUCCESS);
    }

    @Inject(method = "handleEasyPlace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letVanillaUsePassThrough(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "easyPlaceOnUseTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$skipHoldEasyPlaceOnVanillaInteractions(Minecraft mc, CallbackInfo ci) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlacementRestriction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letVanillaUseBypassPlacementRestriction(
            Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            cir.setReturnValue(false);
        }
    }
}
