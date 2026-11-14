package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** BUG-67：源检查节流使用单调纳秒，且未到期不重复 stat。 */
@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaPreviewSourceCheckTest {
    @Test
    void firstCheckIsImmediateAndReentryBeforeIntervalIsSuppressed() throws Exception {
        QuickLitematicaPreview3D.Preview preview = mock(QuickLitematicaPreview3D.Preview.class);
        QuickLitematicaPreview3D.Manager manager = new QuickLitematicaPreview3D.Manager(mock(Screen.class));
        set(manager, "current", preview);
        set(manager, "currentPath", Path.of("source.litematic"));
        set(manager, "currentEntry", mock(DirectoryEntry.class));
        set(manager, "nextSourceCheckNanos", Long.MIN_VALUE);

        try (var resources = mockStatic(QuickLitematicaPreviewResources.class)) {
            resources.when(QuickLitematicaPreviewResources::reloading).thenReturn(false);

            long now = -2_000_000_000L;
            invokeCheck(manager, now);
            long next = getLong(manager, "nextSourceCheckNanos");
            assertThat(next).isNotEqualTo(Long.MIN_VALUE);
            assertThat(next).isEqualTo(now + QuickLitematicaPreview3D.SOURCE_CHECK_INTERVAL_NANOS);
            verify(preview, times(1)).sourceStampChanged();

            invokeCheck(manager, next - 1);
            verify(preview, times(1)).sourceStampChanged();
        } finally {
            manager.close();
        }
    }

    @Test
    void dueCheckAdvancesDeadlineAfterAnInterval() throws Exception {
        QuickLitematicaPreview3D.Preview preview = mock(QuickLitematicaPreview3D.Preview.class);
        QuickLitematicaPreview3D.Manager manager = new QuickLitematicaPreview3D.Manager(mock(Screen.class));
        set(manager, "current", preview);
        set(manager, "currentPath", Path.of("source.litematic"));
        set(manager, "currentEntry", mock(DirectoryEntry.class));
        set(manager, "nextSourceCheckNanos", 100L);

        try (var resources = mockStatic(QuickLitematicaPreviewResources.class)) {
            resources.when(QuickLitematicaPreviewResources::reloading).thenReturn(false);
            invokeCheck(manager, 100L);
            verify(preview, times(1)).sourceStampChanged();
            assertThat(getLong(manager, "nextSourceCheckNanos"))
                    .isEqualTo(100L + QuickLitematicaPreview3D.SOURCE_CHECK_INTERVAL_NANOS);
        } finally {
            manager.close();
        }
    }

    @Test
    void monotonicCounterWrapStillHonorsTheInterval() throws Exception {
        var preview = mock(QuickLitematicaPreview3D.Preview.class);
        var manager = new QuickLitematicaPreview3D.Manager(mock(Screen.class));
        set(manager, "current", preview);
        set(manager, "currentPath", Path.of("source.litematic"));
        set(manager, "currentEntry", mock(DirectoryEntry.class));
        long now = Long.MAX_VALUE - 10L;
        try (var resources = mockStatic(QuickLitematicaPreviewResources.class)) {
            resources.when(QuickLitematicaPreviewResources::reloading).thenReturn(false);
            invokeCheck(manager, now);
            long deadline = getLong(manager, "nextSourceCheckNanos");
            assertThat(deadline).isEqualTo(now + QuickLitematicaPreview3D.SOURCE_CHECK_INTERVAL_NANOS);
            invokeCheck(manager, deadline - 1L);
            verify(preview, times(1)).sourceStampChanged();
            invokeCheck(manager, deadline);
            verify(preview, times(2)).sourceStampChanged();
        } finally {
            manager.close();
        }
    }

    private static void invokeCheck(QuickLitematicaPreview3D.Manager manager, long now) throws Exception {
        Method method = QuickLitematicaPreview3D.Manager.class.getDeclaredMethod("checkCurrentSource", long.class);
        method.setAccessible(true);
        method.invoke(manager, now);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static long getLong(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }
}
