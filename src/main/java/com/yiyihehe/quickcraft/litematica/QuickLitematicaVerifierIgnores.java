package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatchKey;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import net.minecraft.block.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** 验证忽略只属于对应投影，缓存不持有世界或投影对象。 */
public final class QuickLitematicaVerifierIgnores {
    private record Ignores(Set<Pair<BlockState, BlockState>> blocks, Set<ContainerMismatchKey> containers) {
        private Ignores() { this(new HashSet<>(), new HashSet<>()); }
    }

    private static final Map<UUID, Ignores> SAVED = new HashMap<>();
    private static final Map<SchematicVerifier, UUID> SCOPES = new WeakHashMap<>();
    private static final Map<SchematicVerifier, Set<Pair<BlockState, BlockState>>> RESTORED = new WeakHashMap<>();

    private QuickLitematicaVerifierIgnores() { }

    private static boolean enabled() {
        return QuickCraftConfigs.ProjectionTools.PERSIST_PROJECTION_FEATURES.getBooleanValue()
                && QuickCraftConfigs.ProjectionTools.ALLOW_PERSISTENT_VERIFIER_IGNORES.getBooleanValue();
    }

    private static UUID scope(SchematicVerifier verifier, SchematicPlacement placement) {
        return placement != null ? placement.getHashId() : SCOPES.get(verifier);
    }

    public static void rememberBlock(SchematicVerifier verifier, SchematicPlacement placement, BlockMismatch mismatch) {
        if (QuickLitematicaContainerVerifier.isContainerMismatchType(QuickLitematicaVerifierAccess.getMismatchType(mismatch))) { return; }
        UUID id = scope(verifier, placement);
        if (!enabled() || id == null) { return; }
        SAVED.computeIfAbsent(id, key -> new Ignores()).blocks().add(Pair.of(
                QuickLitematicaVerifierAccess.getExpectedState(mismatch), QuickLitematicaVerifierAccess.getFoundState(mismatch)));
    }

    public static void rememberContainer(SchematicVerifier verifier, SchematicPlacement placement, ContainerMismatchKey mismatch) {
        UUID id = scope(verifier, placement);
        if (enabled() && id != null) { SAVED.computeIfAbsent(id, key -> new Ignores()).containers().add(mismatch); }
    }

    public static void restore(SchematicVerifier verifier, SchematicPlacement placement) {
        if (placement == null) { return; }
        SCOPES.put(verifier, placement.getHashId());
        if (!enabled()) { return; }
        Ignores saved = SAVED.get(placement.getHashId());
        if (saved == null) { return; }
        var restored = RESTORED.computeIfAbsent(verifier, key -> new HashSet<>());
        for (var mismatch : saved.blocks()) {
            if (verifier.getIgnoredMismatches().add(mismatch)) { restored.add(mismatch); }
        }
        ((QuickLitematicaProjectionPersistence.PersistentVerifier) verifier).quickcraft$containerVerification()
                .quickcraft$restoreIgnoredContainers(saved.containers());
    }

    public static void reset(SchematicVerifier verifier, SchematicPlacement placement) {
        UUID id = scope(verifier, placement);
        if (id != null) {
            SAVED.remove(id);
            SCOPES.forEach((other, otherId) -> {
                if (id.equals(otherId)) {
                    other.getIgnoredMismatches().clear();
                    ((QuickLitematicaProjectionPersistence.PersistentVerifier) other).quickcraft$containerVerification()
                            .quickcraft$resetIgnoredContainers();
                    RESTORED.remove(other);
                }
            });
        }
        RESTORED.remove(verifier);
        ((QuickLitematicaProjectionPersistence.PersistentVerifier) verifier).quickcraft$containerVerification()
                .quickcraft$resetIgnoredContainers();
    }

    public static void settingsChanged() {
        if (enabled()) { return; }
        SAVED.clear();
        RESTORED.forEach((verifier, entries) -> {
            verifier.getIgnoredMismatches().removeAll(entries);
            ((QuickLitematicaProjectionPersistence.PersistentVerifier) verifier).quickcraft$containerVerification()
                    .quickcraft$forgetRestoredContainerIgnores();
        });
        RESTORED.clear();
    }

    public static void clearSession() {
        SAVED.clear();
        SCOPES.clear();
        RESTORED.clear();
    }
}
