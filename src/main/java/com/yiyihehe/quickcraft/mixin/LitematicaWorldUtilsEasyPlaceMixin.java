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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 扩展 Litematica 轻松放置的水源施工与原版交互退让。
 * Litematica 的 WorldUtils 会对带 onUse 的支撑方块伪装潜行后继续发送放置包；
 * 因此必须在 handleEasyPlace 与 easyPlaceOnUseTick 入口处取消，原版交互才不会被覆盖。
 */
@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaWorldUtilsEasyPlaceMixin {
    private static final Logger QUICKCRAFT_EASY_PLACE_LOGGER = LoggerFactory.getLogger("QuickCraft-EasyPlaceIce");
    private static long quickcraft$lastDiagnosticNanos;
    private static boolean quickcraft$materialRedirectMatched;
    private static String quickcraft$iceBranchDiagnostic = "not-run";

    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$placeIceOverProjectedSourceWater(
            MinecraftClient client,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        quickcraft$materialRedirectMatched = false;
        quickcraft$iceBranchDiagnostic = "preconditions";
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
        quickcraft$iceBranchDiagnostic = "trace=" + (traceWrapper == null ? "none" : traceWrapper.getHitType());
        if (traceWrapper == null
                || traceWrapper.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {
            return;
        }

        BlockHitResult schematicHit = traceWrapper.getBlockHitResult();
        World schematicWorld = SchematicWorldHandler.getSchematicWorld();
        quickcraft$iceBranchDiagnostic = "schematic-hit=" + (schematicHit != null) + " world=" + (schematicWorld != null);
        if (schematicHit == null || schematicWorld == null) {
            return;
        }

        BlockPos targetPos = schematicHit.getBlockPos();
        BlockState schematicState = schematicWorld.getBlockState(targetPos);
        BlockState worldState = client.world.getBlockState(targetPos);
        quickcraft$iceBranchDiagnostic = "target=" + targetPos.toShortString()
                + " schematic=" + schematicState
                + " world=" + worldState
                + " schematicStill=" + schematicState.getFluidState().isStill()
                + " worldStill=" + worldState.getFluidState().isStill();
        if (!schematicState.isOf(Blocks.WATER)
                || !schematicState.getFluidState().isStill()
                || (!worldState.isAir()
                && (!worldState.isOf(Blocks.WATER) || !worldState.getFluidState().isStill()))) {
            return;
        }

        quickcraft$materialRedirectMatched = true;
        ItemStack iceStack = new ItemStack(Blocks.ICE);
        InventoryUtils.schematicWorldPickBlock(iceStack, targetPos, schematicWorld, client);
        Hand hand = fi.dy.masa.litematica.util.EntityUtils.getUsedHandForItem(client.player, iceStack);
        quickcraft$iceBranchDiagnostic = "hand=" + hand;
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
        quickcraft$iceBranchDiagnostic = "interaction=" + result + " hit=" + placementHit.getBlockPos().toShortString()
                + " side=" + placementHit.getSide();
        if (result.shouldSwingHand()) {
            client.player.swingHand(hand);
        }
        cir.setReturnValue(result == ActionResult.FAIL ? ActionResult.FAIL : ActionResult.SUCCESS);
    }

    @Inject(method = "doEasyPlaceAction", at = @At("RETURN"), remap = false)
    private static void quickcraft$logEasyPlaceIceDiagnostic(
            MinecraftClient client,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        long now = System.nanoTime();
        if (now - quickcraft$lastDiagnosticNanos < 500_000_000L) {
            return;
        }
        quickcraft$lastDiagnosticNanos = now;

        String target = "none";
        String worldState = "n/a";
        String schematicState = "n/a";
        String schematicTrace = "none";
        String genericTrace = "none";
        if (client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && client.world != null) {
            BlockPos pos = hit.getBlockPos();
            target = pos.toShortString();
            worldState = client.world.getBlockState(pos).toString();
            World schematicWorld = SchematicWorldHandler.getSchematicWorld();
            if (schematicWorld != null) {
                schematicState = schematicWorld.getBlockState(pos).toString();
            }
        }

        if (client.player != null && client.world != null) {
            Entity traceEntity = QuickFreeCameraInteractions.getEasyPlaceTraceEntity(client, client.player);
            double range = WorldUtils.getValidBlockRange(client);
            BlockHitResult schematicHit = RayTraceUtils.traceToSchematicWorld(traceEntity, range, true, true);
            if (schematicHit != null) {
                BlockPos pos = schematicHit.getBlockPos();
                World schematicWorld = SchematicWorldHandler.getSchematicWorld();
                schematicTrace = pos.toShortString()
                        + " state=" + (schematicWorld == null ? "n/a" : schematicWorld.getBlockState(pos));
            }
            RayTraceUtils.RayTraceWrapper genericHit = RayTraceUtils.getGenericTrace(
                    client.world, traceEntity, range, true, true, false
            );
            if (genericHit != null) {
                BlockHitResult blockHit = genericHit.getBlockHitResult();
                genericTrace = genericHit.getHitType()
                        + (blockHit == null ? "" : " pos=" + blockHit.getBlockPos());
            }
        }

        QUICKCRAFT_EASY_PLACE_LOGGER.info(
                "attempt result={} config={} target={} world={} schematic={} schematicTrace={} genericTrace={} mainHand={} offHand={} materialRedirectMatched={} iceBranch={}",
                cir.getReturnValue(),
                QuickCraftConfigs.isEasyPlaceIceOverSourceWaterAllowed(),
                target,
                worldState,
                schematicState,
                schematicTrace,
                genericTrace,
                client.player == null ? "none" : client.player.getMainHandStack().getItem(),
                client.player == null ? "none" : client.player.getOffHandStack().getItem(),
                quickcraft$materialRedirectMatched,
                quickcraft$iceBranchDiagnostic
        );
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
}
