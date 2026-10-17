package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatchKey;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionPersistence.PersistentVerifier;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaVerifierIgnoresTest {
    @Test
    void containerIgnoresAreScopedResettableAndNotRecordedWhenDisabled() {
        var master = QuickCraftConfigs.ProjectionTools.PERSIST_PROJECTION_FEATURES;
        var ignores = QuickCraftConfigs.ProjectionTools.ALLOW_PERSISTENT_VERIFIER_IGNORES;
        boolean oldMaster = master.getBooleanValue();
        boolean oldIgnores = ignores.getBooleanValue();
        try {
            master.setBooleanValue(true);
            ignores.setBooleanValue(true);
            var first = placement();
            var second = placement();
            var firstVerifier = verifier();
            var secondVerifier = verifier();
            var key = new ContainerMismatchKey(null, null, "same-container-error");
            QuickLitematicaVerifierIgnores.rememberContainer(firstVerifier, first, key);
            QuickLitematicaVerifierIgnores.restore(secondVerifier, second);
            assertThat(containerState(secondVerifier).quickcraft$getIgnoredContainerCount()).isZero();
            QuickLitematicaVerifierIgnores.restore(firstVerifier, first);
            assertThat(containerState(firstVerifier).quickcraft$getIgnoredContainerCount()).isEqualTo(1);
            QuickLitematicaVerifierIgnores.reset(firstVerifier, first);
            assertThat(containerState(firstVerifier).quickcraft$getIgnoredContainerCount()).isZero();
            var recreated = verifier();
            QuickLitematicaVerifierIgnores.restore(recreated, first);
            assertThat(containerState(recreated).quickcraft$getIgnoredContainerCount()).isZero();
            QuickLitematicaVerifierIgnores.rememberContainer(firstVerifier, first, key);
            QuickLitematicaVerifierIgnores.restore(firstVerifier, first);
            master.setBooleanValue(false);
            QuickLitematicaVerifierIgnores.settingsChanged();
            assertThat(containerState(firstVerifier).quickcraft$getIgnoredContainerCount()).isZero();
            QuickLitematicaVerifierIgnores.rememberContainer(firstVerifier, first, key);
            master.setBooleanValue(true);
            QuickLitematicaVerifierIgnores.restore(recreated, first);
            assertThat(containerState(recreated).quickcraft$getIgnoredContainerCount()).isZero();
            ignores.setBooleanValue(false);
            QuickLitematicaVerifierIgnores.settingsChanged();
            QuickLitematicaVerifierIgnores.rememberContainer(firstVerifier, first, key);
            ignores.setBooleanValue(true);
            QuickLitematicaVerifierIgnores.restore(recreated, first);
            assertThat(containerState(recreated).quickcraft$getIgnoredContainerCount()).isZero();
        } finally {
            QuickLitematicaVerifierIgnores.clearSession();
            master.setBooleanValue(oldMaster);
            ignores.setBooleanValue(oldIgnores);
        }
    }

    private static SchematicPlacement placement() {
        var placement = mock(SchematicPlacement.class);
        when(placement.getHashId()).thenReturn(UUID.randomUUID());
        return placement;
    }

    private static SchematicVerifier verifier() {
        var verifier = mock(SchematicVerifier.class, withSettings().extraInterfaces(PersistentVerifier.class));
        when(verifier.getIgnoredMismatches()).thenReturn(new HashSet<>());
        when(((PersistentVerifier) verifier).quickcraft$containerVerification())
                .thenReturn(new QuickLitematicaContainerVerification(mock(QuickLitematicaContainerVerification.Host.class)));
        return verifier;
    }

    private static QuickLitematicaContainerVerification containerState(SchematicVerifier verifier) {
        return ((PersistentVerifier) verifier).quickcraft$containerVerification();
    }
}
