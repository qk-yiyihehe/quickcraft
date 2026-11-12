package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaSaveContextTest {
    @Test
    void nestedFailureRestoresOuterReadAndKeepsOtherThreadsUnaffected() throws InterruptedException {
        WorldSchematic world = mock(WorldSchematic.class);
        BlockPos outerPos = new BlockPos(1, 2, 3);
        try (var handler = mockStatic(SchematicWorldHandler.class)) {
            handler.when(SchematicWorldHandler::getSchematicWorld).thenReturn(world);
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
    }

    @Test
    void readIsLimitedToTheSameWorldAndAnImmutablePosition() {
        WorldSchematic world = mock(WorldSchematic.class);
        WorldSchematic otherWorld = mock(WorldSchematic.class);
        BlockPos.Mutable cursor = new BlockPos.Mutable(1, 2, 3);
        try (var handler = mockStatic(SchematicWorldHandler.class)) {
            handler.when(SchematicWorldHandler::getSchematicWorld).thenReturn(world);
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

    @Test
    void activeProjectionWithoutPlacementsStillAcceptsReadContext() {
        WorldSchematic active = mock(WorldSchematic.class);
        BlockPos pos = new BlockPos(1, 2, 3);
        SchematicPlacementManager manager = mock(SchematicPlacementManager.class);
        when(manager.getAllPlacementsTouchingChunk(pos)).thenReturn(List.of());

        try (var handler = mockStatic(SchematicWorldHandler.class);
             var data = mockStatic(DataManager.class)) {
            handler.when(SchematicWorldHandler::getSchematicWorld).thenReturn(active);
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);

            var previous = QuickLitematicaSaveContext.begin(active, pos);
            try {
                assertThat(QuickLitematicaSaveContext.contains(active, pos)).isTrue();
                assertThat(QuickLitematicaGlobalReplacements.baselineWorldState(pos))
                        .isEqualTo(net.minecraft.block.Blocks.AIR.getDefaultState());
            } finally {
                QuickLitematicaSaveContext.end(previous);
            }
        }
    }

    @Test
    void temporaryConversionWorldIsNotTreatedAsTheActiveProjection() {
        WorldSchematic active = mock(WorldSchematic.class);
        WorldSchematic temporary = mock(WorldSchematic.class);
        BlockPos pos = new BlockPos(1, 2, 3);
        try (var handler = mockStatic(SchematicWorldHandler.class)) {
            handler.when(SchematicWorldHandler::getSchematicWorld).thenReturn(active);

            var previousTemporary = QuickLitematicaSaveContext.begin(temporary, pos);
            try {
                assertThat(QuickLitematicaSaveContext.contains(temporary, pos)).isFalse();
                assertThat(QuickLitematicaSaveContext.contains(active, pos)).isFalse();
            } finally {
                QuickLitematicaSaveContext.end(previousTemporary);
            }

            var previousActive = QuickLitematicaSaveContext.begin(active, pos);
            try {
                assertThat(QuickLitematicaSaveContext.contains(active, pos)).isTrue();
            } finally {
                QuickLitematicaSaveContext.end(previousActive);
            }
        }
    }
}
