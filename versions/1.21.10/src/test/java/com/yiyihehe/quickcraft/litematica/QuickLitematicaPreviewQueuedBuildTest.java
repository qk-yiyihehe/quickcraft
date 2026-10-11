package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.render.VertexConsumerProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class QuickLitematicaPreviewQueuedBuildTest {
    @Test
    void resumesRecordsBeforeTheNextNativePhaseAndClearsLast() {
        var owner = mock(VertexConsumerProvider.Immediate.class);
        var foreign = mock(VertexConsumerProvider.Immediate.class);
        var clock = new AtomicLong();
        var events = new ArrayList<String>();
        var groups = new LinkedHashMap<String, List<Integer>>();
        groups.put("empty", List.of());
        groups.put("opaque", List.of(1, 2));
        var build = new QuickLitematicaPreviewQueuedBuild(submit -> {
            submit.accept(() -> {
                assertThat(QuickLitematicaPreviewQueuedBuild.isCapturing(owner)).isTrue();
                assertThat(QuickLitematicaPreviewQueuedBuild.defer(foreign, () -> {
                    events.add("foreign");
                    return true;
                })).isFalse();
                QuickLitematicaPreviewQueuedBuild.deferEntries(owner, groups.entrySet(), material -> {
                    events.add("consumer:" + material);
                    return record -> events.add(material + record);
                });
                QuickLitematicaPreviewQueuedBuild.deferList(owner, List.of(3, 4), record -> events.add("blended" + record));
            });
            submit.accept(() -> events.add("item"));
            submit.accept(() -> events.add("clear"));
        }, owner, () -> clock.getAndAdd(QuickLitematicaPreviewBatchBudget.TARGET_NANOS));
        int slices = 0;
        while (!build.advance()) {
            assertThat(++slices).isLessThan(20);
            assertThat(QuickLitematicaPreviewQueuedBuild.isCapturing(owner)).isFalse();
            assertThat(events).doesNotContain("clear");
        }
        assertThat(slices).isGreaterThan(3);
        assertThat(events).containsExactly("consumer:empty", "consumer:opaque", "opaque1", "opaque2",
                "blended3", "blended4", "item", "clear");
        assertThat(QuickLitematicaPreviewQueuedBuild.defer(owner, () -> true)).isFalse();
        build.close();
    }

    @Test
    void closeDropsPendingGeometryAndQueueClear() {
        var owner = mock(VertexConsumerProvider.Immediate.class);
        var events = new ArrayList<Integer>();
        var clock = new AtomicLong();
        var build = new QuickLitematicaPreviewQueuedBuild(submit -> {
            submit.accept(() -> QuickLitematicaPreviewQueuedBuild.deferList(owner, List.of(1, 2), events::add));
            submit.accept(() -> events.add(3));
        }, owner, () -> clock.getAndAdd(QuickLitematicaPreviewBatchBudget.TARGET_NANOS));
        assertThat(build.advance()).isFalse();
        assertThat(events).isEmpty();
        build.close();
        assertThat(build.advance()).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    void failureRestoresCaptureBeforeOwnerCleanup() {
        var owner = mock(VertexConsumerProvider.Immediate.class);
        var events = new ArrayList<Integer>();
        var build = new QuickLitematicaPreviewQueuedBuild(submit -> {
            submit.accept(() -> {
                QuickLitematicaPreviewQueuedBuild.deferList(owner, List.of(1), events::add);
                throw new IllegalStateException("native failure");
            });
            submit.accept(() -> events.add(2));
        }, owner);
        assertThatThrownBy(build::advance).isInstanceOf(IllegalStateException.class);
        assertThat(QuickLitematicaPreviewQueuedBuild.isCapturing(owner)).isFalse();
        build.close();
        assertThat(build.advance()).isTrue();
        assertThat(events).isEmpty();
    }
}
