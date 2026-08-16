package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskSaveSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.HashSet;
import java.util.Set;

/**
 * 将模式 1 当前选区异步复制成 Litematica 内存投影。
 * 投影不写文件，持久化和世界切换清理由 Litematica 自己的管理器负责。
 */
public final class QuickLitematicaAreaClone {
    private static final Set<String> RESERVED_NAMES = new HashSet<>();

    private QuickLitematicaAreaClone() {
    }

    public static void bindHotkey() {
        QuickCraftConfigs.Hotkeys.CLONE_LITEMATICA_AREA.getKeybind()
                .setCallback(QuickLitematicaAreaClone::handleHotkey);
    }

    private static boolean handleHotkey(KeyAction action, IKeybind keybind) {
        Minecraft client = Minecraft.getInstance();
        if (action != KeyAction.PRESS
                || client.player == null
                || client.level == null
                || client.screen != null) {
            return false;
        }

        if (!QuickCraftConfigs.isLitematicaAreaCloneEnabled()) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.disabled");
            return true;
        }

        if (DataManager.getToolMode() != ToolMode.AREA_SELECTION) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.requires_area_selection");
            return true;
        }

        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (!hasCompleteSelection(selection)) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.no_selection");
            return true;
        }

        AreaSelection snapshot = selection.copy();
        String author = client.player.getName().getString();
        ClientLevel sourceLevel = client.level;
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(snapshot, author);
        if (schematic == null) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.failed");
            return true;
        }

        String name = reserveTemporaryName();
        schematic.getMetadata().setName(name);
        LitematicaSchematic.SchematicSaveInfo saveInfo =
                new LitematicaSchematic.SchematicSaveInfo(false, false);
        TaskSaveSchematic task = new TaskSaveSchematic(schematic, snapshot, saveInfo);
        task.disableCompletionMessage();
        task.setCompletionListener(new ICompletionListener() {
            @Override
            public void onTaskCompleted() {
                if (client.level != sourceLevel || client.player == null) {
                    releaseTemporaryName(name);
                    return;
                }

                // Litematica 0.19.60 的内存 TaskSaveSchematic 已在通知监听器前加入 SchematicHolder。
                releaseTemporaryName(name);
                SchematicPlacement placement = SchematicPlacement.createFor(
                        schematic,
                        snapshot.getEffectiveOrigin(),
                        name,
                        true,
                        true
                );
                placement.setShouldBeSaved(false);

                DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
                InfoUtils.printActionbarMessage(
                        "quickcraft.message.litematica.area_clone.created",
                        name
                );
            }

            @Override
            public void onTaskAborted() {
                releaseTemporaryName(name);
                if (client.level == sourceLevel && client.player != null) {
                    InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.aborted");
                }
            }
        });

        TaskScheduler.getServerInstanceIfExistsOrClient().scheduleTask(task, 10);
        InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.scheduled");
        return true;
    }

    private static synchronized String reserveTemporaryName() {
        String prefix = StringUtils.translate("quickcraft.litematica.area_clone.name_prefix");

        for (int id = 1; id < Integer.MAX_VALUE; id++) {
            String name = prefix + id;
            if (RESERVED_NAMES.contains(name)) {
                continue;
            }

            boolean alreadyLoaded = SchematicHolder.getInstance().getAllSchematics().stream()
                    .anyMatch(schematic -> name.equals(schematic.getMetadata().getName()));
            if (!alreadyLoaded) {
                RESERVED_NAMES.add(name);
                return name;
            }
        }

        throw new IllegalStateException("No temporary schematic name is available");
    }

    private static synchronized void releaseTemporaryName(String name) {
        RESERVED_NAMES.remove(name);
    }

    private static boolean hasCompleteSelection(AreaSelection selection) {
        if (selection == null || selection.getAllSubRegionBoxes().isEmpty()) {
            return false;
        }

        for (Box box : selection.getAllSubRegionBoxes()) {
            if (box.getPos1() == null || box.getPos2() == null) {
                return false;
            }
        }

        return true;
    }
}


