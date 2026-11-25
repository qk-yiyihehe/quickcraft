package com.yiyihehe.quickcraft.litematica;

import com.mojang.math.Transformation;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BUG-70：预览只初始化实际展示实体的客户端渲染状态。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaPreviewDisplayInitializationTest {
    @Test
    void nbtEntityInstantiationInitializesTheRealDisplay() {
        var world = mock(QuickLitematicaPreview3D.DummyWorld.class);
        when(world.isClientSide()).thenReturn(true);
        when(world.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
        when(world.registryAccess()).thenReturn(
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                        net.minecraft.core.registries.BuiltInRegistries.REGISTRY
                )
        );
        var client = mock(net.minecraft.client.Minecraft.class);
        when(client.getEntityRenderDispatcher()).thenReturn(
                mock(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class)
        );
        var nbt = new net.minecraft.nbt.CompoundTag();
        nbt.putString("id", "minecraft:block_display");
        nbt.put(
                "block_state",
                net.minecraft.world.level.block.state.BlockState.CODEC.encodeStart(
                        net.minecraft.nbt.NbtOps.INSTANCE,
                        Blocks.STONE.defaultBlockState()
                ).getOrThrow()
        );
        var pos = new net.minecraft.nbt.ListTag();
        var motion = new net.minecraft.nbt.ListTag();
        var rotation = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < 3; i++) {
            pos.add(net.minecraft.nbt.DoubleTag.valueOf(0.0));
            motion.add(net.minecraft.nbt.DoubleTag.valueOf(0.0));
        }
        rotation.add(net.minecraft.nbt.FloatTag.valueOf(0.0F));
        rotation.add(net.minecraft.nbt.FloatTag.valueOf(0.0F));
        nbt.put("Pos", pos);
        nbt.put("Motion", motion);
        nbt.put("Rotation", rotation);

        try (var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
            var rendered = new QuickLitematicaPreview3D.EntityData(2.0, 3.0, 4.0, nbt).instantiate(world);
            assertThat(rendered).isNotNull();
            assertThat(rendered.entity()).isInstanceOf(Display.BlockDisplay.class);
            var display = (Display.BlockDisplay) rendered.entity();
            assertThat(display.level()).isSameAs(world);
            assertThat(display.getBlockState()).isEqualTo(Blocks.STONE.defaultBlockState());
            assertThat(display.renderState()).isNotNull();
            assertThat(display.blockRenderState().blockState()).isEqualTo(Blocks.STONE.defaultBlockState());
            assertThat(display.getX()).isEqualTo(2.0);
        }
    }

    @Test
    void blockDisplayGetsRenderStateFromPreviewPreparation() {
        ClientLevel world = mock(ClientLevel.class);
        when(world.isClientSide()).thenReturn(true);
        Display.BlockDisplay display = new Display.BlockDisplay(blockDisplayType(), world);
        display.setBlockState(Blocks.STONE.defaultBlockState());
        display.setTransformation(Transformation.IDENTITY);

        assertThat(display.renderState()).isNull();
        QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(display, 0.0, 0.0, 0.0);

        assertThat(display.renderState()).isNotNull();
        assertThat(display.blockRenderState()).isNotNull();
        assertThat(display.blockRenderState().blockState()).isEqualTo(Blocks.STONE.defaultBlockState());
    }

    @SuppressWarnings("unchecked")
    private static EntityType<Display.BlockDisplay> blockDisplayType() {
        try {
            try {
                return (EntityType<Display.BlockDisplay>) EntityType.class.getField("BLOCK_DISPLAY").get(null);
            } catch (NoSuchFieldException ignored) {
                return (EntityType<Display.BlockDisplay>) Class.forName("net.minecraft.world.entity.EntityTypes")
                        .getField("BLOCK_DISPLAY").get(null);
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void repeatedPreparationKeepsTheInitializedRenderState() {
        ClientLevel world = mock(ClientLevel.class);
        when(world.isClientSide()).thenReturn(true);
        Display.BlockDisplay display = new Display.BlockDisplay(blockDisplayType(), world);
        display.setBlockState(Blocks.STONE.defaultBlockState());
        display.setTransformation(Transformation.IDENTITY);

        var prepared = org.mockito.Mockito.spy(display);
        QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(prepared, 0.0, 0.0, 0.0);
        Display.RenderState first = prepared.renderState();
        QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(prepared, 0.0, 0.0, 0.0);

        assertThat(prepared.renderState()).isSameAs(first);
        verify(prepared, org.mockito.Mockito.times(1)).tick();
    }

    @Test
    void ordinaryEntityIsNotTickedByPreviewPreparation() {
        Entity entity = mock(Entity.class);

        QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(entity, 0.0, 0.0, 0.0);

        verify(entity, never()).tick();
    }
}
