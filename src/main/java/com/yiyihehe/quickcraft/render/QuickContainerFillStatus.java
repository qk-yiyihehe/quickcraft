package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps short-lived world markers for container copy and projection fill results. */
public final class QuickContainerFillStatus {
    private static final int RESULT_LIFETIME_TICKS = 200;
    private static final int MAX_MARKERS = 24;
    private static final double MAX_RENDER_DISTANCE_SQUARED = 32.0 * 32.0;
    private static final Map<Target, Marker> MARKERS = new LinkedHashMap<>();

    private static ClientLevel markerWorld;
    private static long tick;

    private QuickContainerFillStatus() {
    }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickContainerFillStatus::onClientTick);
        LevelRenderEvents.BEFORE_GIZMOS.register(QuickContainerFillStatus::renderWorld);
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
                Identifier.fromNamespaceAndPath("quickcraft", "container_fill_status"),
                (graphics, tickCounter) -> renderHud(graphics));
    }

    public static void begin(Minecraft client, HitResult hitResult) {
        if (!isAvailable(client)) {
            return;
        }
        if (client.level != markerWorld) {
            MARKERS.clear();
            markerWorld = client.level;
        }
        Target target = Target.from(client.level, hitResult);
        if (target == null) {
            return;
        }
        MARKERS.remove(target);
        MARKERS.put(target, new Marker(Status.FILLING, 0, Long.MAX_VALUE));
        while (MARKERS.size() > MAX_MARKERS) {
            Target oldest = MARKERS.keySet().iterator().next();
            MARKERS.remove(oldest);
        }
    }

    public static void progress(Minecraft client, HitResult hitResult, int pendingIssues) {
        if (!isAvailable(client)) {
            return;
        }
        Marker marker = MARKERS.get(Target.from(client.level, hitResult));
        if (marker == null || marker.status != Status.FILLING) {
            return;
        }
        marker.pendingIssues = pendingIssues;
    }

    public static void finish(Minecraft client, HitResult hitResult, int pendingIssues) {
        if (!isAvailable(client)) {
            return;
        }
        Marker marker = MARKERS.get(Target.from(client.level, hitResult));
        if (marker == null) {
            return;
        }
        marker.pendingIssues = pendingIssues;
        marker.status = pendingIssues == 0 ? Status.COMPLETE : Status.PARTIAL;
        marker.expiresAt = tick + RESULT_LIFETIME_TICKS;
    }

    public static void stop(Minecraft client, HitResult hitResult, boolean failed) {
        if (!isAvailable(client)) {
            return;
        }
        Marker marker = MARKERS.get(Target.from(client.level, hitResult));
        if (marker == null || marker.status != Status.FILLING) {
            return;
        }
        marker.status = failed ? Status.FAILED : marker.pendingIssues > 0 ? Status.PARTIAL : Status.STOPPED;
        marker.expiresAt = tick + RESULT_LIFETIME_TICKS;
    }

    public static boolean sameTarget(Minecraft client, HitResult first, HitResult second) {
        if (client == null || client.level == null) {
            return false;
        }
        Target target = Target.from(client.level, first);
        return target != null && target.equals(Target.from(client.level, second));
    }

    private static void onClientTick(Minecraft client) {
        tick++;
        if (client.level != markerWorld || client.player == null || !QuickCraftConfigs.areContainerFillStatusOutlinesVisible()) {
            MARKERS.clear();
            markerWorld = client.level;
            return;
        }
        MARKERS.entrySet().removeIf(entry -> entry.getValue().expiresAt <= tick);
    }

    private static boolean isAvailable(Minecraft client) {
        return client != null
                && client.level != null
                && client.player != null
                && QuickCraftConfigs.areContainerFillStatusOutlinesVisible();
    }

    private static void renderWorld(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!isAvailable(client) || MARKERS.isEmpty()) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        // 26.2 的 gizmo 收集发生在 BEFORE_GIZMOS 后，必须在此阶段提交世界坐标轮廓。
        try (Gizmos.TemporaryCollection ignored = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
            for (Map.Entry<Target, Marker> entry : MARKERS.entrySet()) {
                AABB box = entry.getKey().box(client.level);
                if (box == null || box.getCenter().distanceToSqr(camera) > MAX_RENDER_DISTANCE_SQUARED) {
                    continue;
                }
                Status status = entry.getValue().status;
                // 0.2 个方块像素等于 0.0125 格；仅微抬底边，其余边略微外移以避免贴面闪烁。
                AABB outline = new AABB(
                        box.minX - 0.01, box.minY + 0.0125, box.minZ - 0.01,
                        box.maxX + 0.01, box.maxY + 0.01, box.maxZ + 0.01
                );
                int color = 0xF2000000
                        | ((int) (status.red * 255) << 16)
                        | ((int) (status.green * 255) << 8)
                        | (int) (status.blue * 255);
                Gizmos.cuboid(outline, GizmoStyle.stroke(color, 1.0F));
            }
        }
    }

    private static void renderHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!isAvailable(client) || client.gui.screen() != null || client.gui.hud.isHidden()) {
            return;
        }
        Marker marker = MARKERS.get(Target.from(client.level, client.hitResult));
        if (marker == null) {
            return;
        }
        Component label = switch (marker.status) {
            case FILLING -> marker.pendingIssues > 0
                    ? Component.translatable("quickcraft.container_fill_status.progress", marker.pendingIssues)
                    : Component.translatable("quickcraft.container_fill_status.filling");
            case COMPLETE -> Component.translatable("quickcraft.container_fill_status.complete");
            case PARTIAL -> Component.translatable("quickcraft.container_fill_status.partial", marker.pendingIssues);
            case STOPPED -> Component.translatable("quickcraft.container_fill_status.stopped");
            case FAILED -> Component.translatable("quickcraft.container_fill_status.failed");
        };
        int x = (graphics.guiWidth() - client.font.width(label)) / 2;
        int y = graphics.guiHeight() / 2 + 20;
        graphics.text(client.font, label, x, y, marker.status.textColor, true);
    }

    private record Target(BlockPos blockPos, int entityId) {
        private static Target from(ClientLevel world, HitResult hitResult) {
            if (hitResult instanceof BlockHitResult blockHitResult) {
                BlockPos pos = blockHitResult.getBlockPos();
                BlockState state = world.getBlockState(pos);
                if (state.getBlock() instanceof ChestBlock
                        && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    BlockPos neighborPos = ChestBlock.getConnectedBlockPos(pos, state);
                    BlockState neighbor = world.getBlockState(neighborPos);
                    if (neighbor.getBlock() == state.getBlock()
                            && neighbor.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING)
                            && neighbor.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE).getOpposite()
                            && neighborPos.asLong() < pos.asLong()) {
                        pos = neighborPos;
                    }
                }
                return new Target(pos.immutable(), -1);
            }
            if (hitResult instanceof EntityHitResult entityHitResult) {
                return new Target(null, entityHitResult.getEntity().getId());
            }
            return null;
        }

        private AABB box(ClientLevel world) {
            if (blockPos != null) {
                BlockState state = world.getBlockState(blockPos);
                if (state.isAir()) {
                    return null;
                }
                AABB box = new AABB(blockPos);
                if (state.getBlock() instanceof ChestBlock
                        && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    BlockPos neighborPos = ChestBlock.getConnectedBlockPos(blockPos, state);
                    BlockState neighbor = world.getBlockState(neighborPos);
                    if (neighbor.getBlock() == state.getBlock()
                            && neighbor.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING)
                            && neighbor.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE).getOpposite()) {
                        box = box.minmax(new AABB(neighborPos));
                    }
                }
                return box;
            }
            Entity entity = world.getEntity(entityId);
            return entity != null ? entity.getBoundingBox() : null;
        }
    }

    private static final class Marker {
        private Status status;
        private int pendingIssues;
        private long expiresAt;

        private Marker(Status status, int pendingIssues, long expiresAt) {
            this.status = status;
            this.pendingIssues = pendingIssues;
            this.expiresAt = expiresAt;
        }
    }

    private enum Status {
        FILLING(0.20F, 0.65F, 1.00F, 0xFF55B5FF),
        COMPLETE(0.30F, 0.95F, 0.35F, 0xFF76EE88),
        PARTIAL(1.00F, 0.75F, 0.15F, 0xFFFFD05E),
        STOPPED(0.65F, 0.70F, 0.75F, 0xFFB3BEC9),
        FAILED(1.00F, 0.30F, 0.30F, 0xFFFF7777);

        private final float red;
        private final float green;
        private final float blue;
        private final int textColor;

        Status(float red, float green, float blue, int textColor) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.textColor = textColor;
        }
    }
}
