package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.LayerMesh;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.LayerKey;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewUploadQueueTest {
    @Test
    void discardReturnsQueuedCapacityButKeepsInFlightCapacityUntilClosedOnce() {
        var queue = new QuickLitematicaPreviewUploadQueue();
        var layer = new LayerMesh(LayerKey.SOLID, new byte[65_536]);
        queue.publish(layer, () -> {});
        queue.publish(layer, () -> {});
        assertThat(queue.availableKiB()).isEqualTo(65_536 - 128);

        var inFlight = queue.poll();
        assertThat(inFlight).isNotNull();
        queue.discard();
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.availableKiB()).isEqualTo(65_536 - 64);
        inFlight.close();
        inFlight.close();
        assertThat(queue.availableKiB()).isEqualTo(65_536);
    }

    @Test
    void cancellationBeforePublicationReturnsReservedCapacityWithoutEnqueueing() {
        var queue = new QuickLitematicaPreviewUploadQueue();
        var checks = new AtomicInteger();
        assertThatThrownBy(() -> queue.publish(new LayerMesh(LayerKey.SOLID, new byte[32]), () -> {
            if (checks.incrementAndGet() == 3) throw new CancellationException();
        })).isInstanceOf(CancellationException.class);
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.availableKiB()).isEqualTo(65_536);
    }

    @Test
    void failedSpillReadReturnsReservedCapacity() {
        var queue = new QuickLitematicaPreviewUploadQueue();
        var unavailable = new LayerMesh(LayerKey.SOLID, null, null, 0, 32);
        assertThatThrownBy(() -> queue.publish(unavailable, () -> {})).isInstanceOf(NullPointerException.class);
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.availableKiB()).isEqualTo(65_536);
    }
}
