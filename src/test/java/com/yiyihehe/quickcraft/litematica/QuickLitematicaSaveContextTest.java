package com.yiyihehe.quickcraft.litematica;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaSaveContextTest {
    @Test
    void nestedFailureRestoresOuterReadAndKeepsOtherThreadsUnaffected() throws InterruptedException {
        World world = mock(World.class);
        BlockPos outerPos = new BlockPos(1, 2, 3);
        var previous = QuickLitematicaSaveContext.begin(world, outerPos);
        try {
            assertThatThrownBy(() -> {
                var nested = QuickLitematicaSaveContext.begin(world, new BlockPos(4, 5, 6));
                try {
                    assertThat(QuickLitematicaSaveContext.contains(world, outerPos)).isFalse();
                    throw new IllegalStateException("save filter failed");
                } finally {
                    QuickLitematicaSaveContext.end(nested);
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(QuickLitematicaSaveContext.contains(world, outerPos)).isTrue();
            AtomicBoolean otherThreadRead = new AtomicBoolean(true);
            Thread worker = new Thread(() -> otherThreadRead.set(QuickLitematicaSaveContext.contains(world, outerPos)));
            worker.start();
            worker.join(2_000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(otherThreadRead.get()).isFalse();
        } finally {
            QuickLitematicaSaveContext.end(previous);
        }
        assertThat(QuickLitematicaSaveContext.contains(world, outerPos)).isFalse();
    }

    @Test
    void readIsLimitedToTheSameWorldAndAnImmutablePosition() {
        World world = mock(World.class);
        World otherWorld = mock(World.class);
        BlockPos.Mutable cursor = new BlockPos.Mutable(1, 2, 3);
        var previous = QuickLitematicaSaveContext.begin(world, cursor);
        try {
            cursor.set(4, 5, 6);
            assertThat(QuickLitematicaSaveContext.contains(world, new BlockPos(1, 2, 3))).isTrue();
            assertThat(QuickLitematicaSaveContext.contains(world, cursor)).isFalse();
            assertThat(QuickLitematicaSaveContext.contains(otherWorld, new BlockPos(1, 2, 3))).isFalse();
        } finally {
            QuickLitematicaSaveContext.end(previous);
        }
    }
}
