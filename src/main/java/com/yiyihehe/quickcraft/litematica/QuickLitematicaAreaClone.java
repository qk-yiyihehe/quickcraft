package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.LitematicaTaskSchedulerAccessor;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将模式 1 当前选区异步复制成 Litematica 内存投影。
 * 投影不写文件，持久化和世界切换清理由 Litematica 自己的管理器负责。
 */
public final class QuickLitematicaAreaClone {
    private static final Set<String> RESERVED_NAMES = new HashSet<>();
    private static final Set<PendingClone> PENDING = new HashSet<>();

    private QuickLitematicaAreaClone() {
    }

    public static void bindHotkey() {
        QuickCraftConfigs.Hotkeys.CLONE_LITEMATICA_AREA.getKeybind()
                .setCallback(QuickLitematicaAreaClone::handleHotkey);
    }

    private static boolean handleHotkey(KeyAction action, IKeybind keybind) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (action != KeyAction.PRESS
                || client.player == null
                || client.world == null
                || client.currentScreen != null) {
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
        if (selection == null || !hasCompleteSelection(selection)) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.no_selection");
            return true;
        }

        AreaSelection snapshot = selection.copy();
        String author = client.player.getName().getString();
        ClientWorld sourceWorld = client.world;
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(snapshot, author);
        if (schematic == null) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.failed");
            return true;
        }

        String name = reserveTemporaryName();
        schematic.getMetadata().setName(name);
        LitematicaSchematic.SchematicSaveInfo saveInfo =
                new LitematicaSchematic.SchematicSaveInfo(false, false);
        TaskSaveSchematic task = new CloneSaveTask(schematic, snapshot, saveInfo, sourceWorld);
        TaskScheduler scheduler = TaskScheduler.getServerInstanceIfExistsOrClient();
        PendingClone pending = new PendingClone(task, sourceWorld, name, schematic, scheduler, snapshot.getEffectiveOrigin());
        task.disableCompletionMessage();
        task.setCompletionListener(new ICompletionListener() {
            @Override
            public void onTaskCompleted() {
                complete(pending);
            }

            @Override
            public void onTaskAborted() {
                abort(pending);
            }
        });

        synchronized (PENDING) {
            PENDING.add(pending);
        }
        scheduler.scheduleTask(task, 10);
        InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.scheduled");
        return true;
    }

    public static void beforeWorldChange() {
        List<PendingClone> pending;
        synchronized (PENDING) {
            pending = new ArrayList<>(PENDING);
            PENDING.clear();
            for (PendingClone clone : pending) {
                clone.cancelled = true;
            }
        }

        for (PendingClone clone : pending) {
            cancelTask(clone);
            discard(clone);
        }
    }

    private static void complete(PendingClone pending) {
        boolean current;
        synchronized (PENDING) {
            if (pending.finished) { return; }
            pending.finished = true;
            current = !pending.cancelled && PENDING.remove(pending);
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (!current || client == null || client.world != pending.world || client.player == null) {
            discard(pending);
            return;
        }

        releaseName(pending);
        SchematicPlacement placement = SchematicPlacement.createFor(
                pending.schematic,
                pending.selectionOrigin,
                pending.name,
                true,
                true
        );
        placement.setShouldBeSaved(false);

        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
        InfoUtils.printActionbarMessage(
                "quickcraft.message.litematica.area_clone.created",
                pending.name
        );
    }

    private static void abort(PendingClone pending) {
        boolean cancelled;
        synchronized (PENDING) {
            if (pending.finished) { return; }
            pending.finished = true;
            cancelled = pending.cancelled;
            PENDING.remove(pending);
        }
        discard(pending);
        MinecraftClient client = MinecraftClient.getInstance();
        if (!cancelled && client != null && client.world == pending.world && client.player != null) {
            InfoUtils.printActionbarMessage("quickcraft.message.litematica.area_clone.aborted");
        }
    }

    private static void cancelTask(PendingClone pending) {
        if (pending.task instanceof CloneSaveTask cloneTask) { cloneTask.cancel(); }
        TaskScheduler scheduler = pending.scheduler;
        var queued = ((LitematicaTaskSchedulerAccessor) scheduler).quickcraft$getQueuedTasks();
        synchronized (scheduler) {
            if (queued.remove(pending.task)) {
                pending.task.stop();
            } else if (scheduler.getAllTasks().contains(pending.task)) {
                scheduler.removeTask(pending.task);
            }
        }
    }

    private static void discard(PendingClone pending) {
        releaseName(pending);
        var holder = SchematicHolder.getInstance();
        if (holder.getAllSchematics().contains(pending.schematic)) {
            holder.removeSchematic(pending.schematic);
        }
    }

    private static void releaseName(PendingClone pending) {
        synchronized (PENDING) {
            if (pending.nameReleased) { return; }
            pending.nameReleased = true;
        }
        releaseTemporaryName(pending.name);
    }

    private static final class CloneSaveTask extends TaskSaveSchematic {
        private final ClientWorld sourceWorld;
        private volatile boolean cancelled;

        private CloneSaveTask(LitematicaSchematic schematic, AreaSelection area,
                              LitematicaSchematic.SchematicSaveInfo info, ClientWorld sourceWorld) {
            super(schematic, area, info);
            this.sourceWorld = sourceWorld;
        }

        private void cancel() {
            this.cancelled = true;
        }

        @Override
        protected void onStop() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (this.cancelled || client == null || client.world != this.sourceWorld) {
                this.finished = false;
            }
            super.onStop();
        }
    }

    private static final class PendingClone {
        private final TaskSaveSchematic task;
        private final ClientWorld world;
        private final String name;
        private final LitematicaSchematic schematic;
        private final TaskScheduler scheduler;
        private final net.minecraft.util.math.BlockPos selectionOrigin;
        private volatile boolean cancelled;
        private boolean finished;
        private boolean nameReleased;

        private PendingClone(TaskSaveSchematic task, ClientWorld world, String name,
                             LitematicaSchematic schematic, TaskScheduler scheduler,
                             net.minecraft.util.math.BlockPos selectionOrigin) {
            this.task = task;
            this.world = world;
            this.name = name;
            this.schematic = schematic;
            this.scheduler = scheduler;
            this.selectionOrigin = selectionOrigin;
        }
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
