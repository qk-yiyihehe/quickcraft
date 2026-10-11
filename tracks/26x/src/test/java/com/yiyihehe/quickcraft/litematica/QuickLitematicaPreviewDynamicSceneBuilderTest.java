package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.BlockEntityData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewDynamicSceneBuilderTest {
    @Test
    void retainsSourceContentWhileTheDynamicSceneIsNotPublished() {
        var blocks = List.of(new BlockEntityData(0, 0, 0, new CompoundTag(), new CompoundTag()));
        var data = new QuickLitematicaPreview3D.MeshData(List.of(), List.of(), blocks, List.of(), 1, 1, 1);
        assertThat(data.hasDynamicContent()).isTrue();
        assertThat(data.dynamicSceneIfReady()).isNull();
        assertThat(data.dynamicSceneProgress()).isZero();
        data.closeDynamic();
        assertThat(data.dynamicSceneIfReady()).isNull();
        assertThat(data.blockEntities().size()).isEqualTo(1);
    }

    @Test
    void completesEmptyInputWithoutAccessingTheClientWorld() {
        var builder = new QuickLitematicaPreviewDynamicSceneBuilder(List.of(), List.of(), List.of());
        assertThat(builder.progress()).isEqualTo(1.0F);
        assertThat(builder.scene().isEmpty()).isTrue();
        assertThat(builder.scene().world() == null).isTrue();
    }

    @Test
    void refusesToExposeAnUnfinishedScene() {
        var blocks = List.of(new BlockEntityData(0, 0, 0, new CompoundTag(), new CompoundTag()));
        var builder = new QuickLitematicaPreviewDynamicSceneBuilder(List.of(), blocks, List.of());
        assertThat(builder.progress()).isZero();
        assertThatThrownBy(builder::scene).isInstanceOf(IllegalStateException.class);
    }
}
