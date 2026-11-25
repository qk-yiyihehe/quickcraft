package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.mixin.LitematicaTaskSchedulerAccessor;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskSaveSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.malilib.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.*;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaAreaCloneLifecycleTest {
    @Test
    void namingReadsLoadedMetadataOnceAndKeepsReservations() throws Exception {
        String prefix = fi.dy.masa.malilib.util.StringUtils.translate("quickcraft.litematica.area_clone.name_prefix");
        var loaded = new ArrayList<LitematicaSchematic>();
        for (int i = 1; i <= 500; i++) {
            var schematic = mock(LitematicaSchematic.class);
            var metadata = mock(SchematicMetadata.class);
            when(schematic.getMetadata()).thenReturn(metadata);
            when(metadata.getName()).thenReturn(prefix + i);
            loaded.add(schematic);
        }
        var holder = mock(SchematicHolder.class);
        when(holder.getAllSchematics()).thenReturn(loaded);
        List<String> names = new ArrayList<>();
        try (var holders = mockStatic(SchematicHolder.class)) {
            holders.when(SchematicHolder::getInstance).thenReturn(holder);
            names.add(reserveName());
            assertThat(names.getFirst()).isEqualTo(prefix + 501);
            for (var schematic : loaded) {
                verify(schematic).getMetadata();
            }
            names.add(reserveName());
            assertThat(names.getLast()).isEqualTo(prefix + 502);
        } finally {
            var release = QuickLitematicaAreaClone.class.getDeclaredMethod("releaseTemporaryName", String.class);
            release.setAccessible(true);
            for (String name : names) {
                release.invoke(null, name);
            }
        }
    }

    @Test
    void hotkeyScheduledTaskCancelsFromBothRealSchedulerQueuesWithoutRemovingOtherTasks() throws Exception {
        for (boolean active : new boolean[] { false, true }) {
            try (RealTaskFixture f = new RealTaskFixture(false)) {
                if (active) {
                    f.scheduler.runTasks();
                }
                assertThat(active ? f.scheduler.getAllTasks() : f.queued).contains(f.task);
                ITask unrelated = mock(ITask.class);
                f.queued.add(unrelated);
                QuickLitematicaAreaClone.beforeWorldChange();
                f.drainCallbacks();
                assertThat(f.scheduler.getAllTasks()).doesNotContain(f.task);
                assertThat(f.queued).contains(unrelated).doesNotContain(f.task);
                assertThat(SchematicHolder.getInstance().getAllSchematics()).doesNotContain(f.schematic);
                assertThat(pendingSet()).isEmpty();
                assertThat(reservedNames()).doesNotContain(f.name);
                verify(unrelated, never()).stop();
            }
        }
    }

    @Test
    void deferredIntegratedServerStopCannotDeliverCancelledSchematicBeforeItsCallback() throws Exception {
        try (RealTaskFixture f = new RealTaskFixture(true)) {
            f.scheduler.runTasks();
            f.scheduler.runTasks();
            assertThat(f.scheduler.getAllTasks()).doesNotContain(f.task);
            assertThat(f.callbacks).hasSize(1);
            QuickLitematicaAreaClone.beforeWorldChange();
            // 世界尚未替换，取消标志必须在上游 onStop 入库之前生效。
            f.callbacks.removeFirst().run();
            assertThat(SchematicHolder.getInstance().getAllSchematics()).doesNotContain(f.schematic);
            f.drainCallbacks();
            assertThat(pendingSet()).isEmpty();
            assertThat(reservedNames()).doesNotContain(f.name);
            f.placements.verifyNoInteractions();
        }
    }

    @Test
    void realTaskStopDeliversNormalCloneOnceAndReleasesItsReservedName() throws Exception {
        try (RealTaskFixture f = new RealTaskFixture(false)) {
            f.scheduler.runTasks();
            f.scheduler.runTasks();
            assertThat(SchematicHolder.getInstance().getAllSchematics()).contains(f.schematic);
            f.drainCallbacks();
            verify(f.manager).addSchematicPlacement(f.placement, false);
            verify(f.manager).setSelectedSchematicPlacement(f.placement);
            assertThat(pendingSet()).isEmpty();
            assertThat(reservedNames()).doesNotContain(f.name);
        }
    }

    @Test
    void queuedTaskIsRemovedAndStoppedBeforeItRuns() throws Exception {
        TaskScheduler scheduler = scheduler();
        TaskSaveSchematic task = mock(TaskSaveSchematic.class);
        List<ITask> queued = queued(scheduler);
        queued.add(task);
        Object pending = pending(
                task,
                "area-clone-test",
                mock(LitematicaSchematic.class),
                scheduler,
                mock(ClientLevel.class)
        );

        SchematicPlacementManager manager = manager();
        try (var data = mockStatic(DataManager.class)) {
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
            QuickLitematicaAreaClone.beforeWorldChange();
        }

        assertThat(queued).doesNotContain(task);
        verify(task).stop();
        assertThat(pendingSet()).doesNotContain(pending);
    }

    @Test
    void activeTaskIsCancelledThroughUpstreamRemoveTask() throws Exception {
        TaskScheduler scheduler = scheduler();
        TaskSaveSchematic task = mock(TaskSaveSchematic.class);
        when(scheduler.getAllTasks()).thenReturn(com.google.common.collect.ImmutableList.of(task));
        Object pending = pending(
                task,
                "area-clone-test",
                mock(LitematicaSchematic.class),
                scheduler,
                mock(ClientLevel.class)
        );

        SchematicPlacementManager manager = manager();
        try (var data = mockStatic(DataManager.class)) {
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
            QuickLitematicaAreaClone.beforeWorldChange();
        }

        verify(scheduler).removeTask(task);
        assertThat(pendingSet()).doesNotContain(pending);
    }

    @Test
    void normalCompletionCreatesPlacementAfterUpstreamHolderDelivery() throws Exception {
        TaskScheduler scheduler = scheduler();
        TaskSaveSchematic task = mock(TaskSaveSchematic.class);
        LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        ClientLevel world = mock(ClientLevel.class);
        Object pending = pending(task, "area-clone-test", schematic, scheduler, world);
        Minecraft client = client(world);
        SchematicPlacement placement = mock(SchematicPlacement.class);
        SchematicPlacementManager manager = manager();

        try (var minecraft = mockStatic(Minecraft.class);
                var placements = mockStatic(SchematicPlacement.class);
                var data = mockStatic(DataManager.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            placements.when(() -> SchematicPlacement.createFor(schematic, BlockPos.ZERO, "area-clone-test", true, true))
                    .thenReturn(placement);
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);

            invokePending("complete", pending);

            verify(manager).addSchematicPlacement(placement, false);
            verify(manager).setSelectedSchematicPlacement(placement);
            assertThat(pendingSet()).doesNotContain(pending);
        }
    }

    @Test
    void lateCompletionAfterWorldChangeDoesNotCreateAPlacementOrLeaveAHolderEntry() throws Exception {
        TaskScheduler scheduler = scheduler();
        TaskSaveSchematic task = mock(TaskSaveSchematic.class);
        LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        Object pending = pending(task, "area-clone-test", schematic, scheduler, mock(ClientLevel.class));
        SchematicHolder.getInstance().addSchematic(schematic, true);
        SchematicPlacementManager manager = manager();

        try (var data = mockStatic(DataManager.class)) {
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
            QuickLitematicaAreaClone.beforeWorldChange();
            invokePending("complete", pending);
        }

        assertThat(SchematicHolder.getInstance().getAllSchematics()).doesNotContain(schematic);
        verify(manager, org.mockito.Mockito.never()).addSchematicPlacement(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
    }

    @Test
    void abortedTaskReleasesNameAndDoesNotCreateAPlacement() throws Exception {
        TaskScheduler scheduler = scheduler();
        TaskSaveSchematic task = mock(TaskSaveSchematic.class);
        LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        ClientLevel world = mock(ClientLevel.class);
        Object pending = pending(task, reserveName(), schematic, scheduler, world);
        SchematicPlacementManager manager = manager();
        Minecraft client = client(world);

        try (var minecraft = mockStatic(Minecraft.class); var data = mockStatic(DataManager.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
            invokePending("abort", pending);
        }

        assertThat(SchematicHolder.getInstance().getAllSchematics()).doesNotContain(schematic);
        verify(manager, org.mockito.Mockito.never()).addSchematicPlacement(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
    }

    private static TaskScheduler scheduler() {
        TaskScheduler scheduler = mock(
                TaskScheduler.class,
                withSettings().extraInterfaces(LitematicaTaskSchedulerAccessor.class)
        );
        when(((LitematicaTaskSchedulerAccessor) scheduler).quickcraft$getQueuedTasks()).thenReturn(new ArrayList<>());
        when(scheduler.getAllTasks()).thenReturn(com.google.common.collect.ImmutableList.of());
        return scheduler;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> reservedNames() throws Exception {
        Field field = QuickLitematicaAreaClone.class.getDeclaredField("RESERVED_NAMES");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    private static final class RealTaskFixture implements AutoCloseable {
        final List<ITask> queued = new ArrayList<>();
        final List<Runnable> callbacks = new ArrayList<>();
        final TaskScheduler scheduler = mock(
                TaskScheduler.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS).extraInterfaces(LitematicaTaskSchedulerAccessor.class)
        );
        final LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        final SchematicPlacementManager manager = manager();
        final SchematicPlacement placement = mock(SchematicPlacement.class);
        final MockedStatic<Minecraft> minecraft = mockStatic(Minecraft.class);
        final MockedStatic<DataManager> data = mockStatic(DataManager.class);
        final MockedStatic<TaskScheduler> schedulers = mockStatic(TaskScheduler.class);
        final MockedStatic<LitematicaSchematic> schematics = mockStatic(LitematicaSchematic.class);
        final MockedStatic<WorldUtils> worlds = mockStatic(WorldUtils.class);
        final MockedStatic<SchematicWorldHandler> schematicWorlds = mockStatic(SchematicWorldHandler.class);
        final MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class);
        final MockedStatic<InfoUtils> messages = mockStatic(InfoUtils.class);
        final MockedStatic<SchematicPlacement> placements = mockStatic(SchematicPlacement.class);
        TaskSaveSchematic task;
        String name;

        RealTaskFixture(boolean integratedServer) throws Exception {
            try {
                ClientLevel source = mock(ClientLevel.class);
                Minecraft client = client(source);
                minecraft.when(Minecraft::getInstance).thenReturn(client);
                doAnswer(call -> {
                    callbacks.add(call.getArgument(0));
                    return null;
                }).when(client).execute(any(Runnable.class));
                worlds.when(() -> WorldUtils.getBestWorld(client)).thenReturn(
                        integratedServer ? mock(net.minecraft.world.level.Level.class) : source
                );
                setField(scheduler, "tasks", new ArrayList<ITask>());
                setField(scheduler, "tasksToAdd", queued);
                when(((LitematicaTaskSchedulerAccessor) scheduler).quickcraft$getQueuedTasks()).thenReturn(queued);
                schedulers.when(TaskScheduler::getServerInstanceIfExistsOrClient).thenReturn(scheduler);
                AreaSelection selection = mock(AreaSelection.class);
                when(selection.copy()).thenReturn(selection);
                when(selection.getEffectiveOrigin()).thenReturn(BlockPos.ZERO);
                when(selection.getAllSubRegions()).thenReturn(com.google.common.collect.ImmutableMap.of());
                when(selection.getAllSubRegionBoxes()).thenReturn(List.of());
                // 空的待处理区块用于隔离调度/停止契约，不验证方块采集。
                SelectionManager selections = mock(SelectionManager.class);
                when(selections.getCurrentSelection()).thenReturn(selection);
                data.when(DataManager::getSelectionManager).thenReturn(selections);
                data.when(DataManager::getToolMode).thenReturn(ToolMode.AREA_SELECTION);
                data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
                when(schematic.getMetadata()).thenReturn(new SchematicMetadata());
                schematics.when(() -> LitematicaSchematic.createEmptySchematic(selection, "tester")).thenReturn(
                        schematic
                );
                configs.when(QuickCraftConfigs::isLitematicaAreaCloneEnabled).thenReturn(true);
                placements.when(
                        () -> SchematicPlacement.createFor(
                                eq(schematic),
                                eq(BlockPos.ZERO),
                                anyString(),
                                eq(true),
                                eq(true)
                        )
                ).thenReturn(placement);
                // 正常热键要求有完整选区；单格盒只在入口校验中出现，复制快照保持空块以隔离生命周期。
                AreaSelection initial = mock(AreaSelection.class);
                when(initial.getAllSubRegionBoxes()).thenReturn(
                        List.of(new fi.dy.masa.litematica.selection.Box(BlockPos.ZERO, BlockPos.ZERO, "test"))
                );
                when(initial.copy()).thenReturn(selection);
                when(selections.getCurrentSelection()).thenReturn(initial);
                Method hotkey = QuickLitematicaAreaClone.class.getDeclaredMethod(
                        "handleHotkey",
                        KeyAction.class,
                        IKeybind.class
                );
                hotkey.setAccessible(true);
                assertThat(hotkey.invoke(null, KeyAction.PRESS, mock(IKeybind.class))).isEqualTo(true);
                assertThat(queued).hasSize(1);
                task = (TaskSaveSchematic) queued.getFirst();
                name = schematic.getMetadata().getName();
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        void drainCallbacks() {
            while (!callbacks.isEmpty()) {
                callbacks.removeFirst().run();
            }
        }

        @Override
        public void close() {
            QuickLitematicaAreaClone.beforeWorldChange();
            drainCallbacks();
            SchematicHolder.getInstance().removeSchematic(schematic);
            placements.close();
            messages.close();
            configs.close();
            schematicWorlds.close();
            worlds.close();
            schematics.close();
            schedulers.close();
            data.close();
            minecraft.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ITask> queued(TaskScheduler scheduler) {
        return ((LitematicaTaskSchedulerAccessor) scheduler).quickcraft$getQueuedTasks();
    }

    private static SchematicPlacementManager manager() {
        SchematicPlacementManager manager = mock(SchematicPlacementManager.class);
        when(manager.getAllSchematicsPlacements()).thenReturn(List.of());
        return manager;
    }

    private static Minecraft client(ClientLevel world) throws Exception {
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        when(player.getName()).thenReturn(Component.literal("tester"));
        setField(client, "player", player);
        setField(client, "level", world);
        Gui gui = mock(Gui.class);
        try {
            setField(gui, "hud", mock(findField(Gui.class, "hud").getType()));
        } catch (NoSuchFieldException ignored) {}
        setField(client, "gui", gui);
        return client;
    }

    private static String reserveName() throws Exception {
        Method method = QuickLitematicaAreaClone.class.getDeclaredMethod("reserveTemporaryName");
        method.setAccessible(true);
        return (String) method.invoke(null);
    }

    private static Object pending(
            TaskSaveSchematic task,
            String name,
            LitematicaSchematic schematic,
            TaskScheduler scheduler,
            ClientLevel world
    ) throws Exception {
        Class<?> type = Class.forName(QuickLitematicaAreaClone.class.getName() + "$PendingClone");
        Constructor<?> constructor = type.getDeclaredConstructor(
                TaskSaveSchematic.class,
                ClientLevel.class,
                String.class,
                LitematicaSchematic.class,
                TaskScheduler.class,
                BlockPos.class
        );
        constructor.setAccessible(true);
        Object pending = constructor.newInstance(task, world, name, schematic, scheduler, BlockPos.ZERO);
        pendingSet().add(pending);
        return pending;
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> pendingSet() throws Exception {
        Field field = QuickLitematicaAreaClone.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        return (Set<Object>) field.get(null);
    }

    private static void invokePending(String name, Object pending) throws Exception {
        Method method = QuickLitematicaAreaClone.class.getDeclaredMethod(
                name,
                Class.forName(QuickLitematicaAreaClone.class.getName() + "$PendingClone")
        );
        method.setAccessible(true);
        method.invoke(null, pending);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getSuperclass() == null ? target.getClass().getDeclaredField(name)
                : findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
