package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.LitematicaInfoHudAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaSchematicPlacementAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaTaskSchedulerAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** 仅在当前连接内保留 HUD；离开的维度不持有客户端世界，也不继续验证。 */
public final class QuickLitematicaProjectionPersistence {
    private static final int VERIFIER_INTERVAL_TICKS = 10;
    private static final Map<String, Map<UUID, VerifierState>> VERIFIERS = new HashMap<>();

    private QuickLitematicaProjectionPersistence() { }

    public interface PersistentVerifier {
        SchematicPlacement quickcraft$persistentPlacement();
        void quickcraft$detachVerificationWorld();
        void quickcraft$attachVerificationWorld(SchematicPlacement placement);
        QuickLitematicaContainerVerification quickcraft$containerVerification();
    }

    private record VerifierState(SchematicVerifier verifier, boolean scheduled, boolean hud) { }

    private static boolean keepVerifier() {
        return QuickCraftConfigs.ProjectionTools.PERSIST_PROJECTION_FEATURES.getBooleanValue()
                && QuickCraftConfigs.ProjectionTools.PERSIST_SCHEMATIC_VERIFIER.getBooleanValue();
    }

    public static void settingsChanged() {
        if (!keepVerifier()) { VERIFIERS.clear(); }
        QuickLitematicaMaterialIgnores.settingsChanged();
        QuickLitematicaVerifierIgnores.settingsChanged();
    }

    public static void beforeWorldChange(String dimension, boolean logout) {
        settingsChanged();
        InfoHud hud = InfoHud.getInstance();
        var renderers = ((LitematicaInfoHudAccessor) hud).quickcraft$getRenderers();
        var scheduler = TaskScheduler.getInstanceClient();
        var queued = ((LitematicaTaskSchedulerAccessor) scheduler).quickcraft$getQueuedTasks();
        var verifiers = new LinkedHashSet<SchematicVerifier>();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (placement.hasVerifier()) { verifiers.add(placement.getSchematicVerifier()); }
        }
        for (var renderer : renderers) {
            if (renderer instanceof SchematicVerifier verifier) { verifiers.add(verifier); }
        }
        var tasks = new HashSet<ITask>();
        synchronized (scheduler) {
            tasks.addAll(scheduler.getAllTasks());
            tasks.addAll(queued);
        }
        var visibleRenderers = new HashSet<>(renderers);
        for (var task : tasks) {
            if (task instanceof SchematicVerifier verifier) { verifiers.add(verifier); }
        }
        var materials = DataManager.getMaterialList();
        if (materials instanceof QuickLitematicaContainerMaterials.ContainerMaterialList containers
                && containers.data.placement != null && containers.data.placement.hasVerifier()) {
            verifiers.add(containers.data.placement.getSchematicVerifier());
        }
        var saved = new HashMap<UUID, VerifierState>();
        for (SchematicVerifier verifier : verifiers) {
            var persistent = (PersistentVerifier) verifier;
            SchematicPlacement placement = persistent.quickcraft$persistentPlacement();
            if (placement == null) { continue; }
            boolean scheduled = tasks.contains(verifier);
            if (!logout && keepVerifier()) {
                saved.putIfAbsent(placement.getHashId(), new VerifierState(verifier, scheduled, visibleRenderers.contains(verifier)));
            }
            // 原生 removeTask 不处理待加入队列；两处都移除，避免返回后重复调度。
            synchronized (scheduler) {
                queued.remove(verifier);
                scheduler.removeTask(verifier);
            }
            hud.removeInfoHudRenderer(verifier, false);
            persistent.quickcraft$detachVerificationWorld();
        }
        if (logout) {
            VERIFIERS.clear();
            QuickLitematicaMaterialIgnores.clearSession();
            QuickLitematicaVerifierIgnores.clearSession();
        } else {
            if (keepVerifier() && !saved.isEmpty()) { VERIFIERS.put(dimension, saved); }
            else { VERIFIERS.remove(dimension); }
        }
    }

    public static void afterWorldChange(String dimension) {
        settingsChanged();
        var saved = VERIFIERS.remove(dimension);
        if (!keepVerifier() || saved == null) { return; }
        int resumed = 0;
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            VerifierState state = saved.get(placement.getHashId());
            if (state == null) { continue; }
            ((LitematicaSchematicPlacementAccessor) placement).quickcraft$setVerifier(state.verifier());
            ((PersistentVerifier) state.verifier()).quickcraft$attachVerificationWorld(placement);
            QuickLitematicaVerifierIgnores.restore(state.verifier(), placement);
            if (state.scheduled()) {
                TaskScheduler.getInstanceClient().scheduleTask(state.verifier(), VERIFIER_INTERVAL_TICKS);
                // 按原版 10 tick 验证周期错开首次执行，避免恢复任务挤在传送后的第一帧。
                state.verifier().getTimer().setNextDelay(1 + resumed++ % VERIFIER_INTERVAL_TICKS);
            }
            if (state.hud()) { InfoHud.getInstance().addInfoHudRenderer(state.verifier(), false); }
        }
    }
}
