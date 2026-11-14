package com.yiyihehe.quickcraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickContainerLockIdentityTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final BlockPos SECOND = new BlockPos(1, 64, 0);
    private static final AtomicReference<Screen> CURRENT_SCREEN = new AtomicReference<>();
    private MockedStatic<QuickClientScreenAccess> screenAccess;

    @BeforeEach
    void installScreenAccess() {
        CURRENT_SCREEN.set(null);
        screenAccess = mockStatic(QuickClientScreenAccess.class);
        screenAccess.when(() -> QuickClientScreenAccess.currentScreen(any(Minecraft.class)))
                .thenAnswer(invocation -> CURRENT_SCREEN.get());
    }

    @AfterEach
    void clearState() {
        QuickContainerLock.clearPersistentState();
        screenAccess.close();
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
        ClientLevel independent = world(pos -> pos.equals(FIRST)
                ? Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.SINGLE)
                : pos.equals(SECOND)
                ? Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.SINGLE)
                : Blocks.AIR.defaultBlockState());
        assertThat(QuickContainerLock.buildContainerKey(independent, FIRST))
                .isNotEqualTo(QuickContainerLock.buildContainerKey(independent, SECOND));

        ClientLevel wrongDirection = world(pos -> pos.equals(FIRST)
                ? left
                : pos.equals(SECOND) ? chest(ChestType.RIGHT, Direction.SOUTH) : Blocks.AIR.defaultBlockState());
        assertThat(QuickContainerLock.buildContainerKey(wrongDirection, FIRST))
                .isNotEqualTo(QuickContainerLock.buildContainerKey(wrongDirection, SECOND));

        ClientLevel wrongType = world(pos -> pos.equals(FIRST)
                ? left
                : pos.equals(SECOND) ? chest(ChestType.LEFT, Direction.NORTH) : Blocks.AIR.defaultBlockState());
        assertThat(QuickContainerLock.buildContainerKey(wrongType, FIRST))
                .isNotEqualTo(QuickContainerLock.buildContainerKey(wrongType, SECOND));
    }

    @Test
    void splitAndRejoinKeepsOnlyTheActuallyCreatedIdentity() {
        ClientLevel paired = pairWorld(Direction.NORTH);
        String pairedKey = QuickContainerLock.buildContainerKey(paired, FIRST);

        ClientLevel split = world(pos -> pos.equals(SECOND)
                ? Blocks.CHEST.defaultBlockState()
                : Blocks.AIR.defaultBlockState());
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
        assertThat(lock.getAsJsonArray("lockedContainers"))
                .extracting(JsonElement::getAsString)
                .containsExactlyInAnyOrder("minecraft:overworld#legacy-left", "minecraft:overworld#legacy-right");
        assertThat(lock.getAsJsonObject("lockedContainerSlots").getAsJsonArray("minecraft:overworld#legacy-left"))
                .extracting(JsonElement::getAsInt).containsExactly(0);
        assertThat(lock.getAsJsonObject("lockedContainerSlots").getAsJsonArray("minecraft:overworld#legacy-right"))
                .extracting(JsonElement::getAsInt).containsExactly(27);
    }

    @Test
    void onlyProvableLegacyDoubleChestSlotsAreCopiedToTheCanonicalKey() throws Exception {
        ClientLevel world = pairWorld(Direction.NORTH);
        String dimension = world.dimension().identifier().toString();
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
        ClientLevel world = pairWorld(Direction.NORTH);
        String legacy = world.dimension().identifier() + "#" + SECOND.asLong();
        JsonObject root = JsonParser.parseString("""
                {"containerLock":{"lockedContainers":["%s"],"lockedPlayerSlots":[],
                 "lockedContainerSlots":{"%s":[27]}}}
                """.formatted(legacy, legacy)).getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);
        String canonical = QuickContainerLock.buildContainerKey(world, FIRST);
        LocalPlayer player = mock(LocalPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        ChestMenu handler = ChestMenu.sixRows(51, inventory);
        AbstractContainerScreen<?> screen = screen(handler);
        Minecraft client = mock(Minecraft.class);
        client.player = player;
        client.level = world;

        try (var minecraft = mockStatic(Minecraft.class);
             var persistent = mockStatic(QuickPersistentState.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
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
            assertThat(state.getAsJsonArray("lockedContainers"))
                    .extracting(JsonElement::getAsString).containsExactly(legacy);
            assertThat(state.getAsJsonObject("lockedContainerSlots").getAsJsonArray(legacy))
                    .extracting(JsonElement::getAsInt).containsExactly(27);
        }
    }

    @Test
    void ambiguousLegacyHalfRecordIsNotExpandedToThePair() throws Exception {
        ClientLevel world = pairWorld(Direction.NORTH);
        String dimension = world.dimension().identifier().toString();
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
        ClientLevel world = pairWorld(Direction.NORTH);
        String firstKey = QuickContainerLock.buildContainerKey(world, FIRST);
        String secondKey = QuickContainerLock.buildContainerKey(world, SECOND);
        assertThat(firstKey).isEqualTo(secondKey);

        LocalPlayer player = mock(LocalPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        ChestMenu rightHandler = ChestMenu.sixRows(41, inventory);
        ChestMenu leftHandler = ChestMenu.sixRows(42, inventory);
        ChestMenu reopenedHandler = ChestMenu.sixRows(43, inventory);
        AbstractContainerScreen<?> rightScreen = screen(rightHandler);
        AbstractContainerScreen<?> leftScreen = screen(leftHandler);
        AbstractContainerScreen<?> reopenedScreen = screen(reopenedHandler);

        Minecraft client = mock(Minecraft.class);
        client.player = player;
        client.level = world;

        try (var minecraft = mockStatic(Minecraft.class);
             var persistent = mockStatic(QuickPersistentState.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickContainerLock lock = new QuickContainerLock();

            setScreen(client, rightScreen);
            open(lock, client, rightScreen, firstKey);
            QuickContainerLock.toggleCurrentScreenLock(client, rightScreen);
            assertThat(QuickContainerLock.isCurrentScreenLocked(rightScreen)).isTrue();

            setScreen(client, leftScreen);
            open(lock, client, leftScreen, secondKey);
            assertThat(QuickContainerLock.isCurrentScreenLocked(leftScreen)).isTrue();
            invokeToggleSlotLock(lock, leftScreen, leftHandler.getSlot(0));
            assertThat(QuickContainerLock.isLockedSlot(leftHandler, leftHandler.getSlot(0))).isTrue();

            setScreen(client, reopenedScreen);
            open(lock, client, reopenedScreen, firstKey);
            assertThat(QuickContainerLock.isCurrentScreenLocked(reopenedScreen)).isTrue();
            assertThat(QuickContainerLock.isLockedSlot(reopenedHandler, reopenedHandler.getSlot(0))).isTrue();
        }
    }

    @Test
    void oldHandlerMappingDoesNotFollowAReusedSyncId() throws Exception {
        LocalPlayer player = mock(LocalPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        ChestMenu first = ChestMenu.threeRows(7, inventory);
        ChestMenu second = ChestMenu.threeRows(7, inventory);
        assertThat(first.containerId).isEqualTo(second.containerId);

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
        LocalPlayer player = mock(LocalPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        ChestMenu first = ChestMenu.threeRows(7, inventory);
        ChestMenu second = ChestMenu.threeRows(7, inventory);
        AbstractContainerScreen<?> secondScreen = screen(second);
        JsonObject root = JsonParser.parseString("""
                {"containerLock":{"lockedContainers":["key-a"],"lockedPlayerSlots":[],
                 "lockedContainerSlots":{"key-a":[0]}}}""").getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);
        invokeRememberHandlerKey(first, "key-a");

        Minecraft client = mock(Minecraft.class);
        client.player = player;
        try (var minecraft = mockStatic(Minecraft.class);
             var persistent = mockStatic(QuickPersistentState.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickContainerLock lock = new QuickContainerLock();
            setScreen(client, null);
            setStaticField("pendingContainerKey", "key-b");
            setStaticField("pendingTicks", 21);
            Method process = QuickContainerLock.class.getDeclaredMethod("processPendingOpen", Minecraft.class);
            process.setAccessible(true);
            process.invoke(lock, client);

            setScreen(client, secondScreen);
            QuickContainerLock.bindCurrentScreen(secondScreen);
            assertThat(QuickContainerLock.isCurrentScreenLocked(secondScreen)).isFalse();
            assertThat(QuickContainerLock.isLockedSlot(second, second.getSlot(0))).isFalse();
        }
    }

    @Test
    void staleScreenCallbackCannotReplaceCurrentScreenKey() throws Exception {
        LocalPlayer player = mock(LocalPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        ChestMenu oldHandler = ChestMenu.threeRows(11, inventory);
        ChestMenu currentHandler = ChestMenu.sixRows(12, inventory);
        AbstractContainerScreen<?> oldScreen = screen(oldHandler);
        AbstractContainerScreen<?> currentScreen = screen(currentHandler);
        JsonObject root = JsonParser.parseString("""
                {"containerLock":{"lockedContainers":["key-b"],"lockedPlayerSlots":[],
                 "lockedContainerSlots":{}}}""").getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);
        invokeRememberHandlerKey(oldHandler, "key-a");

        Minecraft client = mock(Minecraft.class);
        client.player = player;
        try (var minecraft = mockStatic(Minecraft.class);
             var persistent = mockStatic(QuickPersistentState.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickContainerLock lock = new QuickContainerLock();
            setScreen(client, currentScreen);
            setStaticField("pendingContainerKey", "key-b");
            Method process = QuickContainerLock.class.getDeclaredMethod("processPendingOpen", Minecraft.class);
            process.setAccessible(true);
            process.invoke(lock, client);

            QuickContainerLock.bindCurrentScreen(oldScreen);
            assertThat(QuickContainerLock.isCurrentScreenLocked(currentScreen)).isTrue();
        }
    }

    private static void open(QuickContainerLock lock, Minecraft client, AbstractContainerScreen<?> screen,
                             String key) throws Exception {
        setScreen(client, screen);
        setStaticField("pendingContainerKey", key);
        Method process = QuickContainerLock.class.getDeclaredMethod("processPendingOpen", Minecraft.class);
        process.setAccessible(true);
        process.invoke(lock, client);
    }

    private static void invokeToggleSlotLock(Object lock, AbstractContainerScreen<?> screen, Slot slot)
            throws Exception {
        Method toggle = QuickContainerLock.class.getDeclaredMethod(
                "toggleSlotLock", AbstractContainerScreen.class, Slot.class);
        toggle.setAccessible(true);
        toggle.invoke(lock, screen, slot);
    }

    private static void invokeRememberHandlerKey(AbstractContainerMenu handler, String key) throws Exception {
        Method remember = QuickContainerLock.class.getDeclaredMethod(
                "rememberHandlerKey", AbstractContainerMenu.class, String.class);
        remember.setAccessible(true);
        remember.invoke(null, handler, key);
    }

    private static void assertSameKey(Direction facing) {
        BlockPos second = FIRST.relative(ChestBlock.getConnectedDirection(chest(ChestType.LEFT, facing)));
        ClientLevel world = pairWorld(facing, FIRST, second);
        assertThat(QuickContainerLock.buildContainerKey(world, FIRST))
                .isEqualTo(QuickContainerLock.buildContainerKey(world, second));
    }

    private static ClientLevel pairWorld(Direction facing) {
        return pairWorld(facing, FIRST, SECOND);
    }

    private static ClientLevel pairWorld(Direction facing, BlockPos first, BlockPos second) {
        return world(pos -> pos.equals(first)
                ? chest(ChestType.LEFT, facing)
                : pos.equals(second) ? chest(ChestType.RIGHT, facing) : Blocks.AIR.defaultBlockState());
    }

    private static ClientLevel world(java.util.function.Function<BlockPos, BlockState> states) {
        ClientLevel world = mock(ClientLevel.class);
        when(world.dimension()).thenReturn(Level.OVERWORLD);
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation ->
                states.apply(invocation.getArgument(0)));
        return world;
    }

    private static BlockState chest(ChestType type, Direction facing) {
        return Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.TYPE, type)
                .setValue(ChestBlock.FACING, facing);
    }

    private static AbstractContainerScreen<?> screen(AbstractContainerMenu menu) {
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class);
        when(screen.getMenu()).thenReturn(menu);
        return screen;
    }

    private static void setScreen(Minecraft client, Screen screen) {
        CURRENT_SCREEN.set(screen);
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
