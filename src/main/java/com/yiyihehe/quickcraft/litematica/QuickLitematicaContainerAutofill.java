package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.compat.QuickLargeBarrel;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.render.QuickContainerFillStatus;
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

import java.util.concurrent.atomic.AtomicReference;

/**
 * 按住填充热键时，根据当前投影中对应位置的容器内容填充实际容器。
 */
public final class QuickLitematicaContainerAutofill implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final Identifier QUICK_SHULKER_BUNDLE_PACKET = Identifier.of("quickshulker", "quick_bundleheld_packet");
    private static final AtomicReference<QuickLitematicaContainerAutofill> ACTIVE = new AtomicReference<>();

    private boolean lastUseDown;
    private BlockPos pendingContainerPos;
    private BlockHitResult pendingFillTarget;
    private QuickContainerFillStatus.Handle pendingFillStatus;
    private int pendingTicks;

    @Override
    public void onInitializeClient() {
        // 1.21-.5 的 special renderer 适配为空，资源监听不能依赖该版本钩子。
        QuickLitematicaPreviewResources.initialize();
        QuickLitematicaMaterialAccess.registerSpecialRenderer();
        QuickLitematicaPreview3D.initializeLifecycle();
        QuickLitematicaContainerScreenBinding.initializeLifecycle();
        ACTIVE.set(this);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static void cancelPendingForContinuousFill(MinecraftClient client) {
        QuickLitematicaContainerAutofill active = ACTIVE.get();
        if (active == null || active.pendingContainerPos == null) {
            return;
        }
        QuickContainerFillStatus.stop(client, active.pendingFillStatus, false);
        active.pendingContainerPos = null;
        active.pendingFillTarget = null;
        active.pendingFillStatus = null;
        active.pendingTicks = 0;
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isLitematicaContainerAutofillEnabled()) {
            lastUseDown = false;
            pendingContainerPos = null;
            QuickContainerFillStatus.stop(client, pendingFillStatus, true);
            pendingFillTarget = null;
            pendingFillStatus = null;
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

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.useKey);
        if (useDown && !lastUseDown
                && !QuickContainerCopy.shouldSuppressContinuousFillUseInput()
                && client.currentScreen == null) {
            BlockHitResult hitResult = getLookedAtBlock(client);
            boolean ordinaryTarget = hitResult != null && hasOrdinaryTemplate(client, hitResult);
            if (hitResult != null && (ordinaryTarget
                    ? QuickContainerCopy.requireContainerFillHotkey(client)
                    : QuickContainerCopy.isContainerFillHotkeyDown(client) && hasLargeBarrelCandidate(client, hitResult))) {
                BlockPos pos = hitResult.getBlockPos();
                pendingContainerPos = pos.toImmutable();
                pendingFillTarget = hitResult;
                pendingTicks = 0;
                pendingFillStatus = ordinaryTarget ? QuickContainerFillStatus.begin(client, hitResult) : null;
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
                QuickContainerFillStatus.stop(client, pendingFillStatus, true);
                pendingContainerPos = null;
                pendingFillTarget = null;
                pendingFillStatus = null;
                pendingTicks = 0;
            }
            return;
        }

        BlockPos pos = pendingContainerPos;
        BlockHitResult target = pendingFillTarget;
        QuickContainerFillStatus.Handle fillStatus = pendingFillStatus;
        pendingContainerPos = null;
        pendingFillTarget = null;
        pendingFillStatus = null;
        pendingTicks = 0;

        ScreenHandler handler = screen.getScreenHandler();
        QuickContainerCopy.PublicContainerType targetType = QuickContainerCopy.getPublicContainerType(client, target);
        boolean barrelTarget = targetType == QuickContainerCopy.PublicContainerType.BARREL;
        boolean largeBarrel = barrelTarget && QuickLargeBarrel.hasSixRows(handler);
        QuickContainerCopy.TemplateSnapshot snapshot = getTemplateSnapshot(client, pos, largeBarrel);
        QuickContainerCopy.PublicContainerType menuType = largeBarrel ? QuickContainerCopy.PublicContainerType.LARGE_BARREL : targetType;
        if (barrelTarget && !largeBarrel && (snapshot == null || snapshot.type() != menuType)) {
            // 桶底相贴时仍可能收到普通木桶菜单。保留这次正常开桶。
            QuickContainerFillStatus.stop(client, fillStatus, false);
            return;
        }
        if (snapshot == null) {
            QuickContainerFillStatus.stop(client, fillStatus, true);
            sendStatusMessage(client, Text.translatable("quickcraft.message.litematica_autofill.no_container_content"));
            closeCurrentScreen(client);
            return;
        }

        if (snapshot.type() != menuType || !QuickContainerCopy.canApplyTemplateSnapshot(handler, snapshot)) {
            QuickContainerFillStatus.stop(client, fillStatus, true);
            sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.projection_type_mismatch"));
            closeCurrentScreen(client);
            return;
        }

        QuickContainerCopy.applyTemplateSnapshot(client, handler, snapshot, shouldUseQuickShulker(), target, fillStatus);
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
        return hasOrdinaryTemplate(client, hitResult)
                || (QuickContainerCopy.isContainerFillHotkeyDown(client) && hasLargeBarrelCandidate(client, hitResult));
    }

    private boolean hasOrdinaryTemplate(MinecraftClient client, BlockHitResult hitResult) {
        QuickContainerCopy.PublicContainerType actualType = QuickContainerCopy.getPublicContainerType(client, hitResult);
        QuickContainerCopy.TemplateSnapshot snapshot = getTemplateSnapshot(client, hitResult.getBlockPos());
        return snapshot != null && actualType == snapshot.type();
    }

    private boolean hasLargeBarrelCandidate(MinecraftClient client, BlockHitResult hitResult) {
        return QuickContainerCopy.getPublicContainerType(client, hitResult) == QuickContainerCopy.PublicContainerType.BARREL
                && getTemplateSnapshot(client, hitResult.getBlockPos(), true) != null;
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshot(MinecraftClient client, BlockPos pos) {
        return getTemplateSnapshot(client, pos, false);
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshot(MinecraftClient client, BlockPos pos, boolean largeBarrel) {
        if (!FabricLoader.getInstance().isModLoaded("litematica") || client.world == null) {
            return null;
        }

        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        QuickContainerCopy.TemplateSnapshot snapshot =
                QuickLitematicaContainerVerifier.getTemplateSnapshotAt(world != null ? world : client.world, pos, largeBarrel);
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
