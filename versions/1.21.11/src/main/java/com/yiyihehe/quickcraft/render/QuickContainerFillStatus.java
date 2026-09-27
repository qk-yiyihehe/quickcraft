package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.util.math.ColorHelper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps short-lived world markers for container copy and projection fill results. */
public final class QuickContainerFillStatus {
    private static final int RESULT_LIFETIME_TICKS = 200;
    private static final int MAX_MARKERS = 24;
    private static final double MAX_RENDER_DISTANCE_SQUARED = 32.0 * 32.0;
    private static final Map<Target, Marker> MARKERS = new LinkedHashMap<>();

    private static ClientWorld markerWorld;
    private static long tick;

    private QuickContainerFillStatus() {
    }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickContainerFillStatus::onClientTick);
        HudRenderCallback.EVENT.register((context, tickCounter) -> renderHud(context));
    }

    public static void begin(MinecraftClient client, HitResult hitResult) {
        if (!isAvailable(client)) {
            return;
        }
        if (client.world != markerWorld) {
            MARKERS.clear();
            markerWorld = client.world;
        }
        Target target = Target.from(client.world, hitResult);
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

    public static void progress(MinecraftClient client, HitResult hitResult, int pendingIssues) {
        if (!isAvailable(client)) {
            return;
        }
        Marker marker = MARKERS.get(Target.from(client.world, hitResult));
        if (marker == null || marker.status != Status.FILLING) {
            return;
        }
        marker.pendingIssues = pendingIssues;
    }

    public static void finish(MinecraftClient client, HitResult hitResult, int pendingIssues) {
        if (!isAvailable(client)) {
            return;
        }
        Marker marker = MARKERS.get(Target.from(client.world, hitResult));
        if (marker == null) {
            return;
        }
        marker.pendingIssues = pendingIssues;
        marker.status = pendingIssues == 0 ? Status.COMPLETE : Status.PARTIAL;
        marker.expiresAt = tick + RESULT_LIFETIME_TICKS;
    }

    public static void stop(MinecraftClient client, HitResult hitResult, boolean failed) {
        if (!isAvailable(client)) {
            return;
        }
        Marker marker = MARKERS.get(Target.from(client.world, hitResult));
        if (marker == null || marker.status != Status.FILLING) {
            return;
        }
        marker.status = failed ? Status.FAILED : marker.pendingIssues > 0 ? Status.PARTIAL : Status.STOPPED;
        marker.expiresAt = tick + RESULT_LIFETIME_TICKS;
    }

    public static boolean sameTarget(MinecraftClient client, HitResult first, HitResult second) {
        if (client == null || client.world == null) {
            return false;
        }
        Target target = Target.from(client.world, first);
        return target != null && target.equals(Target.from(client.world, second));
    }

    private static void onClientTick(MinecraftClient client) {
        tick++;
        if (client.world != markerWorld || client.player == null || !QuickCraftConfigs.areContainerFillStatusOutlinesVisible()) {
            MARKERS.clear();
            markerWorld = client.world;
            return;
        }
        MARKERS.entrySet().removeIf(entry -> entry.getValue().expiresAt <= tick);
    }

    private static boolean isAvailable(MinecraftClient client) {
        return client != null
                && client.world != null
                && client.player != null
                && QuickCraftConfigs.areContainerFillStatusOutlinesVisible();
    }

    public static void renderWorld(MinecraftClient client, Camera camera) {
        if (!isAvailable(client) || MARKERS.isEmpty()) {
            return;
        }
        Vec3d cameraPos = camera.getCameraPos();
        MatrixStack matrices = new MatrixStack();
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        for (Map.Entry<Target, Marker> entry : MARKERS.entrySet()) {
            Box box = entry.getKey().box(client.world);
            if (box == null || box.getCenter().squaredDistanceTo(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }
            Status status = entry.getValue().status;
            // 0.2 个方块像素等于 0.0125 格；仅微抬底边，其余边略微外移以避免贴面闪烁。
            Box outline = new Box(
                    box.minX - 0.01, box.minY + 0.0125, box.minZ - 0.01,
                    box.maxX + 0.01, box.maxY + 0.01, box.maxZ + 0.01
            );
            VertexRendering.drawOutline(matrices, consumers.getBuffer(RenderLayers.lines()),
                    VoxelShapes.cuboid(outline), 0, 0, 0,
                    ColorHelper.fromFloats(0.95F, status.red, status.green, status.blue),
                    client.getWindow().getMinimumLineWidth());
        }
        matrices.pop();
        consumers.draw(RenderLayers.lines());
    }

    private static void renderHud(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isAvailable(client) || client.currentScreen != null || client.options.hudHidden) {
            return;
        }
        Marker marker = MARKERS.get(Target.from(client.world, client.crosshairTarget));
        if (marker == null) {
            return;
        }
        Text label = switch (marker.status) {
            case FILLING -> marker.pendingIssues > 0
                    ? Text.translatable("quickcraft.container_fill_status.progress", marker.pendingIssues)
                    : Text.translatable("quickcraft.container_fill_status.filling");
            case COMPLETE -> Text.translatable("quickcraft.container_fill_status.complete");
            case PARTIAL -> Text.translatable("quickcraft.container_fill_status.partial", marker.pendingIssues);
            case STOPPED -> Text.translatable("quickcraft.container_fill_status.stopped");
            case FAILED -> Text.translatable("quickcraft.container_fill_status.failed");
        };
        int x = (context.getScaledWindowWidth() - client.textRenderer.getWidth(label)) / 2;
        int y = context.getScaledWindowHeight() / 2 + 20;
        context.drawTextWithShadow(client.textRenderer, label, x, y, marker.status.textColor);
    }

    private record Target(BlockPos blockPos, int entityId) {
        private static Target from(ClientWorld world, HitResult hitResult) {
            if (hitResult instanceof BlockHitResult blockHitResult) {
                BlockPos pos = blockHitResult.getBlockPos();
                BlockState state = world.getBlockState(pos);
                if (state.getBlock() instanceof ChestBlock
                        && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                    BlockPos neighborPos = pos.offset(ChestBlock.getFacing(state));
                    BlockState neighbor = world.getBlockState(neighborPos);
                    if (neighbor.getBlock() == state.getBlock()
                            && neighbor.get(ChestBlock.FACING) == state.get(ChestBlock.FACING)
                            && neighbor.get(ChestBlock.CHEST_TYPE) == state.get(ChestBlock.CHEST_TYPE).getOpposite()
                            && neighborPos.asLong() < pos.asLong()) {
                        pos = neighborPos;
                    }
                }
                return new Target(pos.toImmutable(), -1);
            }
            if (hitResult instanceof EntityHitResult entityHitResult) {
                return new Target(null, entityHitResult.getEntity().getId());
            }
            return null;
        }

        private Box box(ClientWorld world) {
            if (blockPos != null) {
                BlockState state = world.getBlockState(blockPos);
                if (state.isAir()) {
                    return null;
                }
                Box box = new Box(blockPos);
                if (state.getBlock() instanceof ChestBlock
                        && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                    BlockPos neighborPos = blockPos.offset(ChestBlock.getFacing(state));
                    BlockState neighbor = world.getBlockState(neighborPos);
                    if (neighbor.getBlock() == state.getBlock()
                            && neighbor.get(ChestBlock.FACING) == state.get(ChestBlock.FACING)
                            && neighbor.get(ChestBlock.CHEST_TYPE) == state.get(ChestBlock.CHEST_TYPE).getOpposite()) {
                        box = box.union(new Box(neighborPos));
                    }
                }
                return box;
            }
            Entity entity = world.getEntityById(entityId);
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
