package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceInteractions;
import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 扩展 Litematica 轻松放置的水源施工与原版交互退让。
 * Litematica 的 WorldUtils 会对带 onUse 的支撑方块伪装潜行后继续发送放置包；
 * 因此必须同时退出轻松放置和放置限制入口，原版交互才不会被覆盖。
 */
@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaWorldUtilsEasyPlaceMixin {
    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$placeIceOverProjectedSourceWater(
            MinecraftClient client,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        if (!QuickCraftConfigs.isEasyPlaceIceOverSourceWaterAllowed()
                || client.player == null
                || client.world == null
                || client.interactionManager == null) {
            return;
        }

        Entity traceEntity = QuickFreeCameraInteractions.getEasyPlaceTraceEntity(client, client.player);
        double range = WorldUtils.getValidBlockRange(client);
        RayTraceUtils.RayTraceWrapper traceWrapper = RayTraceUtils.getGenericTrace(
                client.world, traceEntity, range, true, true, false
        );
        if (traceWrapper == null
                || traceWrapper.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {
            return;
        }

        BlockHitResult schematicHit = traceWrapper.getBlockHitResult();
        World schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicHit == null || schematicWorld == null) {
            return;
        }

        BlockPos targetPos = schematicHit.getBlockPos();
        BlockState schematicState = schematicWorld.getBlockState(targetPos);
        BlockState worldState = client.world.getBlockState(targetPos);
        if (!schematicState.isOf(Blocks.WATER)
                || !schematicState.getFluidState().isStill()
                || (!worldState.isAir()
                && (!worldState.isOf(Blocks.WATER) || !worldState.getFluidState().isStill()))) {
            return;
        }

        ItemStack iceStack = new ItemStack(Blocks.ICE);
        InventoryUtils.schematicWorldPickBlock(iceStack, targetPos, schematicWorld, client);
        Hand hand = fi.dy.masa.litematica.util.EntityUtils.getUsedHandForItem(client.player, iceStack);
        if (hand == null) {
            cir.setReturnValue(ActionResult.FAIL);
            return;
        }

        BlockHitResult placementHit = schematicHit;
        HitResult vanillaTrace = RayTraceUtils.getRayTraceFromEntity(client.world, traceEntity, false, range);
        if (vanillaTrace instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().offset(blockHit.getSide()).equals(targetPos)) {
            placementHit = blockHit;
        }

        ActionResult result = client.interactionManager.interactBlock(client.player, hand, placementHit);
        if (result.shouldSwingHand()) {
            client.player.swingHand(hand);
        }
        cir.setReturnValue(result == ActionResult.FAIL ? ActionResult.FAIL : ActionResult.SUCCESS);
    }

    @Inject(method = "handleEasyPlace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letVanillaUsePassThrough(MinecraftClient mc, CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "easyPlaceOnUseTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$skipHoldEasyPlaceOnVanillaInteractions(MinecraftClient mc, CallbackInfo ci) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlacementRestriction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letVanillaUseBypassPlacementRestriction(
            MinecraftClient mc,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            cir.setReturnValue(false);
        }
    }
}
