package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntityDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** BUG-01/71：缓存缺键、可靠空库存、双箱边界和合成器锁槽来源必须分开判断。 */
@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaContainerDataSourceTest {
    private static final BlockPos POS = new BlockPos(2, 64, 3);
    @org.junit.jupiter.api.io.TempDir
    static Path fixtureDirectory;

    @org.junit.jupiter.api.BeforeAll
    static void initializeDependencyWithAClientDirectory() throws Exception {
        try (var minecraft = mockStatic(net.minecraft.client.Minecraft.class)) {
            var client = clientWithDirectory();
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
            // 先于其他数据源 mock 初始化真实配置，默认目录只落在本类临时目录中。
            Class.forName("fi.dy.masa.litematica.config.Configs$Generic");
            receiptStorageType();
        }
    }


    @Test
    void missingCacheEntryIsUnknownButReliableEmptyIsAccepted() {
        try (var fixture = fixture()) {
            fixture.respond(null);
            assertThat(QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world, POS, new SimpleContainer(27), new SimpleContainer(27))).isNull();

            CompoundTag empty = new CompoundTag();
            empty.putString("id", "minecraft:chest");
            fixture.respond(empty);
            Container actual = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world, POS, new SimpleContainer(27), new SimpleContainer(27));
            assertThat(actual).isNotNull();
            assertThat(actual.isEmpty()).isTrue();
            assertThat(QuickLitematicaContainerInventory.getLastActualInventoryReadStatus())
                    .isEqualTo(QuickLitematicaContainerVerifier.ActualInventoryReadStatus.CACHE_INVENTORY);
        }
    }

    @Test
    void laterNonEmptyCacheDataReplacesTheTrustedEmptySnapshot() {
        try (var fixture = fixture()) {
            CompoundTag empty = new CompoundTag();
            empty.putString("id", "minecraft:chest");
            fixture.respond(empty);
            Container emptyActual = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world, POS, new SimpleContainer(27), new SimpleContainer(27));
            assertThat(emptyActual).isNotNull();
            assertThat(emptyActual.isEmpty()).isTrue();

            fixture.respond(items("minecraft:stone", 1));
            Container actual = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world, POS, new SimpleContainer(27), new SimpleContainer(27));
            assertThat(actual).isNotNull();
            assertThat(actual.getItem(0).is(Items.STONE)).isTrue();
        }
    }

    @Test
    void doubleChestEmptyShortcutRequiresBothReliableHalvesAndKeepsSlotOrder() {
        var state = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.TYPE, ChestType.LEFT)
                .setValue(ChestBlock.FACING, Direction.NORTH);
        SimpleContainer expected = new SimpleContainer(54);
        SimpleContainer direct = new SimpleContainer(54);

        try (var fixture = fixture()) {
            BlockPos paired = ChestBlock.getConnectedBlockPos(POS, state);
            when(fixture.world.getBlockState(POS)).thenReturn(state);
            when(fixture.world.getBlockState(paired)).thenReturn(state.setValue(ChestBlock.TYPE, ChestType.RIGHT));

            fixture.respond(new CompoundTag());
            fixture.respondPaired(null);
            assertThat(QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world, POS, direct, expected)).isNull();
            org.mockito.Mockito.verify(EntityDataManager.getInstance())
                    .requestBlockEntityWrapped(fixture.world, paired);

            fixture.respondPaired(new CompoundTag());
            Container bothEmpty = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world, POS, direct, expected);
            assertThat(bothEmpty).isNotNull();
            assertThat(bothEmpty.getContainerSize()).isEqualTo(54);
            assertThat(bothEmpty.isEmpty()).isTrue();

            fixture.respond(new CompoundTag());
            fixture.respondPaired(items("minecraft:stone", 1));
            Container adjacentNonEmpty = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world, POS, direct, expected);
            assertThat(adjacentNonEmpty).isNotNull();
            assertThat(adjacentNonEmpty.getItem(0).is(Items.STONE)).isTrue();

            fixture.respond(items("minecraft:stone", 1));
            fixture.respondPaired(new CompoundTag());
            Container oneNonEmpty = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world, POS, direct, expected);
            assertThat(oneNonEmpty).isNotNull();
            assertThat(oneNonEmpty.getContainerSize()).isEqualTo(54);
            assertThat(oneNonEmpty.getItem(27).is(Items.STONE)).isTrue();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void ungeneratedLootDoesNotReuseAnEmptySnapshotOrAnEmptyCachedEntity() {
        try (var fixture = fixture()) {
            fixture.respond(new CompoundTag());
            assertThat(QuickLitematicaContainerInventory.getActualInventory(fixture.world, POS,
                    new SimpleContainer(27), new SimpleContainer(27))).isNotNull();
            CompoundTag loot = new CompoundTag();
            loot.putString("LootTable", "minecraft:chests/simple_dungeon");
            fixture.respond(loot);
            when(EntityDataManager.getInstance().getFromBlockEntityCache(POS)).thenReturn(
                    new net.minecraft.world.level.block.entity.ChestBlockEntity(POS, Blocks.CHEST.defaultBlockState()));
            assertThat(QuickLitematicaContainerInventory.getActualInventory(fixture.world, POS,
                    new SimpleContainer(27), new SimpleContainer(27))).isNull();
        }
    }

    @Test
    void nativeCacheMissingKeyAndClientShellAreNotServerResponses() {
        try (var fixture = fixture()) {
            var storage = EntityDataManager.getInstance();
            var cache = fixture.cache;
            assertThat(cache.getBlockEntityNbtFromCache(POS)).isNotNull();
            assertThat(cache.getBlockEntityNbtFromCache(POS).isEmpty()).isTrue();
            assertThat(QuickLitematicaContainerInventory.getCachedBlockEntityNbt(storage, POS)).isNull();
            CompoundTag empty = new CompoundTag();
            empty.putString("id", "minecraft:chest");
            cache.addToCache(POS, null, fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.fromVanillaCompound(empty));
            assertThat(QuickLitematicaContainerInventory.getCachedBlockEntityNbt(storage, POS)).isNull();
            fixture.respond(empty);
            assertThat(QuickLitematicaContainerInventory.getCachedBlockEntityNbt(storage, POS)).isEqualTo(empty);
        }
    }

    @Test
    void cachedHalvesMustBelongToARealDoubleChestPair() {
        var state = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.TYPE, ChestType.LEFT)
                .setValue(ChestBlock.FACING, Direction.NORTH);
        BlockPos paired = ChestBlock.getConnectedBlockPos(POS, state);
        try (var fixture = fixture()) {
            when(fixture.world.getBlockState(POS)).thenReturn(state);
            fixture.respond(items("minecraft:stone", 1));
            fixture.respondPaired(items("minecraft:diamond", 1));
            for (var unrelated : List.of(Blocks.BARREL.defaultBlockState(),
                    Blocks.CHEST.defaultBlockState(), state,
                    state.setValue(ChestBlock.TYPE, ChestType.RIGHT).setValue(ChestBlock.FACING, Direction.EAST))) {
                when(fixture.world.getBlockState(paired)).thenReturn(unrelated);
                assertThat(QuickLitematicaContainerInventory.getActualInventory(fixture.world, POS,
                        new SimpleContainer(54), new SimpleContainer(54))).isNull();
            }
        }
    }

    @Test
    void integratedCrafterReadsServerEntityInsteadOfClientShell() {
        var state = Blocks.CRAFTER.defaultBlockState();
        CrafterBlockEntity clientShell = new CrafterBlockEntity(POS, state);
        CrafterBlockEntity authoritative = new CrafterBlockEntity(POS, state);
        authoritative.setSlotState(0, false);
        var serverWorld = mock(net.minecraft.server.level.ServerLevel.class);
        when(serverWorld.getBlockEntity(POS)).thenReturn(authoritative);
        try (var data = mockStatic(DataManager.class)) {
            DataManager manager = mock(DataManager.class);
            data.when(DataManager::getInstance).thenReturn(manager);
            when(manager.hasIntegratedServer()).thenReturn(true);
            assertThat(QuickLitematicaContainerInventory.getActualDisabledSlots(serverWorld, POS, clientShell))
                    .containsExactly(0);
            when(serverWorld.getBlockEntity(POS)).thenReturn(null);
            assertThat(QuickLitematicaContainerInventory.getActualDisabledSlots(serverWorld, POS, clientShell)).isNull();
            assertThat(QuickLitematicaContainerInventory.getActualDisabledSlots(mock(ClientLevel.class), POS, clientShell)).isNull();
        }
    }

    @Test
    void unsyncedCrafterDisabledSlotsStayPendingWhileReliableAndScreenValuesCompare() throws Exception {
        try (var fixture = fixture();
             var verifier = mockStatic(QuickLitematicaContainerVerifier.class, CALLS_REAL_METHODS)) {
            var world = fixture.world;
            CrafterBlockEntity crafter = new CrafterBlockEntity(POS, Blocks.CRAFTER.defaultBlockState());
            when(world.getBlockState(POS)).thenReturn(Blocks.CRAFTER.defaultBlockState());
            when(world.getBlockEntity(POS)).thenReturn(crafter);
            var host = mock(QuickLitematicaContainerVerification.Host.class);
            when(host.quickcraft$verificationWorldClient()).thenReturn(world);
            var verification = new QuickLitematicaContainerVerification(host);
            var expected = new QuickLitematicaContainerVerifier.ExpectedContainer(
                    POS, Blocks.CRAFTER.defaultBlockState(), null, new SimpleContainer(9), Set.of(0));
            verifier.when(() -> QuickLitematicaContainerVerifier.getExpectedContainerAt(world, POS, null)).thenReturn(expected);
            Method collect = QuickLitematicaContainerVerification.class.getDeclaredMethod(
                    "quickcraft$collectContainerMismatchesFromWorld", Level.class, BlockPos.class);
            collect.setAccessible(true);
            CompoundTag unsynced = new CompoundTag();
            unsynced.putString("id", "minecraft:crafter");
            // 客户端序列化也写 disabled_slots 空数组，字段存在不能单独证明收包。
            unsynced.put("disabled_slots", new IntArrayTag(new int[0]));
            fixture.cache.addToCache(POS, crafter,
                    fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.fromVanillaCompound(unsynced));
            assertThat(collect.invoke(verification, world, POS)).isNull();
            CompoundTag reliable = unsynced.copy();
            reliable.put("disabled_slots", new IntArrayTag(new int[]{0}));
            fixture.respond(reliable);
            assertThat((List<?>) collect.invoke(verification, world, POS)).isEmpty();
            CompoundTag different = unsynced.copy();
            different.put("disabled_slots", new IntArrayTag(new int[]{1}));
            fixture.respond(different);
            assertThat(((List<?>) collect.invoke(verification, world, POS)).toString()).contains("LOCK_STATE");
            Method fromInventory = QuickLitematicaContainerVerification.class.getDeclaredMethod(
                    "quickcraft$collectContainerMismatchesFromInventory", Level.class, BlockPos.class, Container.class, Set.class);
            fromInventory.setAccessible(true);
            fixture.respond(new CompoundTag());
            assertThat((List<?>) fromInventory.invoke(verification, world, POS, new SimpleContainer(9), Set.of(0))).isEmpty();
        }
    }

    @Test
    void receiptHookRequiresSuccessAndScopesProofToWorldPositionAndPayload() throws Exception {
        var world = mock(ClientLevel.class);
        when(world.registryAccess()).thenReturn(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        try (var minecraft = mockStatic(net.minecraft.client.Minecraft.class)) {
            var client = clientWithDirectory();
            client.level = world;
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
            CompoundTag nbt = new CompoundTag();
            nbt.putString("id", "minecraft:crafter");
            Object payload = receiptPayload(nbt);
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, payload)).isFalse();
            invokeReceiptHook(payload, null);
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, payload)).isFalse();
            invokeReceiptHook(payload, new CrafterBlockEntity(POS, Blocks.CRAFTER.defaultBlockState()));
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, payload)).isTrue();
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS.above(), payload)).isFalse();
            CompoundTag clientOnly = nbt.copy();
            clientOnly.putInt("clientOnly", 1);
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, receiptPayload(clientOnly))).isFalse();
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(mock(ClientLevel.class), POS, payload)).isFalse();
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, payload)).isFalse();
        } finally {
            QuickLitematicaContainerInventory.wasReceivedBlockEntityData(null, POS, null);
        }
    }

    private static net.minecraft.client.Minecraft clientWithDirectory() {
        var client = mock(net.minecraft.client.Minecraft.class);
        try {
            var directory = net.minecraft.client.Minecraft.class.getDeclaredField("gameDirectory");
            directory.setAccessible(true);
            directory.set(client, fixtureDirectory.toFile());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return client;
    }

    private static Class<?> receiptStorageType() throws ClassNotFoundException {
        return Class.forName("fi.dy.masa.litematica.data.EntityDataManager");
    }

    private static Method receiptMethod() throws ClassNotFoundException {
        return java.util.Arrays.stream(receiptStorageType().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("handleBlockEntityData")).findFirst().orElseThrow();
    }

    private static Object receiptPayload(CompoundTag nbt) throws Exception {
        if (receiptMethod().getParameterTypes()[1] == CompoundTag.class) return nbt;
        return Class.forName("fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt")
                .getMethod("fromVanillaCompound", CompoundTag.class).invoke(null, nbt);
    }

    private static void invokeReceiptHook(Object payload, net.minecraft.world.level.block.entity.BlockEntity result) throws Exception {
        var constructor = receiptStorageType().getDeclaredConstructor();
        constructor.setAccessible(true);
        Object storage = org.mockito.Mockito.spy(constructor.newInstance());
        var world = net.minecraft.client.Minecraft.getInstance().level;
        // 只替换世界边界；实际数据处理及其中织入的收包标记照常执行。
        receiptStorageType().getMethod("getClientWorld").invoke(org.mockito.Mockito.doReturn(world).when(storage));
        when(world.getBlockEntity(POS)).thenReturn(result);
        Method method = receiptMethod();
        Object[] arguments = new Object[method.getParameterCount()];
        arguments[0] = POS;
        arguments[1] = payload;
        method.invoke(storage, arguments);
    }

    private static Fixture fixture() {
        ClientLevel world = mock(ClientLevel.class);
        when(world.registryAccess()).thenReturn(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        var minecraft = mockStatic(net.minecraft.client.Minecraft.class);
        var client = clientWithDirectory();
        client.level = world;
        minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
        var data = mockStatic(DataManager.class);
        DataManager manager = mock(DataManager.class);
        data.when(DataManager::getInstance).thenReturn(manager);
        data.when(DataManager::getDefaultBaseSchematicDirectory).thenReturn(Path.of("."));
        when(manager.hasIntegratedServer()).thenReturn(false);
        var entityData = mockStatic(EntityDataManager.class);
        var dataManager = mock(EntityDataManager.class);
        entityData.when(EntityDataManager::getInstance).thenReturn(dataManager);
        var cache = new fi.dy.masa.malilib.util.data_syncer.EntityDataCache("quickcraft-test", 60_000L);
        when(dataManager.getCache()).thenReturn(cache);
        when(dataManager.requestBlockEntityWrapped(any(), any())).thenReturn(null);
        return new Fixture(world, cache, data, entityData, minecraft);
    }

    private static CompoundTag items(String id, int count) {
        CompoundTag nbt = new CompoundTag();
        ListTag items = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putString("id", id);
        item.putInt("count", count);
        item.putByte("Slot", (byte) 0);
        items.add(item);
        nbt.put("Items", items);
        return nbt;
    }

    private record Fixture(ClientLevel world, fi.dy.masa.malilib.util.data_syncer.EntityDataCache cache,
                           org.mockito.MockedStatic<DataManager> data,
                           org.mockito.MockedStatic<EntityDataManager> entityData,
                           org.mockito.MockedStatic<net.minecraft.client.Minecraft> minecraft) implements AutoCloseable {
        void respond(CompoundTag nbt) { respondAt(POS, nbt); }
        void respondPaired(CompoundTag nbt) {
            respondAt(ChestBlock.getConnectedBlockPos(POS, world.getBlockState(POS)), nbt);
        }
        private void respondAt(BlockPos pos, CompoundTag nbt) {
            if (nbt == null) { cache.removeFromCache(pos); return; }
            var payload = fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.fromVanillaCompound(nbt);
            cache.addToCache(pos, null, payload);
            QuickLitematicaContainerVerifier.rememberReceivedBlockEntityData(world, pos, payload);
        }
        @Override
        public void close() {
            QuickLitematicaContainerInventory.wasReceivedBlockEntityData(null, POS, null);
            entityData.close();
            data.close();
            minecraft.close();
        }
    }
}
