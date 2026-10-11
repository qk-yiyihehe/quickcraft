package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.ScissorState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import java.util.List;

class QuickLitematicaPreviewRenderTypesTest extends QuickLitematicaPreviewRenderTypesChecks {
    @Override
    PreparedRenderType prepared(ScissorState scissor, List<PreparedRenderType.Texture> textures) {
        return new PreparedRenderType(null, null, null, scissor, textures);
    }
}
