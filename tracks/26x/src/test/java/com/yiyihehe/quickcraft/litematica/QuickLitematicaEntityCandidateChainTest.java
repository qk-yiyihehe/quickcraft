package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JE-R3：廉价预筛先行；4096 限制实际完整构建尝试；排序与结果上限保持。 */
@ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEntityCandidateChainTest {
    @Test
    void denseEvaluationClassifiesEachPairOnceAndRefreshesNextEvaluation() throws Exception {
        var position = new Vec3(0.5, 0, 0.5);
        var expected = new CompoundTag();
        expected.putString("id", "minecraft:armor_stand");
        expected.putBoolean("Small", false);
        var projected = new ArrayList<LitematicaSchematic.EntityInfo>();
        for (int i = 0; i < 6; i++) projected.add(new LitematicaSchematic.EntityInfo(position,
                fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.fromVanillaCompound(expected)));
        var candidates = collect(projected, false);
        assertThat(candidates).hasSize(6);
        var client = mock(Minecraft.class);
        client.level = mock(ClientLevel.class);
        var box = new net.minecraft.world.phys.AABB(0, 0, 0, 1, 2, 1);
        var entities = new ArrayList<net.minecraft.world.entity.Entity>();
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var lookup = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        for (int i = 0; i < 6; i++) {
            var entity = mock(net.minecraft.world.entity.Entity.class);
            org.mockito.Mockito.doReturn(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
                    candidates.getFirst().entityType())).when(entity).getType();
            when(entity.getBoundingBox()).thenReturn(box);
            when(entity.position()).thenReturn(position);
            when(entity.registryAccess()).thenReturn(lookup);
            when(entity.getSelfAndPassengers()).thenAnswer(call -> java.util.stream.Stream.of(entity));
            org.mockito.Mockito.doAnswer(call -> {
                net.minecraft.world.level.storage.ValueOutput output = call.getArgument(0);
                output.putString("id", candidates.getFirst().entityType().toString());
                output.putBoolean("Small", changed.get());
                writes.incrementAndGet();
                return null;
            }).when(entity).saveWithoutId(any(net.minecraft.world.level.storage.ValueOutput.class));
            entities.add(entity);
        }
        when(client.level.getEntities(org.mockito.ArgumentMatchers.<net.minecraft.world.entity.Entity>isNull(),
                any(net.minecraft.world.phys.AABB.class), any())).thenReturn(entities);
        var first = QuickLitematicaEntityPlacement.evaluatePlacement(client, candidates);
        assertThat(first.statuses().values()).containsOnly(QuickLitematicaEntityPlacement.PlacementStatus.MATCHED);
        assertThat(first.excessEntities()).isEmpty();
        assertThat(writes).hasValue(36);
        changed.set(true);
        var next = QuickLitematicaEntityPlacement.evaluatePlacement(client, candidates);
        assertThat(next.statuses().values()).containsOnly(QuickLitematicaEntityPlacement.PlacementStatus.MISMATCHED);
        assertThat(writes).hasValue(72);
    }

    @Test
    void legacyBoatIdsStillReachNativeNormalization() throws Exception {
        for (String id : List.of("minecraft:boat", "minecraft:chest_boat")) {
            assertThat(collect(List.of(entity(id, new Vec3(0.5, 0, 0.5), null)), false)).hasSize(1);
        }
    }

    @Test
    void irrelevantEntitiesAfter4096StillLeaveUniqueRayTarget() throws Exception {
        List<LitematicaSchematic.EntityInfo> entities = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            entities.add(itemEntity(new Vec3(5000.0 + i, 0.0, 5000.0), "minecraft:stone"));
        }
        entities.add(itemEntity(new Vec3(0.5, 0.0, 0.5), "minecraft:diamond"));
        try (var access = mockStatic(QuickLitematicaDataCompat.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var data = mockStatic(QuickLitematicaSchematicData.class)) {
            data.when(() -> QuickLitematicaSchematicData.decodeItem(any(CompoundTag.class), any()))
                    .thenReturn(new ItemStack(Items.DIAMOND));
            List<QuickLitematicaEntityPlacement.Candidate> result = collect(entities, false);
            assertThat(result).hasSize(1);
            // 只有被射线命中的那个实体进入完整材料解码；4096 个准星外实体未触发解码。
            data.verify(() -> QuickLitematicaSchematicData.decodeItem(any(CompoundTag.class), any()), times(1));
            access.verify(() -> QuickLitematicaDataCompat.entityNbt(any()), times(1));
        }
    }

    @Test
    void failedBuildsConsumeBudgetAndCannotBuildUnbounded() throws Exception {
        List<LitematicaSchematic.EntityInfo> entities = new ArrayList<>();
        for (int i = 0; i < 4100; i++) {
            entities.add(itemEntity(new Vec3(0.5, 0.0, 0.5 + i * 0.001), "minecraft:air"));
        }
        try (var data = mockStatic(QuickLitematicaSchematicData.class)) {
            data.when(() -> QuickLitematicaSchematicData.decodeItem(any(CompoundTag.class), any()))
                    .thenReturn(ItemStack.EMPTY);
            List<QuickLitematicaEntityPlacement.Candidate> result = collect(entities, true);
            assertThat(result).isEmpty();
            // 每次完整构建都消耗预算：4100 个候选只允许 4096 次材料解码。
            data.verify(() -> QuickLitematicaSchematicData.decodeItem(any(CompoundTag.class), any()), times(4096));
        }
    }

    @Test
    void rayEntrySortsByDistanceAndLimitsResults() throws Exception {
        List<LitematicaSchematic.EntityInfo> entities = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            entities.add(armorStand(new Vec3(0.5, 0.0, 60.0 - i)));
        }
        List<QuickLitematicaEntityPlacement.Candidate> result = collect(entities, true);
        assertThat(result).hasSize(54);
        assertThat(positionZ(result.getFirst())).isLessThan(positionZ(result.getLast()));
    }

    private static List<QuickLitematicaEntityPlacement.Candidate> collect(
            List<LitematicaSchematic.EntityInfo> entities, boolean rayEntry) throws Exception {
        Minecraft client = mock(Minecraft.class);
        client.player = mock(LocalPlayer.class);
        client.level = mock(ClientLevel.class);
        when(client.level.registryAccess()).thenReturn(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        when(client.player.getEyePosition(1.0F)).thenReturn(new Vec3(0.5, 0.125, -2.0));
        when(client.player.getViewVector(1.0F)).thenReturn(new Vec3(0.0, 0.0, 1.0));
        when(client.player.blockInteractionRange()).thenReturn(64.0D);

        SchematicPlacement placement = mock(SchematicPlacement.class);
        SubRegionPlacement subRegion = mock(SubRegionPlacement.class);
        LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        when(placement.getSchematic()).thenReturn(schematic);
        when(placement.getOrigin()).thenReturn(BlockPos.ZERO);
        when(placement.getMirror()).thenReturn(net.minecraft.world.level.block.Mirror.NONE);
        when(placement.getRotation()).thenReturn(net.minecraft.world.level.block.Rotation.NONE);
        when(placement.getEnabledRelativeSubRegionPlacements())
                .thenReturn(com.google.common.collect.ImmutableMap.of("r", subRegion));
        when(subRegion.getPos()).thenReturn(BlockPos.ZERO);
        when(subRegion.getMirror()).thenReturn(net.minecraft.world.level.block.Mirror.NONE);
        when(subRegion.getRotation()).thenReturn(net.minecraft.world.level.block.Rotation.NONE);
        when(schematic.getEntityListForRegion("r")).thenReturn(entities);

        var manager = mock(fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.class);
        when(manager.getSelectedSchematicPlacement()).thenReturn(placement);
        Class<?> rangeType = DataManager.class.getMethod("getRenderLayerRange").getReturnType();
        Object range = mock(rangeType, invocation ->
                invocation.getMethod().getReturnType() == boolean.class ? Boolean.TRUE : null);

        try (MockedStatic<Minecraft> minecraft = mockStatic(Minecraft.class);
             MockedStatic<DataManager> data = mockStatic(DataManager.class);
             MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
            data.when(DataManager::getRenderLayerRange).thenAnswer(invocation -> range);
            configs.when(QuickCraftConfigs::isEasyPlaceEntitiesEnabled).thenReturn(true);
            configs.when(QuickCraftConfigs::areEasyPlaceEntityContainerContentMaterialsRequired).thenReturn(false);
            configs.when(QuickCraftConfigs::areEasyPlaceEntityPassengerMaterialsRequired).thenReturn(false);
            Method method = rayEntry
                    ? QuickLitematicaEntityPlacement.class.getDeclaredMethod("collectRayCandidates", Minecraft.class)
                    : QuickLitematicaEntityPlacement.class.getDeclaredMethod(
                            "collectCandidates", Minecraft.class, Vec3.class, Vec3.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<QuickLitematicaEntityPlacement.Candidate> result = rayEntry
                    ? (List<QuickLitematicaEntityPlacement.Candidate>) method.invoke(null, client)
                    : (List<QuickLitematicaEntityPlacement.Candidate>) method.invoke(
                            null, client, new Vec3(0.5, 0.125, -2.0), new Vec3(0.5, 0.125, 10.0));
            return result;
        }
    }

    private static double positionZ(QuickLitematicaEntityPlacement.Candidate candidate) throws Exception {
        var field = QuickLitematicaEntityPlacement.Candidate.class.getDeclaredField("position");
        field.setAccessible(true);
        return ((Vec3) field.get(candidate)).z;
    }

    private static LitematicaSchematic.EntityInfo armorStand(Vec3 pos) {
        return entity("minecraft:armor_stand", pos, null);
    }

    private static LitematicaSchematic.EntityInfo itemEntity(Vec3 pos, String itemId) {
        CompoundTag item = new CompoundTag();
        item.putString("id", itemId);
        item.putInt("count", 1);
        return entity("minecraft:item", pos, item);
    }

    private static LitematicaSchematic.EntityInfo entity(String id, Vec3 pos, CompoundTag item) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", id);
        if (id.equals("minecraft:boat") || id.equals("minecraft:chest_boat")) {
            nbt.putString("Type", "oak");
        }
        if (item != null) {
            nbt.put("Item", item);
        }
        return new LitematicaSchematic.EntityInfo(pos, fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.fromVanillaCompound(nbt));
    }
}
