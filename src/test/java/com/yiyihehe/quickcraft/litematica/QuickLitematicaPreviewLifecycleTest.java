package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

class QuickLitematicaPreviewLifecycleTest {
    @BeforeAll
    static void initializeMinecraftRegistries() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void browserMetadataRefreshUsesTheWeakOwnerImmediately() throws Exception {
        var owner = mock(Screen.class, withSettings().extraInterfaces(QuickLitematicaPreviewMetadataRefresh.class));
        var refresh = (QuickLitematicaPreviewMetadataRefresh) owner;
        var preview = mock(QuickLitematicaPreview3D.Preview.class);
        var manager = new QuickLitematicaPreview3D.Manager(owner);
        setField(manager, "current", preview);
        long generation = manager.beginPreviewImageWrite();

        var client = mock(MinecraftClient.class);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }).when(client).execute(any(Runnable.class));

            manager.finishPreviewWrite(
                    preview,
                    false,
                    false,
                    generation,
                    message -> {},
                    net.minecraft.text.Text.empty()
            );
        }

        verify(refresh, times(1)).quickcraft$clearPreviewMetadataCache();
        manager.close();
    }

    @Test
    void abandonedBrowserIsClosedByTheTickReaperOnce() throws Exception {
        var gui = mock(GuiSchematicBrowserBase.class);
        var manager = QuickLitematicaPreview3D.init(gui);
        var preview = mock(QuickLitematicaPreview3D.Preview.class);
        setField(manager, "current", preview);

        QuickLitematicaPreview3D.reapAbandonedManagers(mock(Screen.class));
        QuickLitematicaPreview3D.reapAbandonedManagers(mock(Screen.class));

        verify(preview, times(1)).close();
        assertThat(QuickLitematicaPreview3D.init(gui)).isNotSameAs(manager);
        QuickLitematicaPreview3D.close(gui);
    }

    @Test
    void temporaryBrowserChildAndFullscreenKeepTheManager() throws Exception {
        var gui = mock(GuiSchematicBrowserBase.class);
        var manager = QuickLitematicaPreview3D.init(gui);
        var preview = mock(QuickLitematicaPreview3D.Preview.class);
        setField(manager, "current", preview);
        var child = mock(GuiBase.class);
        when(child.getParent()).thenReturn(gui);

        QuickLitematicaPreview3D.reapAbandonedManagers(child);
        var screen = newScreen(manager, false);
        QuickLitematicaPreview3D.reapAbandonedManagers(screen);

        verify(preview, never()).close();
        QuickLitematicaPreview3D.close(gui);
        verify(preview, times(1)).close();
    }

    @Test
    void temporaryChildScreenKeepsTaskButFinalRemovalAndRepeatedCloseReleaseOnce() throws Exception {
        var preview = mock(QuickLitematicaPreview3D.Preview.class);
        var manager = new QuickLitematicaPreview3D.Manager(null);
        setField(manager, "current", preview);
        var screen = newScreen(manager, true);

        setField(screen, "temporaryChildScreen", true);
        screen.removed();
        verify(preview, never()).close();

        setField(screen, "temporaryChildScreen", false);
        screen.removed();
        screen.removed();

        verify(preview, times(1)).close();
        assertThat(getField(manager, "current")).isNull();
    }

    @Test
    void colorPickerTerminalReplacementReleasesOwnedManager() throws Exception {
        var preview = mock(QuickLitematicaPreview3D.Preview.class);
        var manager = new QuickLitematicaPreview3D.Manager(null);
        setField(manager, "current", preview);
        var screen = newScreen(manager, true);
        setField(screen, "temporaryChildScreen", true);
        Class<?> pickerClass = Class.forName(QuickLitematicaPreview3DScreen.class.getName() + "$ColorPickerScreen");
        var constructor = pickerClass.getDeclaredConstructor(QuickLitematicaPreview3DScreen.class);
        constructor.setAccessible(true);
        var client = mock(MinecraftClient.class);
        try {
            setField(client, "textRenderer", mock(TextRenderer.class));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        Screen picker;
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            when(MinecraftClient.getInstance()).thenReturn(client);
            picker = (Screen) constructor.newInstance(screen);
        }

        picker.removed();

        verify(preview, times(1)).close();
    }

    @Test
    void colorPickerIsRetainedByReaperUntilNormalReturnThenClosesOnFinalReplacement() throws Exception {
        var gui = mock(GuiSchematicBrowserBase.class);
        var manager = QuickLitematicaPreview3D.init(gui);
        var preview = mock(QuickLitematicaPreview3D.Preview.class);
        setField(manager, "current", preview);
        var screen = newScreen(manager, false);
        var picker = newColorPicker(screen);

        QuickLitematicaPreview3D.reapAbandonedManagers(picker);
        verify(preview, never()).close();
        assertThat(getField(manager, "current")).isSameAs(preview);

        picker.close();
        QuickLitematicaPreview3D.reapAbandonedManagers(screen);
        verify(preview, never()).close();
        assertThat(getField(manager, "current")).isSameAs(preview);

        QuickLitematicaPreview3D.reapAbandonedManagers(mock(Screen.class));
        verify(preview, times(1)).close();
        assertThat(getField(manager, "current")).isNull();
    }

    @Test
    void browserFullscreenRemovalDoesNotCloseTheReusedBrowserManager() throws Exception {
        var preview = mock(QuickLitematicaPreview3D.Preview.class);
        var manager = new QuickLitematicaPreview3D.Manager(null);
        setField(manager, "current", preview);
        var screen = newScreen(manager, false);

        screen.removed();

        verify(preview, never()).close();
        manager.close();
        verify(preview, times(1)).close();
    }

    @Test
    void sameBrowserGuiReusesManagerAndTheMapHoldsOnlyWeakValues() throws Exception {
        var gui = mock(GuiSchematicBrowserBase.class);
        var first = QuickLitematicaPreview3D.init(gui);

        assertThat(QuickLitematicaPreview3D.init(gui)).isSameAs(first);

        Field managersField = QuickLitematicaPreview3D.class.getDeclaredField("MANAGERS");
        managersField.setAccessible(true);
        Map<?, ?> managers = (Map<?, ?>) managersField.get(null);
        assertThat(managers.get(gui)).isInstanceOf(java.lang.ref.WeakReference.class);

        QuickLitematicaPreview3D.close(gui);
        var replacement = QuickLitematicaPreview3D.init(gui);
        assertThat(replacement).isNotSameAs(first);
        QuickLitematicaPreview3D.close(gui);
    }

    private static Screen newColorPicker(QuickLitematicaPreview3DScreen parent) throws Exception {
        Class<?> pickerClass = Class.forName(QuickLitematicaPreview3DScreen.class.getName() + "$ColorPickerScreen");
        var constructor = pickerClass.getDeclaredConstructor(QuickLitematicaPreview3DScreen.class);
        constructor.setAccessible(true);
        var client = mock(MinecraftClient.class);
        setField(client, "textRenderer", mock(TextRenderer.class));
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            when(MinecraftClient.getInstance()).thenReturn(client);
            Screen picker = (Screen) constructor.newInstance(parent);
            setField(picker, "client", client);
            return picker;
        }
    }

    private static QuickLitematicaPreview3DScreen newScreen(
            QuickLitematicaPreview3D.Manager manager,
            boolean closeManagerOnExit
    ) {
        var client = mock(MinecraftClient.class);
        try {
            setField(client, "textRenderer", mock(TextRenderer.class));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            when(MinecraftClient.getInstance()).thenReturn(client);
            return new QuickLitematicaPreview3DScreen(null, "test", manager, closeManagerOnExit);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object getField(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
