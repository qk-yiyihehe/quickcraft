package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMemory.Mode9Edit;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.SchematicUtils;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;

@Mixin(value = SchematicUtils.class, remap = false)
public abstract class LitematicaMode9EditMixin {
    // 两个同名入口还有委托重载，先匹配全部重载，再由调用点选中实际编辑的方法。
    @WrapOperation(
            method = { "setTargetedSchematicBlockState*", "setSchematicBlockStates", "replaceAllIdenticalBlocks",
                    "setAllStatesToAirExcept*" },
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/schematic/LitematicaSchematic;getSubRegionContainer(Ljava/lang/String;)Lfi/dy/masa/litematica/schematic/container/LitematicaBlockStateContainer;"),
            require = 4)
    private static LitematicaBlockStateContainer quickcraft$selectEditedRegion(
            LitematicaSchematic schematic,
            String region,
            Operation<LitematicaBlockStateContainer> original,
            @Share("edit") LocalRef<Mode9Edit> edit
    ) {
        var live = original.call(schematic, region);
        if (live != null) {
            if (edit.get() == null) {
                edit.set(new Mode9Edit(schematic));
            }
            edit.get().selectRegion(region);
        }
        return live;
    }

    @WrapOperation(
            method = { "replaceAllIdenticalBlocks", "setAllStatesToAirExcept*" },
            at = @At(
                    value = "INVOKE",
                    desc = @Desc(
                            owner = LitematicaBlockStateContainer.class,
                            value = "get",
                            args = { int.class, int.class, int.class },
                            ret = BlockState.class)),
            require = 2)
    private static BlockState quickcraft$matchDisplayedBlock(
            LitematicaBlockStateContainer live,
            int x,
            int y,
            int z,
            Operation<BlockState> original,
            @Share("edit") LocalRef<Mode9Edit> edit
    ) {
        return original.call(edit.get().displayedRegion(live), x, y, z);
    }

    @WrapOperation(
            method = { "setTargetedSchematicBlockState*", "setSchematicBlockStates", "replaceAllIdenticalBlocks",
                    "setAllStatesToAirExcept*" },
            at = @At(
                    value = "INVOKE",
                    desc = @Desc(
                            owner = LitematicaBlockStateContainer.class,
                            value = "set",
                            args = { int.class, int.class, int.class, BlockState.class })),
            require = 4)
    private static void quickcraft$recordMode9Write(
            LitematicaBlockStateContainer live,
            int x,
            int y,
            int z,
            BlockState target,
            Operation<Void> original,
            @Share("edit") LocalRef<Mode9Edit> edit
    ) {
        boolean liveChanged = live.get(x, y, z) != target;
        original.call(live, x, y, z, target);
        edit.get().record(x, y, z, target, liveChanged);
    }

    @WrapOperation(
            method = { "setTargetedSchematicBlockState*", "setSchematicBlockStates", "replaceAllIdenticalBlocks",
                    "setAllStatesToAirExcept*" },
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/schematic/SchematicMetadata;setTotalBlocks(I)V"),
            require = 4)
    private static void quickcraft$finishMode9Edit(
            SchematicMetadata metadata,
            int totalBlocks,
            Operation<Void> original,
            @Share("edit") LocalRef<Mode9Edit> edit
    ) {
        original.call(metadata, totalBlocks);
        if (edit.get() != null) {
            // 26.x 可在工作线程消费随后的重建请求，必须先使旧替换副本失效。
            edit.get().finish(totalBlocks);
        }
    }
}
