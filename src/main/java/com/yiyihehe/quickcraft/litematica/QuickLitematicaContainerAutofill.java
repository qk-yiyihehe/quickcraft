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
 * Litematica 容器自动填充的客户端入口。
 *
 * <p>本类只负责“玩家右键真实容器 -> 等待原版界面打开 -> 读取同坐标投影容器模板 -> 套用模板 -> 关闭界面”。
 * 模板读取和容器点击计划复用 {@link QuickLitematicaContainerVerifier} 与 {@link QuickContainerCopy}，
 * 不在这里重新解析投影或实现槽位搬运。</p>
 */
public final class QuickLitematicaContainerAutofill implements ClientModInitializer {
    // 右键后最多等 20 tick。超过 1 秒仍没打开容器，认为本次交互被服务端或其它 mod 拦截。
    private static final int OPEN_TIMEOUT_TICKS = 20;
    // Quick Shulker 的公开通道；可发送时才允许自动填充潜影盒走它的服务端打包逻辑。
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

    /**
     * 等待原版容器界面真正打开后再填充。
     *
     * <p>右键发生时还拿不到当前 {@link ScreenHandler}，必须跨 tick 等服务端同步界面；
     * 如果模板缺失或容器类型不匹配，会关闭刚打开的界面，避免玩家继续在错误容器里自动点击。</p>
     */
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

        // Litematica 自己的 best world 可能是投影上下文；没有时才回退到当前客户端世界。
        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        QuickContainerCopy.TemplateSnapshot snapshot =
                QuickLitematicaContainerVerifier.getTemplateSnapshotAt(world != null ? world : client.world, pos);
        return QuickLitematicaContainerReplacements.applyToSnapshot(snapshot);
    }

    /**
     * Quick Shulker 只作为可选加速路径。
     *
     * <p>通道不存在或服务端不允许发送时必须回退普通槽位点击，避免把自动填充绑定到另一个 mod。</p>
     */
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

        // 这里只复用无状态判断，真正的 pending 生命周期仍由 ClientTickEvents 注册的实例维护。
        QuickLitematicaContainerAutofill autofill = new QuickLitematicaContainerAutofill();
        BlockHitResult hitResult = autofill.getLookedAtBlock(client);
        return hitResult != null && autofill.shouldHandleTarget(client, hitResult);
    }
}
