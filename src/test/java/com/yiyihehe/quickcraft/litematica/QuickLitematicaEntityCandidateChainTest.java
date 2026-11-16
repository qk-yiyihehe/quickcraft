package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
        var position = new Vec3d(0.5, 0, 0.5);
        var expected = new NbtCompound();
        expected.putString("id", "minecraft:armor_stand");
        expected.putBoolean("Small", false);
        var projected = new ArrayList<LitematicaSchematic.EntityInfo>();
        for (int i = 0; i < 6; i++) projected.add(entityInfo(position, expected.copy()));
        var candidates = collect(projected, false);
        assertThat(candidates).hasSize(6);
        var client = mock(MinecraftClient.class);
        client.world = mock(ClientWorld.class);
        var box = new net.minecraft.util.math.Box(0, 0, 0, 1, 2, 1);
        var entities = new ArrayList<net.minecraft.entity.Entity>();
        for (int i = 0; i < 6; i++) {
            var entity = mock(net.minecraft.entity.Entity.class);
            org.mockito.Mockito.doReturn(net.minecraft.entity.EntityType.ARMOR_STAND).when(entity).getType();
            when(entity.getBoundingBox()).thenReturn(box);
            when(entity.streamSelfAndPassengers()).thenAnswer(call -> java.util.stream.Stream.of(entity));
            entities.add(entity);
        }
        when(client.world.getOtherEntities(org.mockito.ArgumentMatchers.<net.minecraft.entity.Entity>isNull(), any(net.minecraft.util.math.Box.class), any()))
                .thenReturn(entities);
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        try (var access = mockStatic(QuickLitematicaEntityPlacementAccess.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            access.when(() -> QuickLitematicaEntityPlacementAccess.getEntityPos(any())).thenReturn(position);
            access.when(() -> QuickLitematicaEntityPlacementAccess.writeEntityNbt(any())).thenAnswer(call -> {
                var actual = expected.copy();
                actual.putBoolean("Small", changed.get());
                return actual;
            });
            var first = QuickLitematicaEntityPlacement.evaluatePlacement(client, candidates);
            assertThat(first.statuses().values()).containsOnly(QuickLitematicaEntityPlacement.PlacementStatus.MATCHED);
            assertThat(first.excessEntities()).isEmpty();
            access.verify(() -> QuickLitematicaEntityPlacementAccess.writeEntityNbt(any()), times(36));
            changed.set(true);
            var next = QuickLitematicaEntityPlacement.evaluatePlacement(client, candidates);
            assertThat(next.statuses().values()).containsOnly(QuickLitematicaEntityPlacement.PlacementStatus.MISMATCHED);
            access.verify(() -> QuickLitematicaEntityPlacementAccess.writeEntityNbt(any()), times(72));
        }
    }

    @Test
    void legacyBoatIdsStillReachNativeNormalization() throws Exception {
        for (String id : List.of("minecraft:boat", "minecraft:chest_boat")) {
            assertThat(collect(List.of(entity(id, new Vec3d(0.5, 0, 0.5), null)), false)).hasSize(1);
        }
    }

    @Test
    void unknownRootIsRejectedWhileRegisteredPigRemains() throws Exception {
        assertThat(collect(List.of(entity("unknownmod:missing", new Vec3d(0.5, 0, 0.5), null)), true)).isEmpty();

        var pigs = collect(List.of(entity("minecraft:pig", new Vec3d(0.5, 0, 0.5), null)), true);
        assertThat(pigs).hasSize(1);
        assertThat(pigs.getFirst().entityType()).isEqualTo(net.minecraft.util.Identifier.of("minecraft", "pig"));
        assertThat(pigs.getFirst().material().isOf(Items.PIG_SPAWN_EGG)).isTrue();
    }

    @Test
    void unknownPassengerRejectsTheWholeTreeWhenPassengerMaterialsAreRequired() throws Exception {
        var unknownPassenger = entityWithPassenger("minecraft:armor_stand", "unknownmod:missing",
                new Vec3d(0.5, 0, 0.5));
        assertThat(collect(List.of(unknownPassenger), true, true)).isEmpty();

        var knownPassenger = entityWithPassenger("minecraft:armor_stand", "minecraft:pig",
                new Vec3d(0.5, 0, 0.5));
        assertThat(collect(List.of(knownPassenger), true, true)).hasSize(1);
    }

    @Test
    void irrelevantEntitiesAfter4096StillLeaveUniqueRayTarget() throws Exception {
        List<LitematicaSchematic.EntityInfo> entities = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            entities.add(itemEntity(new Vec3d(5000.0 + i, 0.0, 5000.0), "minecraft:stone"));
        }
        entities.add(itemEntity(new Vec3d(0.5, 0.0, 0.5), "minecraft:diamond"));
        try (var access = mockStatic(QuickLitematicaEntityPlacementAccess.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var data = mockStatic(QuickLitematicaSchematicData.class)) {
            data.when(() -> QuickLitematicaSchematicData.decodeItem(any(NbtCompound.class), any()))
                    .thenReturn(new ItemStack(Items.DIAMOND));
            List<QuickLitematicaEntityPlacement.Candidate> result = collect(entities, false);
            assertThat(result).hasSize(1);
            // 只有被射线命中的那个实体进入完整材料解码；4096 个准星外实体未触发解码。
            data.verify(() -> QuickLitematicaSchematicData.decodeItem(any(NbtCompound.class), any()), times(1));
            access.verify(() -> QuickLitematicaEntityPlacementAccess.getSchematicEntityNbt(any()), times(1));
        }
    }

    @Test
    void failedBuildsConsumeBudgetAndCannotBuildUnbounded() throws Exception {
        List<LitematicaSchematic.EntityInfo> entities = new ArrayList<>();
        for (int i = 0; i < 4100; i++) {
            entities.add(itemEntity(new Vec3d(0.5, 0.0, 0.5 + i * 0.001), "minecraft:air"));
        }
        try (var data = mockStatic(QuickLitematicaSchematicData.class)) {
            data.when(() -> QuickLitematicaSchematicData.decodeItem(any(NbtCompound.class), any()))
                    .thenReturn(ItemStack.EMPTY);
            List<QuickLitematicaEntityPlacement.Candidate> result = collect(entities, true);
            assertThat(result).isEmpty();
            // 每次完整构建都消耗预算：4100 个候选只允许 4096 次材料解码。
            data.verify(() -> QuickLitematicaSchematicData.decodeItem(any(NbtCompound.class), any()), times(4096));
        }
    }

    @Test
    void rayEntrySortsByDistanceAndLimitsResults() throws Exception {
        List<LitematicaSchematic.EntityInfo> entities = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            entities.add(armorStand(new Vec3d(0.5, 0.0, 60.0 - i)));
        }
        List<QuickLitematicaEntityPlacement.Candidate> result = collect(entities, true);
        assertThat(result).hasSize(54);
        assertThat(positionZ(result.getFirst())).isLessThan(positionZ(result.getLast()));
    }

    private static List<QuickLitematicaEntityPlacement.Candidate> collect(
            List<LitematicaSchematic.EntityInfo> entities, boolean rayEntry) throws Exception {
        return collect(entities, rayEntry, false);
    }

    private static List<QuickLitematicaEntityPlacement.Candidate> collect(
            List<LitematicaSchematic.EntityInfo> entities, boolean rayEntry, boolean requirePassengerMaterials) throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        client.player = mock(ClientPlayerEntity.class);
        client.world = mock(ClientWorld.class);
        when(client.world.getRegistryManager()).thenReturn(net.minecraft.registry.DynamicRegistryManager.of(net.minecraft.registry.Registries.REGISTRIES));
        when(client.player.getInventory()).thenReturn(com.yiyihehe.quickcraft.QuickCraftTestInventories.newPlayerInventory(client.player));
        when(client.player.getCameraPosVec(1.0F)).thenReturn(new Vec3d(0.5, 0.125, -2.0));
        when(client.player.getRotationVec(1.0F)).thenReturn(new Vec3d(0.0, 0.0, 1.0));
        when(client.player.getBlockInteractionRange()).thenReturn(64.0D);

        SchematicPlacement placement = mock(SchematicPlacement.class);
        SubRegionPlacement subRegion = mock(SubRegionPlacement.class);
        LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        when(placement.getSchematic()).thenReturn(schematic);
        when(placement.getOrigin()).thenReturn(BlockPos.ORIGIN);
        when(placement.getMirror()).thenReturn(net.minecraft.util.BlockMirror.NONE);
        when(placement.getRotation()).thenReturn(net.minecraft.util.BlockRotation.NONE);
        when(placement.getEnabledRelativeSubRegionPlacements())
                .thenReturn(com.google.common.collect.ImmutableMap.of("r", subRegion));
        when(subRegion.getPos()).thenReturn(BlockPos.ORIGIN);
        when(subRegion.getMirror()).thenReturn(net.minecraft.util.BlockMirror.NONE);
        when(subRegion.getRotation()).thenReturn(net.minecraft.util.BlockRotation.NONE);
        when(schematic.getEntityListForRegion("r")).thenReturn(entities);

        var manager = mock(fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.class);
        when(manager.getSelectedSchematicPlacement()).thenReturn(placement);
        Class<?> rangeType = DataManager.class.getMethod("getRenderLayerRange").getReturnType();
        Object range = mock(rangeType, invocation ->
                invocation.getMethod().getReturnType() == boolean.class ? Boolean.TRUE : null);

        try (MockedStatic<MinecraftClient> minecraft = mockStatic(MinecraftClient.class);
             MockedStatic<DataManager> data = mockStatic(DataManager.class);
             MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
            data.when(DataManager::getRenderLayerRange).thenAnswer(invocation -> range);
            configs.when(QuickCraftConfigs::isEasyPlaceEntitiesEnabled).thenReturn(true);
            configs.when(QuickCraftConfigs::areEasyPlaceEntityContainerContentMaterialsRequired).thenReturn(false);
            configs.when(QuickCraftConfigs::areEasyPlaceEntityPassengerMaterialsRequired)
                    .thenReturn(requirePassengerMaterials);
            Method method = rayEntry
                    ? QuickLitematicaEntityPlacement.class.getDeclaredMethod("collectRayCandidates", MinecraftClient.class)
                    : QuickLitematicaEntityPlacement.class.getDeclaredMethod(
                            "collectCandidates", MinecraftClient.class, Vec3d.class, Vec3d.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<QuickLitematicaEntityPlacement.Candidate> result = rayEntry
                    ? (List<QuickLitematicaEntityPlacement.Candidate>) method.invoke(null, client)
                    : (List<QuickLitematicaEntityPlacement.Candidate>) method.invoke(
                            null, client, new Vec3d(0.5, 0.125, -2.0), new Vec3d(0.5, 0.125, 10.0));
            return result;
        }
    }

    private static double positionZ(QuickLitematicaEntityPlacement.Candidate candidate) throws Exception {
        var field = QuickLitematicaEntityPlacement.Candidate.class.getDeclaredField("position");
        field.setAccessible(true);
        return ((Vec3d) field.get(candidate)).z;
    }

    private static LitematicaSchematic.EntityInfo armorStand(Vec3d pos) {
        return entity("minecraft:armor_stand", pos, null);
    }

    private static LitematicaSchematic.EntityInfo itemEntity(Vec3d pos, String itemId) {
        NbtCompound item = new NbtCompound();
        item.putString("id", itemId);
        item.putInt("count", 1);
        return entity("minecraft:item", pos, item);
    }

    private static LitematicaSchematic.EntityInfo entity(String id, Vec3d pos, NbtCompound item) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", id);
        if (id.equals("minecraft:boat") || id.equals("minecraft:chest_boat")) {
            nbt.putString("Type", "oak");
        }
        if (item != null) {
            nbt.put("Item", item);
        }
        return entityInfo(pos, nbt);
    }

    private static LitematicaSchematic.EntityInfo entityWithPassenger(String id, String passengerId, Vec3d pos) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", id);
        NbtCompound passenger = new NbtCompound();
        passenger.putString("id", passengerId);
        net.minecraft.nbt.NbtList passengers = new net.minecraft.nbt.NbtList();
        passengers.add(passenger);
        nbt.put("Passengers", passengers);
        return entityInfo(pos, nbt);
    }

    private static LitematicaSchematic.EntityInfo entityInfo(Vec3d pos, NbtCompound nbt) {
        // 1.21.11 的同一依赖范围同时允许原生 NBT 与 MaLiLib CompoundData。
        try {
            for (var constructor : LitematicaSchematic.EntityInfo.class.getConstructors()) {
                if (constructor.getParameterCount() == 2 && constructor.getParameterTypes()[0] == Vec3d.class) {
                    Object data = constructor.getParameterTypes()[1] == NbtCompound.class ? nbt
                            : Class.forName("fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt")
                            .getMethod("fromVanillaCompound", NbtCompound.class).invoke(null, nbt);
                    return (LitematicaSchematic.EntityInfo) constructor.newInstance(pos, data);
                }
            }
            throw new AssertionError("No EntityInfo constructor");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
