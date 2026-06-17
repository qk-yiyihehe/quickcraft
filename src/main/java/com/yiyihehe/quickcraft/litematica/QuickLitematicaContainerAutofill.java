package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 根据当前投影中对应位置的容器内容，自动填充玩家右键打开的实际容器。
 */
public final class QuickLitematicaContainerAutofill implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final Identifier QUICK_SHULKER_BUNDLE_PACKET = Identifier.of("quickshulker", "quick_bundleheld_packet");

    private boolean lastUseDown;
    private BlockPos pendingContainerPos;
    private int pendingTicks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isLitematicaContainerAutofillEnabled()) {
            lastUseDown = false;
            pendingContainerPos = null;
            pendingTicks = 0;
            return;
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.useKey);
        if (useDown && !lastUseDown && client.currentScreen == null) {
            BlockHitResult hitResult = getLookedAtBlock(client);
            if (hitResult != null && shouldHandleTarget(client, hitResult)) {
                BlockPos pos = hitResult.getBlockPos();
                pendingContainerPos = pos.toImmutable();
                pendingTicks = 0;
            }
        }
        lastUseDown = useDown;
    }

    private void processPendingOpen(MinecraftClient client) {
        if (pendingContainerPos == null) {
            return;
        }

        pendingTicks++;
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
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
            sendStatusMessage(client, Text.translatable("quickcraft.message.litematica_autofill.no_container_content"));
            closeCurrentScreen(client);
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!QuickContainerCopy.canApplyTemplateSnapshot(handler, snapshot)) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.projection_type_mismatch"));
            closeCurrentScreen(client);
            return;
        }

        QuickContainerCopy.applyTemplateSnapshot(client, handler, snapshot, shouldUseQuickShulker());
        closeCurrentScreen(client);
    }

    private BlockHitResult getLookedAtBlock(MinecraftClient client) {
        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || blockHitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        return blockHitResult;
    }

    private boolean shouldHandleTarget(MinecraftClient client, BlockHitResult hitResult) {
        QuickContainerCopy.TemplateSnapshot snapshot = getTemplateSnapshot(client, hitResult.getBlockPos());
        if (snapshot == null) {
            return false;
        }

        QuickContainerCopy.PublicContainerType actualType = QuickContainerCopy.getPublicContainerType(client, hitResult);
        return actualType == snapshot.type();
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshot(MinecraftClient client, BlockPos pos) {
        if (!FabricLoader.getInstance().isModLoaded("litematica") || client.world == null) {
            return null;
        }

        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        QuickContainerCopy.TemplateSnapshot snapshot =
                QuickLitematicaContainerVerifier.getTemplateSnapshotAt(world != null ? world : client.world, pos);
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

    private void closeCurrentScreen(MinecraftClient client) {
        QuickLitematicaContainerVerifier.clearCurrentHandledScreenBinding();
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
    }

    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    public static boolean shouldHandleCurrentTarget(MinecraftClient client) {
        if (!QuickCraftConfigs.isLitematicaContainerAutofillEnabled()
                || client == null
                || client.player == null
                || client.world == null
                || client.currentScreen != null) {
            return false;
        }

        QuickLitematicaContainerAutofill autofill = new QuickLitematicaContainerAutofill();
        BlockHitResult hitResult = autofill.getLookedAtBlock(client);
        return hitResult != null && autofill.shouldHandleTarget(client, hitResult);
    }
}
