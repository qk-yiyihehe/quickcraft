package com.yiyihehe.quickcraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickContainerLockIdentityTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final BlockPos SECOND = new BlockPos(1, 64, 0);

    @AfterEach
    void clearState() {
        QuickContainerLock.clearPersistentState();
    }

    @Test
    void bothHalvesShareOneKeyForEveryHorizontalFacing() {
        assertSameKey(Direction.NORTH);
        assertSameKey(Direction.EAST);
        assertSameKey(Direction.SOUTH);
        assertSameKey(Direction.WEST);
    }

    @Test
    void independentWrongDirectionAndWrongTypeNeighborsKeepDifferentKeys() {
        BlockState left = chest(ChestType.LEFT, Direction.NORTH);
        ClientWorld independent = world(
                pos -> pos.equals(FIRST) ? Blocks.CHEST.getDefaultState().with(ChestBlock.CHEST_TYPE, ChestType.SINGLE)
                        : pos.equals(SECOND) ? Blocks.CHEST.getDefaultState().with(
                                ChestBlock.CHEST_TYPE,
                                ChestType.SINGLE
                        ) : Blocks.AIR.getDefaultState()
        );
        assertThat(QuickContainerLock.buildContainerKey(independent, FIRST)).isNotEqualTo(
                QuickContainerLock.buildContainerKey(independent, SECOND)
        );

        ClientWorld wrongDirection = world(
                pos -> pos.equals(FIRST) ? left
                        : pos.equals(SECOND) ? chest(ChestType.RIGHT, Direction.SOUTH) : Blocks.AIR.getDefaultState()
        );
        assertThat(QuickContainerLock.buildContainerKey(wrongDirection, FIRST)).isNotEqualTo(
                QuickContainerLock.buildContainerKey(wrongDirection, SECOND)
        );

        ClientWorld wrongType = world(
                pos -> pos.equals(FIRST) ? left
                        : pos.equals(SECOND) ? chest(ChestType.LEFT, Direction.NORTH) : Blocks.AIR.getDefaultState()
        );
        assertThat(QuickContainerLock.buildContainerKey(wrongType, FIRST)).isNotEqualTo(
                QuickContainerLock.buildContainerKey(wrongType, SECOND)
        );
    }

    @Test
    void splitAndRejoinKeepsOnlyTheActuallyCreatedIdentity() {
        ClientWorld paired = pairWorld(Direction.NORTH);
        String pairedKey = QuickContainerLock.buildContainerKey(paired, FIRST);

        ClientWorld split = world(
                pos -> pos.equals(SECOND) ? Blocks.CHEST.getDefaultState() : Blocks.AIR.getDefaultState()
        );
        assertThat(QuickContainerLock.buildContainerKey(split, SECOND)).isNotEqualTo(pairedKey);
        assertThat(QuickContainerLock.buildContainerKey(paired, SECOND)).isEqualTo(pairedKey);
    }

    @Test
    void legacyHalfKeysRemainByteForByteWhenLoadedAndSaved() {
        JsonObject root = JsonParser.parseString("""
                {"containerLock":{
                  "lockedContainers":["minecraft:overworld#legacy-left","minecraft:overworld#legacy-right"],
                  "lockedPlayerSlots":[],
                  "lockedContainerSlots":{
                    "minecraft:overworld#legacy-left":[0],
                    "minecraft:overworld#legacy-right":[27]
                  }}}""").getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);

        JsonObject saved = new JsonObject();
        QuickContainerLock.writePersistentState(saved);
        JsonObject lock = saved.getAsJsonObject("containerLock");
        assertThat(lock.getAsJsonArray("lockedContainers")).extracting(JsonElement::getAsString)
                .containsExactlyInAnyOrder("minecraft:overworld#legacy-left", "minecraft:overworld#legacy-right");
        assertThat(lock.getAsJsonObject("lockedContainerSlots").getAsJsonArray("minecraft:overworld#legacy-left"))
                .extracting(JsonElement::getAsInt).containsExactly(0);
        assertThat(lock.getAsJsonObject("lockedContainerSlots").getAsJsonArray("minecraft:overworld#legacy-right"))
                .extracting(JsonElement::getAsInt).containsExactly(27);
    }

    @Test
    void onlyProvableLegacyDoubleChestSlotsAreCopiedToTheCanonicalKey() throws Exception {
        ClientWorld world = pairWorld(Direction.NORTH);
        String dimension = world.getRegistryKey().getValue().toString();
        String legacy = dimension + "#" + SECOND.asLong();
        JsonObject root = JsonParser.parseString("""
                {"containerLock":{"lockedContainers":["%s"],"lockedPlayerSlots":[],
                 "lockedContainerSlots":{"%s":[0,27]}}}
                """.formatted(legacy, legacy)).getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);

        String canonical = QuickContainerLock.buildContainerKey(world, FIRST);

        @SuppressWarnings("unchecked")
        Set<String> containers = (Set<String>) readStaticField("LOCKED_CONTAINERS");
        @SuppressWarnings("unchecked")
        Map<String, Set<Integer>> slots = (Map<String, Set<Integer>>) readStaticField("LOCKED_CONTAINER_SLOTS");
        assertThat(containers).contains(canonical, legacy);
        assertThat(slots.get(canonical)).containsExactlyInAnyOrder(0, 27);
        assertThat(slots.get(legacy)).containsExactlyInAnyOrder(0, 27);
    }

    @Test
    void unlockedMigratedLocksStayUnlockedAfterReopeningAndReloading() throws Exception {
        ClientWorld world = pairWorld(Direction.NORTH);
        String legacy = world.getRegistryKey().getValue() + "#" + SECOND.asLong();
        JsonObject root = JsonParser.parseString("""
                {"containerLock":{"lockedContainers":["%s"],"lockedPlayerSlots":[],
                 "lockedContainerSlots":{"%s":[27]}}}
                """.formatted(legacy, legacy)).getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);
        String canonical = QuickContainerLock.buildContainerKey(world, FIRST);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        PlayerInventory inventory = QuickCraftTestInventories.newPlayerInventory(player);
        GenericContainerScreenHandler handler = GenericContainerScreenHandler.createGeneric9x6(51, inventory);
        HandledScreen<?> screen = screen(handler);
        MinecraftClient client = mock(MinecraftClient.class);
        client.player = player;
        client.world = world;

        try (var minecraft = mockStatic(MinecraftClient.class);
                var persistent = mockStatic(QuickPersistentState.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            QuickContainerLock lock = new QuickContainerLock();
            open(lock, client, screen, canonical);
            assertThat(QuickContainerLock.isCurrentScreenLocked(screen)).isTrue();
            assertThat(QuickContainerLock.isLockedSlot(handler, handler.getSlot(27))).isTrue();
            QuickContainerLock.toggleCurrentScreenLock(client, screen);
            invokeToggleSlotLock(lock, screen, handler.getSlot(27));

            assertThat(QuickContainerLock.buildContainerKey(world, SECOND)).isEqualTo(canonical);
            assertThat(QuickContainerLock.isCurrentScreenLocked(screen)).isFalse();
            assertThat(QuickContainerLock.isLockedSlot(handler, handler.getSlot(27))).isFalse();

            JsonObject saved = new JsonObject();
            QuickContainerLock.writePersistentState(saved);
            QuickContainerLock.clearPersistentState();
            QuickContainerLock.loadPersistentState(saved);
            assertThat(QuickContainerLock.buildContainerKey(world, FIRST)).isEqualTo(canonical);
            open(lock, client, screen, canonical);
            assertThat(QuickContainerLock.isCurrentScreenLocked(screen)).isFalse();
            assertThat(QuickContainerLock.isLockedSlot(handler, handler.getSlot(27))).isFalse();
            JsonObject reloaded = new JsonObject();
            QuickContainerLock.writePersistentState(reloaded);
            JsonObject state = reloaded.getAsJsonObject("containerLock");
            assertThat(state.getAsJsonArray("lockedContainers")).extracting(JsonElement::getAsString).containsExactly(
                    legacy
            );
            assertThat(state.getAsJsonObject("lockedContainerSlots").getAsJsonArray(legacy)).extracting(
                    JsonElement::getAsInt
            ).containsExactly(27);
        }
    }

    @Test
    void ambiguousLegacyHalfRecordIsNotExpandedToThePair() throws Exception {
        ClientWorld world = pairWorld(Direction.NORTH);
        String dimension = world.getRegistryKey().getValue().toString();
        String legacy = dimension + "#" + SECOND.asLong();
        JsonObject root = JsonParser.parseString("""
                {"containerLock":{"lockedContainers":["%s"],"lockedPlayerSlots":[],
                 "lockedContainerSlots":{"%s":[0]}}}
                """.formatted(legacy, legacy)).getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);

        String canonical = QuickContainerLock.buildContainerKey(world, FIRST);

        @SuppressWarnings("unchecked")
        Map<String, Set<Integer>> slots = (Map<String, Set<Integer>>) readStaticField("LOCKED_CONTAINER_SLOTS");
        assertThat(slots).doesNotContainKey(canonical);
    }

    @Test
    void openingEitherHalfUsesTheSameWholeContainerAndSlotLock() throws Exception {
        ClientWorld world = pairWorld(Direction.NORTH);
        String firstKey = QuickContainerLock.buildContainerKey(world, FIRST);
        String secondKey = QuickContainerLock.buildContainerKey(world, SECOND);
        assertThat(firstKey).isEqualTo(secondKey);

        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        PlayerInventory inventory = QuickCraftTestInventories.newPlayerInventory(player);
        GenericContainerScreenHandler rightHandler = GenericContainerScreenHandler.createGeneric9x6(41, inventory);
        GenericContainerScreenHandler leftHandler = GenericContainerScreenHandler.createGeneric9x6(42, inventory);
        GenericContainerScreenHandler reopenedHandler = GenericContainerScreenHandler.createGeneric9x6(43, inventory);
        HandledScreen<?> rightScreen = screen(rightHandler);
        HandledScreen<?> leftScreen = screen(leftHandler);
        HandledScreen<?> reopenedScreen = screen(reopenedHandler);

        MinecraftClient client = mock(MinecraftClient.class);
        client.player = player;
        client.world = world;

        try (var minecraft = mockStatic(MinecraftClient.class);
                var persistent = mockStatic(QuickPersistentState.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            QuickContainerLock lock = new QuickContainerLock();

            open(lock, client, rightScreen, firstKey);
            QuickContainerLock.toggleCurrentScreenLock(client, rightScreen);
            assertThat(QuickContainerLock.isCurrentScreenLocked(rightScreen)).isTrue();

            open(lock, client, leftScreen, secondKey);
            assertThat(QuickContainerLock.isCurrentScreenLocked(leftScreen)).isTrue();
            invokeToggleSlotLock(lock, leftScreen, leftHandler.getSlot(0));
            assertThat(QuickContainerLock.isLockedSlot(leftHandler, leftHandler.getSlot(0))).isTrue();

            open(lock, client, reopenedScreen, firstKey);
            assertThat(QuickContainerLock.isCurrentScreenLocked(reopenedScreen)).isTrue();
            assertThat(QuickContainerLock.isLockedSlot(reopenedHandler, reopenedHandler.getSlot(0))).isTrue();
        }
    }

    @Test
    void oldHandlerMappingDoesNotFollowAReusedSyncId() throws Exception {
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        PlayerInventory inventory = QuickCraftTestInventories.newPlayerInventory(player);
        GenericContainerScreenHandler first = GenericContainerScreenHandler.createGeneric9x3(7, inventory);
        GenericContainerScreenHandler second = GenericContainerScreenHandler.createGeneric9x3(7, inventory);
        assertThat(first.syncId).isEqualTo(second.syncId);

        JsonObject root = JsonParser.parseString("""
                {"containerLock":{"lockedContainers":[],"lockedPlayerSlots":[],
                 "lockedContainerSlots":{"key-a":[0],"key-b":[1]}}}""").getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);
        invokeRememberHandlerKey(first, "key-a");

        assertThat(QuickContainerLock.isLockedSlot(first, first.getSlot(0))).isTrue();
        assertThat(QuickContainerLock.isLockedSlot(second, second.getSlot(0))).isFalse();

        invokeRememberHandlerKey(second, "key-b");
        assertThat(QuickContainerLock.isLockedSlot(second, second.getSlot(1))).isTrue();
        assertThat(QuickContainerLock.isLockedSlot(first, first.getSlot(0))).isTrue();
    }

    @Test
    void timedOutBLateScreenDoesNotInheritAHandlerLock() throws Exception {
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        PlayerInventory inventory = QuickCraftTestInventories.newPlayerInventory(player);
        GenericContainerScreenHandler first = GenericContainerScreenHandler.createGeneric9x3(7, inventory);
        GenericContainerScreenHandler second = GenericContainerScreenHandler.createGeneric9x3(7, inventory);
        HandledScreen<?> secondScreen = screen(second);
        JsonObject root = JsonParser.parseString("""
                {"containerLock":{"lockedContainers":["key-a"],"lockedPlayerSlots":[],
                 "lockedContainerSlots":{"key-a":[0]}}}""").getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);
        invokeRememberHandlerKey(first, "key-a");

        MinecraftClient client = mock(MinecraftClient.class);
        client.player = player;
        try (var minecraft = mockStatic(MinecraftClient.class);
                var persistent = mockStatic(QuickPersistentState.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            QuickContainerLock lock = new QuickContainerLock();
            client.currentScreen = null;
            setStaticField("pendingContainerKey", "key-b");
            setStaticField("pendingTicks", 21);
            Method process = QuickContainerLock.class.getDeclaredMethod("processPendingOpen", MinecraftClient.class);
            process.setAccessible(true);
            process.invoke(lock, client);

            client.currentScreen = secondScreen;
            QuickContainerLock.bindCurrentScreen(secondScreen);
            assertThat(QuickContainerLock.isCurrentScreenLocked(secondScreen)).isFalse();
            assertThat(QuickContainerLock.isLockedSlot(second, second.getSlot(0))).isFalse();
        }
    }

    @Test
    void staleScreenCallbackCannotReplaceCurrentScreenKey() throws Exception {
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        PlayerInventory inventory = QuickCraftTestInventories.newPlayerInventory(player);
        GenericContainerScreenHandler oldHandler = GenericContainerScreenHandler.createGeneric9x3(11, inventory);
        GenericContainerScreenHandler currentHandler = GenericContainerScreenHandler.createGeneric9x6(12, inventory);
        HandledScreen<?> oldScreen = screen(oldHandler);
        HandledScreen<?> currentScreen = screen(currentHandler);
        JsonObject root = JsonParser.parseString("""
                {"containerLock":{"lockedContainers":["key-b"],"lockedPlayerSlots":[],
                 "lockedContainerSlots":{}}}""").getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);
        invokeRememberHandlerKey(oldHandler, "key-a");

        MinecraftClient client = mock(MinecraftClient.class);
        client.player = player;
        try (var minecraft = mockStatic(MinecraftClient.class);
                var persistent = mockStatic(QuickPersistentState.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            QuickContainerLock lock = new QuickContainerLock();
            client.currentScreen = currentScreen;
            setStaticField("pendingContainerKey", "key-b");
            Method process = QuickContainerLock.class.getDeclaredMethod("processPendingOpen", MinecraftClient.class);
            process.setAccessible(true);
            process.invoke(lock, client);

            QuickContainerLock.bindCurrentScreen(oldScreen);
            assertThat(QuickContainerLock.isCurrentScreenLocked(currentScreen)).isTrue();
        }
    }

    private static void open(QuickContainerLock lock, MinecraftClient client, HandledScreen<?> screen, String key)
            throws Exception {
        client.currentScreen = screen;
        setStaticField("pendingContainerKey", key);
        Method process = QuickContainerLock.class.getDeclaredMethod("processPendingOpen", MinecraftClient.class);
        process.setAccessible(true);
        process.invoke(lock, client);
    }

    private static void invokeToggleSlotLock(Object lock, HandledScreen<?> screen, net.minecraft.screen.slot.Slot slot)
            throws Exception {
        Method toggle = QuickContainerLock.class.getDeclaredMethod(
                "toggleSlotLock",
                HandledScreen.class,
                net.minecraft.screen.slot.Slot.class
        );
        toggle.setAccessible(true);
        toggle.invoke(lock, screen, slot);
    }

    private static void invokeRememberHandlerKey(ScreenHandler handler, String key) throws Exception {
        Method remember = QuickContainerLock.class.getDeclaredMethod(
                "rememberHandlerKey",
                ScreenHandler.class,
                String.class
        );
        remember.setAccessible(true);
        remember.invoke(null, handler, key);
    }

    private static void assertSameKey(Direction facing) {
        BlockPos second = FIRST.offset(ChestBlock.getFacing(chest(ChestType.LEFT, facing)));
        ClientWorld world = pairWorld(facing, FIRST, second);
        assertThat(QuickContainerLock.buildContainerKey(world, FIRST)).isEqualTo(
                QuickContainerLock.buildContainerKey(world, second)
        );
    }

    private static ClientWorld pairWorld(Direction facing) {
        return pairWorld(facing, FIRST, SECOND);
    }

    private static ClientWorld pairWorld(Direction facing, BlockPos first, BlockPos second) {
        return world(
                pos -> pos.equals(first) ? chest(ChestType.LEFT, facing)
                        : pos.equals(second) ? chest(ChestType.RIGHT, facing) : Blocks.AIR.getDefaultState()
        );
    }

    private static ClientWorld world(java.util.function.Function<BlockPos, BlockState> states) {
        ClientWorld world = mock(ClientWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(
                invocation -> states.apply(invocation.getArgument(0))
        );
        return world;
    }

    private static BlockState chest(ChestType type, Direction facing) {
        return Blocks.CHEST.getDefaultState().with(ChestBlock.CHEST_TYPE, type).with(ChestBlock.FACING, facing);
    }

    private static HandledScreen<?> screen(ScreenHandler handler) {
        HandledScreen<?> screen = mock(HandledScreen.class);
        when(screen.getScreenHandler()).thenReturn(handler);
        return screen;
    }

    private static Object readStaticField(String name) throws Exception {
        Field field = QuickContainerLock.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = QuickContainerLock.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
