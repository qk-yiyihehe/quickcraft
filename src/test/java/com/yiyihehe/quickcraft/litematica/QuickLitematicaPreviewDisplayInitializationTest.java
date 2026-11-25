package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** BUG-70：预览只初始化实际展示实体的客户端渲染状态。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaPreviewDisplayInitializationTest {
    @Test
    void nbtEntityInstantiationInitializesTheRealDisplay() {
        var world = mock(QuickLitematicaPreviewAccess.DummyWorld.class);
        setClientWorld(world);
        when(world.isClient()).thenReturn(true);
        when(world.getEnabledFeatures()).thenReturn(
                net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES
        );
        var registries = net.minecraft.registry.DynamicRegistryManager.of(net.minecraft.registry.Registries.REGISTRIES);
        when(world.getRegistryManager()).thenReturn(registries);
        try {
            // 早期 NBT 解码从 World 的查找入口读取方块注册表，后期改为读取视图。
            var lookup = world.getClass().getMethod(
                    "createCommandRegistryWrapper",
                    net.minecraft.registry.RegistryKey.class
            );
            var registryLookup = java.util.Arrays.stream(
                    net.minecraft.registry.DynamicRegistryManager.class.getMethods()
            ).filter(
                    method -> (method.getName().equals("getWrapperOrThrow") || method.getName().equals("getOrThrow"))
                            && java.util.Arrays.equals(
                                    method.getParameterTypes(),
                                    new Class<?>[] { net.minecraft.registry.RegistryKey.class }
                            )
            ).findFirst().orElseThrow();
            lookup.invoke(
                    org.mockito.Mockito.doReturn(
                            registryLookup.invoke(registries, net.minecraft.registry.RegistryKeys.BLOCK)
                    ).when(world),
                    net.minecraft.registry.RegistryKeys.BLOCK
            );
        } catch (NoSuchMethodException ignored) {} catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        var client = mock(net.minecraft.client.MinecraftClient.class);
        when(client.getEntityRenderDispatcher()).thenReturn(
                mock(net.minecraft.client.render.entity.EntityRenderDispatcher.class)
        );
        var nbt = new net.minecraft.nbt.NbtCompound();
        nbt.putString("id", "minecraft:block_display");
        var block = new net.minecraft.nbt.NbtCompound();
        block.putString("Name", "minecraft:stone");
        nbt.put("block_state", block);
        var pos = new net.minecraft.nbt.NbtList();
        var motion = new net.minecraft.nbt.NbtList();
        var rotation = new net.minecraft.nbt.NbtList();
        for (int i = 0; i < 3; i++) {
            pos.add(net.minecraft.nbt.NbtDouble.of(0.0));
            motion.add(net.minecraft.nbt.NbtDouble.of(0.0));
        }
        rotation.add(net.minecraft.nbt.NbtFloat.of(0.0F));
        rotation.add(net.minecraft.nbt.NbtFloat.of(0.0F));
        nbt.put("Pos", pos);
        nbt.put("Motion", motion);
        nbt.put("Rotation", rotation);

        try (var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.MinecraftClient.class)) {
            minecraft.when(net.minecraft.client.MinecraftClient::getInstance).thenReturn(client);
            var rendered = new QuickLitematicaPreviewAccess.EntityData(2.0, 3.0, 4.0, nbt).instantiate(world);
            assertThat(rendered).isNotNull();
            assertThat(rendered.entity()).isInstanceOf(DisplayEntity.BlockDisplayEntity.class);
            var display = (DisplayEntity.BlockDisplayEntity) rendered.entity();
            assertThat(display.getRenderState()).isNotNull();
            assertThat(display.getData().blockState()).isEqualTo(Blocks.STONE.getDefaultState());
            assertThat(display.getX()).isEqualTo(2.0);
        }
    }

    @Test
    void blockDisplayGetsRenderStateFromPreviewPreparation() {
        ClientWorld world = mock(ClientWorld.class);
        setClientWorld(world);
        when(world.isClient()).thenReturn(true);
        DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(
                EntityType.BLOCK_DISPLAY,
                world
        );
        setBlockState(display, Blocks.STONE.getDefaultState());
        setTransformation(display);

        assertThat(display.getRenderState()).isNull();
        QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(display, 0.0, 0.0, 0.0);

        assertThat(display.getRenderState()).isNotNull();
        assertThat(display.getData()).isNotNull();
        assertThat(display.getData().blockState()).isEqualTo(Blocks.STONE.getDefaultState());
    }

    private static void setClientWorld(net.minecraft.world.World world) {
        try {
            var field = net.minecraft.world.World.class.getDeclaredField("isClient");
            field.setAccessible(true);
            field.setBoolean(world, true);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setTransformation(DisplayEntity.BlockDisplayEntity display) {
        try {
            var method = DisplayEntity.class.getDeclaredMethod("setTransformation", AffineTransformation.class);
            method.setAccessible(true);
            method.invoke(display, AffineTransformation.identity());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setBlockState(DisplayEntity.BlockDisplayEntity display, net.minecraft.block.BlockState state) {
        try {
            var method = DisplayEntity.BlockDisplayEntity.class.getDeclaredMethod(
                    "setBlockState",
                    net.minecraft.block.BlockState.class
            );
            method.setAccessible(true);
            method.invoke(display, state);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void repeatedPreparationKeepsTheInitializedRenderState() {
        ClientWorld world = mock(ClientWorld.class);
        setClientWorld(world);
        when(world.isClient()).thenReturn(true);
        DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(
                EntityType.BLOCK_DISPLAY,
                world
        );
        setBlockState(display, Blocks.STONE.getDefaultState());
        setTransformation(display);

        var prepared = org.mockito.Mockito.spy(display);
        QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(prepared, 0.0, 0.0, 0.0);
        DisplayEntity.RenderState first = prepared.getRenderState();
        QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(prepared, 0.0, 0.0, 0.0);

        assertThat(prepared.getRenderState()).isSameAs(first);
        verify(prepared, org.mockito.Mockito.times(1)).tick();
    }

    @Test
    void ordinaryEntityIsNotTickedByPreviewPreparation() {
        Entity entity = mock(Entity.class);

        QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(entity, 0.0, 0.0, 0.0);

        verify(entity, never()).tick();
    }
}
