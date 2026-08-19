package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从游戏内 Litematica 选区进入 QuickCraft 3D 查看页。
 * 这里只解析当前模式 1 选区，不参与网格、导出或剪贴板生命周期。
 */
public final class QuickLitematicaSelectionPreview {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickLitematicaSelectionPreview.class);
    // 选区快照需要逐方块读取客户端世界，先限制总体积以免误圈超大范围长期占用预览线程。
    private static final long MAX_SELECTION_CAPTURE_VOLUME = 8_000_000L;

    private QuickLitematicaSelectionPreview() {
    }

    public static void bindHotkey() {
        QuickCraftConfigs.Hotkeys.OPEN_LITEMATICA_AREA_3D_PREVIEW.getKeybind()
                .setCallback(QuickLitematicaSelectionPreview::handleHotkey);
    }

    private static boolean handleHotkey(KeyAction action, IKeybind keybind) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (action != KeyAction.PRESS
                || client.player == null
                || client.world == null
                || client.currentScreen != null) {
            return false;
        }
        if (!QuickCraftConfigs.isLitematica3DPreviewEnabled()) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.preview_3d.disabled");
            return true;
        }
        if (!QuickLitematicaPreview3D.prepare3DPreview()) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.preview_3d.shader_disabled");
            return true;
        }

        if (DataManager.getToolMode() != ToolMode.AREA_SELECTION) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.preview_3d.requires_area_selection");
            return true;
        }
        return openAreaSelection(client);
    }

    private static boolean openAreaSelection(MinecraftClient client) {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null || selection.getAllSubRegionBoxes().stream().noneMatch(QuickLitematicaSelectionPreview::isComplete)) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.preview_3d.no_selection");
            return true;
        }
        if (selectionVolume(selection) > MAX_SELECTION_CAPTURE_VOLUME) {
            InfoUtils.printActionbarMessage("quickcraft.litematica.preview_3d.too_large");
            return true;
        }

        AreaSelection selectionSnapshot = selection.copy();
        String displayName = StringUtils.translate("quickcraft.litematica.preview_3d.area_title");
        String author = client.player.getName().getString();
        QuickLitematicaPreview3D.openGenerated(client.currentScreen, displayName, () ->
                captureSelection(client.world, selectionSnapshot, author)
        );
        return true;
    }

    private static LitematicaSchematic captureSelection(
            ClientWorld world,
            AreaSelection selection,
            String author
    ) {
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(selection, author);
        if (schematic == null) {
            return null;
        }

        var boxes = selection.getAllSubRegions();
        LitematicaSchematic.SchematicSaveInfo saveInfo =
                new LitematicaSchematic.SchematicSaveInfo(false, false, true, false);
        int loadedChunks = 0;
        for (ChunkPos chunkPos : PositionUtils.getTouchedChunks(boxes)) {
            if (!world.getChunkManager().isChunkLoaded(chunkPos.x, chunkPos.z)) {
                continue;
            }

            schematic.takeBlocksFromWorldWithinChunk(
                    world,
                    PositionUtils.getBoxesWithinChunk(chunkPos.x, chunkPos.z, boxes),
                    boxes,
                    saveInfo
            );
            loadedChunks++;
        }

        int blockCount = schematic.getTotalBlocksReadFromWorld();
        schematic.getMetadata().setTotalBlocks(blockCount);
        if (blockCount == 0) {
            LOGGER.warn(
                    "Litematica area preview captured no world blocks from {} loaded client chunks",
                    loadedChunks
            );
            return null;
        }
        return schematic;
    }

    private static boolean isComplete(Box box) {
        return box.getPos1() != null && box.getPos2() != null;
    }

    private static long selectionVolume(AreaSelection selection) {
        long total = 0L;
        for (Box box : selection.getAllSubRegionBoxes()) {
            if (!isComplete(box)) {
                continue;
            }

            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();
            long sizeX = Math.abs((long) pos1.getX() - pos2.getX()) + 1L;
            long sizeY = Math.abs((long) pos1.getY() - pos2.getY()) + 1L;
            long sizeZ = Math.abs((long) pos1.getZ() - pos2.getZ()) + 1L;
            long volume = sizeX * sizeY * sizeZ;
            if (volume > MAX_SELECTION_CAPTURE_VOLUME || total > MAX_SELECTION_CAPTURE_VOLUME - volume) {
                return MAX_SELECTION_CAPTURE_VOLUME + 1L;
            }
            total += volume;
        }
        return total;
    }
}
