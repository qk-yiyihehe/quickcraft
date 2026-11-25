package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.data.DataManager;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** BUG-01/71：缓存缺键、可靠空库存、双箱边界和合成器锁槽来源必须分开判断。 */
@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaContainerDataSourceTest {
    private static final BlockPos POS = new BlockPos(2, 64, 3);
    @org.junit.jupiter.api.io.TempDir
    static Path fixtureDirectory;

    @org.junit.jupiter.api.BeforeAll
    static void initializeDependencyWithAClientDirectory() throws Exception {
        try (var minecraft = mockStatic(net.minecraft.client.MinecraftClient.class)) {
            var client = clientWithDirectory();
            minecraft.when(net.minecraft.client.MinecraftClient::getInstance).thenReturn(client);
            // 先于其他数据源 mock 初始化真实配置，默认目录只落在本类临时目录中。
            Class.forName("fi.dy.masa.litematica.config.Configs$Generic");
            receiptStorageType();
        }
    }

    @Test
    void missingCacheEntryIsUnknownButReliableEmptyIsAccepted() {
        try (var fixture = fixture()) {
            fixture.respond(null);
            assertThat(
                    QuickLitematicaContainerInventory.getActualInventory(
                            fixture.world,
                            POS,
                            new SimpleInventory(27),
                            new SimpleInventory(27)
                    )
            ).isNull();

            NbtCompound empty = new NbtCompound();
            empty.putString("id", "minecraft:chest");
            empty.putInt("x", POS.getX());
            fixture.respond(empty);
            Inventory actual = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world,
                    POS,
                    new SimpleInventory(27),
                    new SimpleInventory(27)
            );
            assertThat(actual).isNotNull();
            assertThat(actual.isEmpty()).isTrue();
            assertThat(QuickLitematicaContainerInventory.getLastActualInventoryReadStatus()).isEqualTo(
                    QuickLitematicaContainerVerifier.ActualInventoryReadStatus.CACHE_INVENTORY
            );
        }
    }

    @Test
    void laterNonEmptyCacheDataReplacesTheTrustedEmptySnapshot() {
        try (var fixture = fixture()) {
            NbtCompound empty = new NbtCompound();
            empty.putString("id", "minecraft:chest");
            fixture.respond(empty);
            Inventory emptyActual = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world,
                    POS,
                    new SimpleInventory(27),
                    new SimpleInventory(27)
            );
            assertThat(emptyActual).isNotNull();
            assertThat(emptyActual.isEmpty()).isTrue();

            fixture.respond(items("minecraft:stone", 1));
            Inventory actual = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world,
                    POS,
                    new SimpleInventory(27),
                    new SimpleInventory(27)
            );
            assertThat(actual).isNotNull();
            assertThat(actual.getStack(0).isOf(Items.STONE)).isTrue();
        }
    }

    @Test
    void doubleChestEmptyShortcutRequiresBothReliableHalvesAndKeepsSlotOrder() {
        var state = Blocks.CHEST.getDefaultState().with(ChestBlock.CHEST_TYPE, ChestType.LEFT).with(
                ChestBlock.FACING,
                Direction.NORTH
        );
        BlockPos paired = POS.offset(ChestBlock.getFacing(state));
        SimpleInventory expected = new SimpleInventory(54);
        SimpleInventory direct = new SimpleInventory(54);

        try (var fixture = fixture()) {
            when(fixture.world.getBlockState(POS)).thenReturn(state);
            when(fixture.world.getBlockState(paired)).thenReturn(state.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT));

            fixture.respond(new NbtCompound());
            fixture.respondPaired(null);
            assertThat(QuickLitematicaContainerInventory.getActualInventory(fixture.world, POS, direct, expected))
                    .isNull();
            fixture.access.verify(() -> QuickLitematicaVerifierAccess.requestBlockEntity(fixture.world, paired));

            fixture.respondPaired(new NbtCompound());
            Inventory bothEmpty = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world,
                    POS,
                    direct,
                    expected
            );
            assertThat(bothEmpty).isNotNull();
            assertThat(bothEmpty.size()).isEqualTo(54);
            assertThat(bothEmpty.isEmpty()).isTrue();

            fixture.respond(new NbtCompound());
            fixture.respondPaired(items("minecraft:stone", 1));
            Inventory adjacentNonEmpty = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world,
                    POS,
                    direct,
                    expected
            );
            assertThat(adjacentNonEmpty).isNotNull();
            assertThat(adjacentNonEmpty.getStack(0).isOf(Items.STONE)).isTrue();

            fixture.respond(items("minecraft:stone", 1));
            fixture.respondPaired(new NbtCompound());
            Inventory oneNonEmpty = QuickLitematicaContainerInventory.getActualInventory(
                    fixture.world,
                    POS,
                    direct,
                    expected
            );
            assertThat(oneNonEmpty).isNotNull();
            assertThat(oneNonEmpty.size()).isEqualTo(54);
            // LEFT 当前半箱按生产顺序位于合并库存后半。
            assertThat(oneNonEmpty.getStack(27).isOf(Items.STONE)).isTrue();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void ungeneratedLootDoesNotReuseAnEmptySnapshotOrAnEmptyCachedEntity() {
        try (var fixture = fixture()) {
            fixture.respond(new NbtCompound());
            assertThat(
                    QuickLitematicaContainerInventory.getActualInventory(
                            fixture.world,
                            POS,
                            new SimpleInventory(27),
                            new SimpleInventory(27)
                    )
            ).isNotNull();
            NbtCompound loot = new NbtCompound();
            loot.putString("LootTable", "minecraft:chests/simple_dungeon");
            fixture.respond(loot);
            fixture.access.when(() -> QuickLitematicaVerifierAccess.getBlockEntityFromStorage(POS)).thenReturn(
                    new net.minecraft.block.entity.ChestBlockEntity(POS, Blocks.CHEST.getDefaultState())
            );
            assertThat(
                    QuickLitematicaContainerInventory.getActualInventory(
                            fixture.world,
                            POS,
                            new SimpleInventory(27),
                            new SimpleInventory(27)
                    )
            ).isNull();
        }
    }

    @Test
    void cachedHalvesMustBelongToARealDoubleChestPair() {
        var state = Blocks.CHEST.getDefaultState().with(ChestBlock.CHEST_TYPE, ChestType.LEFT).with(
                ChestBlock.FACING,
                Direction.NORTH
        );
        BlockPos paired = POS.offset(ChestBlock.getFacing(state));
        try (var fixture = fixture()) {
            when(fixture.world.getBlockState(POS)).thenReturn(state);
            fixture.respond(items("minecraft:stone", 1));
            fixture.respondPaired(items("minecraft:diamond", 1));
            for (var unrelated : List.of(
                    Blocks.BARREL.getDefaultState(),
                    Blocks.CHEST.getDefaultState(),
                    state,
                    state.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT).with(ChestBlock.FACING, Direction.EAST)
            )) {
                when(fixture.world.getBlockState(paired)).thenReturn(unrelated);
                assertThat(
                        QuickLitematicaContainerInventory.getActualInventory(
                                fixture.world,
                                POS,
                                new SimpleInventory(54),
                                new SimpleInventory(54)
                        )
                ).isNull();
            }
        }
    }

    @Test
    void integratedCrafterReadsServerEntityInsteadOfClientShell() {
        var state = Blocks.CRAFTER.getDefaultState();
        CrafterBlockEntity clientShell = new CrafterBlockEntity(POS, state);
        CrafterBlockEntity authoritative = new CrafterBlockEntity(POS, state);
        authoritative.setSlotEnabled(0, false);
        var serverWorld = mock(net.minecraft.server.world.ServerWorld.class);
        when(serverWorld.getBlockEntity(POS)).thenReturn(authoritative);
        try (var data = mockStatic(DataManager.class)) {
            DataManager manager = mock(DataManager.class);
            data.when(DataManager::getInstance).thenReturn(manager);
            when(manager.hasIntegratedServer()).thenReturn(true);
            assertThat(QuickLitematicaContainerInventory.getActualDisabledSlots(serverWorld, POS, clientShell))
                    .containsExactly(0);
            when(serverWorld.getBlockEntity(POS)).thenReturn(null);
            assertThat(QuickLitematicaContainerInventory.getActualDisabledSlots(serverWorld, POS, clientShell))
                    .isNull();
            assertThat(
                    QuickLitematicaContainerInventory.getActualDisabledSlots(mock(ClientWorld.class), POS, clientShell)
            ).isNull();
        }
    }

    @Test
    void unsyncedCrafterDisabledSlotsStayPendingWhileReliableAndScreenValuesCompare() throws Exception {
        BlockPos pos = POS;
        ClientWorld world = mock(ClientWorld.class);
        CrafterBlockEntity crafter = mock(CrafterBlockEntity.class, withSettings().extraInterfaces(Inventory.class));
        when(world.getBlockState(pos)).thenReturn(Blocks.CRAFTER.getDefaultState());
        when(world.getBlockEntity(pos)).thenReturn(crafter);
        when(((Inventory) crafter).size()).thenReturn(9);
        when(((Inventory) crafter).getStack(anyInt())).thenReturn(ItemStack.EMPTY);

        var host = mock(QuickLitematicaContainerVerification.Host.class);
        when(host.quickcraft$verificationWorldClient()).thenReturn(world);
        var verification = new QuickLitematicaContainerVerification(host);
        var expected = new QuickLitematicaContainerVerifier.ExpectedContainer(
                pos,
                Blocks.CRAFTER.getDefaultState(),
                null,
                new SimpleInventory(9),
                Set.of(0)
        );
        Method collect = QuickLitematicaContainerVerification.class.getDeclaredMethod(
                "quickcraft$collectContainerMismatchesFromWorld",
                World.class,
                BlockPos.class
        );
        collect.setAccessible(true);

        AtomicReference<NbtCompound> cache = new AtomicReference<>();
        try (var data = mockStatic(DataManager.class);
                var verifier = mockStatic(QuickLitematicaContainerVerifier.class, CALLS_REAL_METHODS);
                var access = mockStatic(QuickLitematicaVerifierAccess.class, CALLS_REAL_METHODS);
                var minecraft = mockStatic(net.minecraft.client.MinecraftClient.class)) {
            var client = clientWithDirectory();
            client.world = world;
            minecraft.when(net.minecraft.client.MinecraftClient::getInstance).thenReturn(client);
            DataManager manager = mock(DataManager.class);
            data.when(DataManager::getInstance).thenReturn(manager);
            when(manager.hasIntegratedServer()).thenReturn(false);
            verifier.when(() -> QuickLitematicaContainerVerifier.getExpectedContainerAt(world, pos, null)).thenReturn(
                    expected
            );
            access.when(() -> QuickLitematicaVerifierAccess.getBlockEntityNbtFromStorage(pos)).thenAnswer(
                    invocation -> cache.get()
            );
            access.when(QuickLitematicaVerifierAccess::hasServuxServer).thenReturn(false);
            access.when(QuickLitematicaVerifierAccess::hasBackupStatus).thenReturn(false);
            access.when(QuickLitematicaVerifierAccess::getIfReceivedBackupPackets).thenReturn(false);
            access.when(() -> QuickLitematicaVerifierAccess.hasCompletedChunk(any())).thenReturn(false);
            access.when(() -> QuickLitematicaVerifierAccess.getBlockEntityFromStorage(any())).thenReturn(null);
            access.when(() -> QuickLitematicaVerifierAccess.requestBlockEntity(any(), any())).thenAnswer(
                    invocation -> null
            );

            NbtCompound unsynced = new NbtCompound();
            unsynced.putString("id", "minecraft:crafter");
            unsynced.put("disabled_slots", new NbtIntArray(new int[0]));
            cache.set(unsynced);
            assertThat(collect.invoke(verification, world, pos)).isNull();
            access.verify(
                    () -> QuickLitematicaVerifierAccess.requestBlockEntity(any(), any()),
                    org.mockito.Mockito.atLeastOnce()
            );

            NbtCompound reliable = unsynced.copy();
            reliable.put("disabled_slots", new NbtIntArray(new int[] { 0 }));
            cache.set(reliable);
            supplyReceivedData(world, pos, reliable);
            assertThat((List<?>) collect.invoke(verification, world, pos)).isEmpty();

            NbtCompound different = unsynced.copy();
            different.put("disabled_slots", new NbtIntArray(new int[] { 1 }));
            cache.set(different);
            supplyReceivedData(world, pos, different);
            List<?> mismatches = (List<?>) collect.invoke(verification, world, pos);
            assertThat(mismatches).isNotEmpty();
            assertThat(mismatches.toString()).contains("LOCK_STATE");

            Method fromInventory = QuickLitematicaContainerVerification.class.getDeclaredMethod(
                    "quickcraft$collectContainerMismatchesFromInventory",
                    World.class,
                    BlockPos.class,
                    Inventory.class,
                    Set.class
            );
            fromInventory.setAccessible(true);
            cache.set(unsynced);
            assertThat((List<?>) fromInventory.invoke(verification, world, pos, new SimpleInventory(9), Set.of(0)))
                    .isEmpty();
        }
    }

    @Test
    void receiptHookRequiresSuccessAndScopesProofToWorldPositionAndPayload() throws Exception {
        var world = mock(ClientWorld.class);
        when(world.getRegistryManager()).thenReturn(DynamicRegistryManager.of(Registries.REGISTRIES));
        try (var minecraft = mockStatic(net.minecraft.client.MinecraftClient.class)) {
            var client = clientWithDirectory();
            client.world = world;
            minecraft.when(net.minecraft.client.MinecraftClient::getInstance).thenReturn(client);
            NbtCompound nbt = new NbtCompound();
            nbt.putString("id", "minecraft:crafter");
            Object payload = receiptPayload(nbt);
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, payload)).isFalse();
            invokeReceiptHook(payload, null);
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, payload)).isFalse();
            invokeReceiptHook(payload, new CrafterBlockEntity(POS, Blocks.CRAFTER.getDefaultState()));
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, payload)).isTrue();
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS.up(), payload))
                    .isFalse();
            NbtCompound clientOnly = nbt.copy();
            clientOnly.putInt("clientOnly", 1);
            assertThat(
                    QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, receiptPayload(clientOnly))
            ).isFalse();
            assertThat(
                    QuickLitematicaContainerInventory.wasReceivedBlockEntityData(mock(ClientWorld.class), POS, payload)
            ).isFalse();
            assertThat(QuickLitematicaContainerInventory.wasReceivedBlockEntityData(world, POS, payload)).isFalse();
        } finally {
            QuickLitematicaContainerInventory.wasReceivedBlockEntityData(null, POS, null);
        }
    }

    private static net.minecraft.client.MinecraftClient clientWithDirectory() {
        var client = mock(net.minecraft.client.MinecraftClient.class);
        try {
            var directory = net.minecraft.client.MinecraftClient.class.getDeclaredField("runDirectory");
            directory.setAccessible(true);
            directory.set(client, fixtureDirectory.toFile());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        return client;
    }

    private static Class<?> receiptStorageType() throws ClassNotFoundException {
        try {
            return Class.forName("fi.dy.masa.litematica.data.EntitiesDataStorage");
        } catch (ClassNotFoundException newerApi) {
            return Class.forName("fi.dy.masa.litematica.data.EntityDataManager");
        }
    }

    private static Method receiptMethod() throws ClassNotFoundException {
        return java.util.Arrays.stream(receiptStorageType().getDeclaredMethods()).filter(
                candidate -> candidate.getName().equals("handleBlockEntityData")
        ).findFirst().orElseThrow();
    }

    private static Object receiptPayload(NbtCompound nbt) throws Exception {
        if (receiptMethod().getParameterTypes()[1] == NbtCompound.class) {
            return nbt;
        }
        return Class.forName("fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt").getMethod(
                "fromVanillaCompound",
                NbtCompound.class
        ).invoke(null, nbt);
    }

    private static void invokeReceiptHook(Object payload, net.minecraft.block.entity.BlockEntity result)
            throws Exception {
        var constructor = receiptStorageType().getDeclaredConstructor();
        constructor.setAccessible(true);
        Object storage = org.mockito.Mockito.spy(constructor.newInstance());
        var world = net.minecraft.client.MinecraftClient.getInstance().world;
        // 只替换世界边界；实际数据处理及其中织入的收包标记照常执行。
        receiptStorageType().getMethod("getClientWorld").invoke(org.mockito.Mockito.doReturn(world).when(storage));
        when(world.getBlockEntity(POS)).thenReturn(result);
        Method method = receiptMethod();
        Object[] arguments = new Object[method.getParameterCount()];
        arguments[0] = POS;
        arguments[1] = payload;
        method.invoke(storage, arguments);
    }

    private static void supplyReceivedData(World world, BlockPos pos, NbtCompound nbt) {
        try {
            Object payload = nbt == null ? null : receiptPayload(nbt);
            if (receiptMethod().getParameterTypes()[1] != NbtCompound.class) {
                Object storage = receiptStorageType().getMethod("getInstance").invoke(null);
                Object nativeCache = receiptStorageType().getMethod("getCache").invoke(storage);
                if (payload == null) {
                    nativeCache.getClass().getMethod("removeFromCache", BlockPos.class).invoke(nativeCache, pos);
                } else {
                    Method add = java.util.Arrays.stream(nativeCache.getClass().getMethods()).filter(
                            method -> method.getName().equals("addToCache") && method.getParameterCount() == 3 && method
                                    .getParameterTypes()[0] == BlockPos.class
                    ).findFirst().orElseThrow();
                    add.invoke(nativeCache, pos, null, payload);
                }
            }
            if (payload != null) {
                QuickLitematicaContainerVerifier.rememberReceivedBlockEntityData(world, pos, payload);
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Fixture fixture() {
        ClientWorld world = mock(ClientWorld.class);
        when(world.getRegistryManager()).thenReturn(DynamicRegistryManager.of(Registries.REGISTRIES));
        var minecraft = mockStatic(net.minecraft.client.MinecraftClient.class);
        var client = clientWithDirectory();
        client.world = world;
        minecraft.when(net.minecraft.client.MinecraftClient::getInstance).thenReturn(client);
        var data = mockStatic(DataManager.class);
        DataManager manager = mock(DataManager.class);
        data.when(DataManager::getInstance).thenReturn(manager);
        when(manager.hasIntegratedServer()).thenReturn(false);
        var access = mockStatic(QuickLitematicaVerifierAccess.class, CALLS_REAL_METHODS);
        var cache = new AtomicReference<NbtCompound>();
        var cachePaired = new AtomicReference<NbtCompound>();
        access.when(() -> QuickLitematicaVerifierAccess.getBlockEntityNbtFromStorage(any())).thenAnswer(
                invocation -> POS.equals(invocation.getArgument(0)) ? cache.get() : cachePaired.get()
        );
        access.when(() -> QuickLitematicaVerifierAccess.getBlockEntityFromStorage(any())).thenReturn(null);
        access.when(QuickLitematicaVerifierAccess::hasServuxServer).thenReturn(false);
        access.when(QuickLitematicaVerifierAccess::hasBackupStatus).thenReturn(false);
        access.when(QuickLitematicaVerifierAccess::getIfReceivedBackupPackets).thenReturn(false);
        access.when(() -> QuickLitematicaVerifierAccess.hasCompletedChunk(any())).thenReturn(false);
        access.when(() -> QuickLitematicaVerifierAccess.requestBlockEntity(any(), any())).thenAnswer(
                invocation -> null
        );
        return new Fixture(world, cache, cachePaired, data, access, minecraft);
    }

    private static NbtCompound items(String id, int count) {
        NbtCompound nbt = new NbtCompound();
        NbtList items = new NbtList();
        NbtCompound item = new NbtCompound();
        item.putString("id", id);
        item.putInt("count", count);
        item.putByte("Slot", (byte) 0);
        items.add(item);
        nbt.put("Items", items);
        return nbt;
    }

    private record Fixture(
            ClientWorld world,
            AtomicReference<NbtCompound> cache,
            AtomicReference<NbtCompound> cachePaired,
            org.mockito.MockedStatic<DataManager> data,
            org.mockito.MockedStatic<QuickLitematicaVerifierAccess> access,
            org.mockito.MockedStatic<net.minecraft.client.MinecraftClient> minecraft
    ) implements AutoCloseable {
        void respond(NbtCompound nbt) {
            cache.set(nbt);
            supplyReceivedData(world, POS, nbt);
        }

        void respondPaired(NbtCompound nbt) {
            cachePaired.set(nbt);
            supplyReceivedData(world, POS.offset(ChestBlock.getFacing(world.getBlockState(POS))), nbt);
        }

        @Override
        public void close() {
            supplyReceivedData(world, POS, null);
            QuickLitematicaContainerInventory.wasReceivedBlockEntityData(null, POS, null);
            access.close();
            data.close();
            minecraft.close();
        }
    }
}
