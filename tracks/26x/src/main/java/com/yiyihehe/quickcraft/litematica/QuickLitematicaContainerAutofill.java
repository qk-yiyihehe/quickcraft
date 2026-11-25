package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.compat.QuickLargeBarrel;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.render.QuickContainerFillStatus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 按住填充热键时，根据当前投影中对应位置的容器内容填充实际容器。
 */
public final class QuickLitematicaContainerAutofill implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final Identifier QUICK_SHULKER_BUNDLE_PACKET = Identifier.fromNamespaceAndPath(
            "quickshulker",
            "quick_bundleheld_packet"
    );
    private static final AtomicReference<QuickLitematicaContainerAutofill> ACTIVE = new AtomicReference<>();

    private boolean lastUseDown;
    private BlockPos pendingContainerPos;
    private BlockHitResult pendingFillTarget;
    private QuickContainerFillStatus.Handle pendingFillStatus;
    private int pendingTicks;

    @Override
    public void onInitializeClient() {
        QuickLitematicaPreview3D.registerSpecialRenderer();
        QuickLitematicaPreview3D.initializeLifecycle();
        QuickLitematicaContainerScreenBinding.initializeLifecycle();
        ACTIVE.set(this);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static void cancelPendingForContinuousFill(Minecraft client) {
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

    private void onClientTick(Minecraft client) {
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

    private void handleUseAttempt(Minecraft client) {
        if (client.player == null || client.level == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.keyUse);
        if (useDown && !lastUseDown && !QuickContainerCopy.shouldSuppressContinuousFillUseInput() && currentScreen(
                client
        ) == null) {
            BlockHitResult hitResult = getLookedAtBlock(client);
            boolean ordinaryTarget = hitResult != null && hasOrdinaryTemplate(client, hitResult);
            if (hitResult != null && (ordinaryTarget ? QuickContainerCopy.requireContainerFillHotkey(client)
                    : QuickContainerCopy.isContainerFillHotkeyDown(client) && hasLargeBarrelCandidate(
                            client,
                            hitResult
                    ))) {
                BlockPos pos = hitResult.getBlockPos();
                pendingContainerPos = pos.immutable();
                pendingFillTarget = hitResult;
                pendingTicks = 0;
                pendingFillStatus = ordinaryTarget ? QuickContainerFillStatus.begin(client, hitResult) : null;
            }
        }
        lastUseDown = useDown;
    }

    private void processPendingOpen(Minecraft client) {
        if (pendingContainerPos == null) {
            return;
        }

        pendingTicks++;
        if (!(currentScreen(client) instanceof AbstractContainerScreen<?> screen)) {
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

        AbstractContainerMenu handler = screen.getMenu();
        QuickContainerCopy.PublicContainerType targetType = QuickContainerCopy.getPublicContainerType(client, target);
        boolean barrelTarget = targetType == QuickContainerCopy.PublicContainerType.BARREL;
        boolean largeBarrel = barrelTarget && QuickLargeBarrel.hasSixRows(handler);
        QuickContainerCopy.TemplateSnapshot snapshot = getTemplateSnapshot(client, pos, largeBarrel);
        QuickContainerCopy.PublicContainerType menuType = largeBarrel
                ? QuickContainerCopy.PublicContainerType.LARGE_BARREL
                : targetType;
        if (barrelTarget && !largeBarrel && (snapshot == null || snapshot.type() != menuType)) {
            // 桶底相贴时仍可能收到普通木桶菜单。保留这次正常开桶。
            QuickContainerFillStatus.stop(client, fillStatus, false);
            return;
        }
        if (snapshot == null) {
            QuickContainerFillStatus.stop(client, fillStatus, true);
            sendStatusMessage(
                    client,
                    Component.translatable("quickcraft.message.litematica_autofill.no_container_content")
            );
            closeCurrentScreen(client);
            return;
        }

        if (snapshot.type() != menuType || !QuickContainerCopy.canApplyTemplateSnapshot(handler, snapshot)) {
            QuickContainerFillStatus.stop(client, fillStatus, true);
            sendStatusMessage(
                    client,
                    Component.translatable("quickcraft.message.container_copy.projection_type_mismatch")
            );
            closeCurrentScreen(client);
            return;
        }

        QuickContainerCopy.applyTemplateSnapshot(
                client,
                handler,
                snapshot,
                shouldUseQuickShulker(),
                target,
                fillStatus
        );
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
        return hasOrdinaryTemplate(client, hitResult) || (QuickContainerCopy.isContainerFillHotkeyDown(client)
                && hasLargeBarrelCandidate(client, hitResult));
    }

    private boolean hasOrdinaryTemplate(Minecraft client, BlockHitResult hitResult) {
        QuickContainerCopy.PublicContainerType actualType = QuickContainerCopy.getPublicContainerType(
                client,
                hitResult
        );
        QuickContainerCopy.TemplateSnapshot snapshot = getTemplateSnapshot(client, hitResult.getBlockPos());
        return snapshot != null && actualType == snapshot.type();
    }

    private boolean hasLargeBarrelCandidate(Minecraft client, BlockHitResult hitResult) {
        return QuickContainerCopy.getPublicContainerType(
                client,
                hitResult
        ) == QuickContainerCopy.PublicContainerType.BARREL && getTemplateSnapshot(
                client,
                hitResult.getBlockPos(),
                true
        ) != null;
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshot(Minecraft client, BlockPos pos) {
        return getTemplateSnapshot(client, pos, false);
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshot(
            Minecraft client,
            BlockPos pos,
            boolean largeBarrel
    ) {
        if (!FabricLoader.getInstance().isModLoaded("litematica") || client.level == null) {
            return null;
        }

        Level world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        QuickContainerCopy.TemplateSnapshot snapshot = QuickLitematicaContainerVerifier.getTemplateSnapshotAt(
                world != null ? world : client.level,
                pos,
                largeBarrel
        );
        return QuickLitematicaContainerReplacements.applyToSnapshot(snapshot);
    }

    private boolean shouldUseQuickShulker() {
        if (!QuickCraftConfigs.isLitematicaContainerAutofillWithQuickShulkerEnabled() || !FabricLoader.getInstance()
                .isModLoaded("quickshulker")) {
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
        if (!QuickCraftConfigs.isLitematicaContainerAutofillEnabled() || client == null || client.player == null
                || client.level == null || currentScreen(client) != null) {
            return false;
        }

        QuickLitematicaContainerAutofill autofill = new QuickLitematicaContainerAutofill();
        BlockHitResult hitResult = autofill.getLookedAtBlock(client);
        return hitResult != null && autofill.shouldHandleTarget(client, hitResult);
    }

    private static Screen currentScreen(Minecraft client) {
        return QuickClientScreenAccess.currentScreen(client);
    }
}
