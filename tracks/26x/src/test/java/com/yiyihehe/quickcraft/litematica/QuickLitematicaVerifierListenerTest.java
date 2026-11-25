package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import java.lang.ref.WeakReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaVerifierListenerTest {
    @Test
    void guiCallbacksForwardWhileReachableAndDoNotRetainTheGui() throws Exception {
        var gui = mock(GuiSchematicVerifier.class);
        var listener = QuickLitematicaProjectionPersistence.persistentCompletionListener(gui);
        assertThat(listener).isNotSameAs(gui);
        listener.onTaskCompleted();
        listener.onTaskAborted();
        verify(gui).onTaskCompleted();
        verify(gui).onTaskAborted();
        var field = listener.getClass().getDeclaredField("target");
        field.setAccessible(true);
        ((WeakReference<?>) field.get(listener)).clear();
        listener.onTaskCompleted();
        listener.onTaskAborted();
        verifyNoMoreInteractions(gui);
    }

    @Test
    void customListenersAndNullKeepTheirOriginalContract() {
        var custom = mock(ICompletionListener.class);
        assertThat(QuickLitematicaProjectionPersistence.persistentCompletionListener(custom)).isSameAs(custom);
        assertThat(QuickLitematicaProjectionPersistence.persistentCompletionListener(null)).isNull();
    }
}
