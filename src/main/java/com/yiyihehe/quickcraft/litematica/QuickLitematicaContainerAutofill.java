package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 根据当前投影中对应位置的容器内容，自动填充玩家右键打开的实际容器。
 */
public final class QuickLitematicaContainerAutofill implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final Identifier QUICK_SHULKER_BUNDLE_PACKET = Identifier.fromNamespaceAndPath("quickshulker", "quick_bundleheld_packet");

    private boolean lastUseDown;
    private BlockPos pendingContainerPos;
    private int pendingTicks;

    @Override
    public void onInitializeClient() {
        QuickLitematicaPreview3D.registerSpecialRenderer();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isLitematicaContainerAutofillEnabled()) {
            lastUseDown = false;
            pendingContainerPos = null;
            pendingTicks = 0;
            return;
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(Minecraft client) {
        if (client.player == null || client.level == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.keyUse);
        if (useDown && !lastUseDown && client.gui.screen() == null) {
            BlockHitResult hitResult = getLookedAtBlock(client);
            if (hitResult != null && shouldHandleTarget(client, hitResult)) {
                BlockPos pos = hitResult.getBlockPos();
                pendingContainerPos = pos.immutable();
                pendingTicks = 0;
            }
        }
        lastUseDown = useDown;
    }

    private void processPendingOpen(Minecraft client) {
        if (pendingContainerPos == null) {
            return;
        }

        pendingTicks++;
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                pendingContainerPos = null;
                pendingTicks = 0;
            }
            return;
        }

        BlockPos pos = pendingContainerPos;
        pendingContainerPos = null;
        pendingTicks = 0;

        QuickContainerCopy.TemplateSnapshot snapshot = getTemplateSnapshot(client, pos);
        if (snapshot == null) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.litematica_autofill.no_container_content"));
            closeCurrentScreen(client);
            return;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!QuickContainerCopy.canApplyTemplateSnapshot(handler, snapshot)) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.container_copy.projection_type_mismatch"));
            closeCurrentScreen(client);
            return;
        }

        QuickContainerCopy.applyTemplateSnapshot(client, handler, snapshot, shouldUseQuickShulker());
        closeCurrentScreen(client);
    }

    private BlockHitResult getLookedAtBlock(Minecraft client) {
        HitResult hitResult = client.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || blockHitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        return blockHitResult;
    }

    private boolean shouldHandleTarget(Minecraft client, BlockHitResult hitResult) {
        QuickContainerCopy.TemplateSnapshot snapshot = getTemplateSnapshot(client, hitResult.getBlockPos());
        if (snapshot == null) {
            return false;
        }

        QuickContainerCopy.PublicContainerType actualType = QuickContainerCopy.getPublicContainerType(client, hitResult);
        return actualType == snapshot.type();
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshot(Minecraft client, BlockPos pos) {
        if (!FabricLoader.getInstance().isModLoaded("litematica") || client.level == null) {
            return null;
        }

        Level world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        QuickContainerCopy.TemplateSnapshot snapshot =
                QuickLitematicaContainerVerifier.getTemplateSnapshotAt(world != null ? world : client.level, pos);
        return QuickLitematicaContainerReplacements.applyToSnapshot(snapshot);
    }

    private boolean shouldUseQuickShulker() {
        if (!QuickCraftConfigs.isLitematicaContainerAutofillWithQuickShulkerEnabled()
                || !FabricLoader.getInstance().isModLoaded("quickshulker")) {
            return false;
        }

        try {
            return ClientPlayNetworking.canSend(QUICK_SHULKER_BUNDLE_PACKET);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void closeCurrentScreen(Minecraft client) {
        QuickLitematicaContainerVerifier.clearCurrentHandledScreenBinding();
        if (client.player != null) {
            client.player.closeContainer();
        }
    }

    private void sendStatusMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendOverlayMessage(message);
        }
    }

    public static boolean shouldHandleCurrentTarget(Minecraft client) {
        if (!QuickCraftConfigs.isLitematicaContainerAutofillEnabled()
                || client == null
                || client.player == null
                || client.level == null
                || client.gui.screen() != null) {
            return false;
        }

        QuickLitematicaContainerAutofill autofill = new QuickLitematicaContainerAutofill();
        BlockHitResult hitResult = autofill.getLookedAtBlock(client);
        return hitResult != null && autofill.shouldHandleTarget(client, hitResult);
    }
}
