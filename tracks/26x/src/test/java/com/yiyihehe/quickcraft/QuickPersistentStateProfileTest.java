package com.yiyihehe.quickcraft;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** BUG-07：本地世界开放 LAN 后仍使用单机档案；远程服务器使用多人档案。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickPersistentStateProfileTest {
    private final RegistryAccess lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    @TempDir
    Path tempDir;

    @AfterEach
    void clearState() throws Exception {
        QuickPersistentState.onClientTick(null);
    }

    @Test
    void localWorldKeepsProfileAfterLanAndPersistsExistingAndNewData() throws Exception {
        Minecraft client = mockClient();
        ClientLevelStub.install(client, lookup);
        IntegratedServer server = mock(IntegratedServer.class);
        Path saveRoot = tempDir.resolve("world");
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(saveRoot);
        when(client.getSingleplayerServer()).thenReturn(server);
        when(client.hasSingleplayerServer()).thenReturn(true);

        Object firstProfile;
        Object firstFile;
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickPersistentState.onClientTick(client);
            firstProfile = getStatic("currentProfileId");
            firstFile = getStatic("currentStateFile");
            assertThat(firstProfile).isNotNull();
            assertThat(firstFile).isNotNull();
            assertThat(firstProfile.toString()).startsWith("singleplayer:");
            assertThat(firstFile.toString()).contains("singleplayer");
            assertThat(((Path) firstFile).normalize().startsWith(tempDir.normalize())).isTrue();

            Path stateFile = (Path) firstFile;
            JsonObject seed = new JsonObject();
            JsonObject containerLock = new JsonObject();
            JsonArray containers = new JsonArray();
            containers.add("chest-key");
            containerLock.add("lockedContainers", containers);
            JsonArray playerSlots = new JsonArray();
            playerSlots.add(3);
            containerLock.add("lockedPlayerSlots", playerSlots);
            JsonObject containerSlots = new JsonObject();
            JsonArray chestSlots = new JsonArray();
            chestSlots.add(1);
            chestSlots.add(2);
            containerSlots.add("chest-key", chestSlots);
            containerLock.add("lockedContainerSlots", containerSlots);
            seed.add("containerLock", containerLock);
            QuickTradeTestSupport.loadFavorite("merchant-key",
                    new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
            QuickTrade.writePersistentState(seed, lookup);
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, new Gson().toJson(seed));

            QuickPersistentState.onClientTick(null);
            QuickPersistentState.onClientTick(client);
            assertThat(getStatic("currentProfileId")).isEqualTo(firstProfile);
            assertThat(getStatic("currentStateFile")).isEqualTo(firstFile);
            assertThat((Set<String>) getStatic(QuickContainerLock.class, "LOCKED_CONTAINERS")).contains("chest-key");
            assertThat((Set<Integer>) getStatic(QuickContainerLock.class, "LOCKED_PLAYER_SLOTS")).contains(3);
            assertThat((java.util.Map<String, ?>) QuickTradeTestSupport.getStaticField(
                    QuickTrade.class, "FAVORITE_TRADES")).containsKey("merchant-key");

            QuickContainerLock.togglePlayerInventoryLock(client);
            QuickTradeTestSupport.loadFavorite("new-merchant",
                    new ItemStack(Items.EMERALD), new ItemStack(Items.APPLE));
            QuickPersistentState.saveCurrentProfileState();

            QuickPersistentState.onClientTick(null);
            QuickPersistentState.onClientTick(client);
            assertThat((Set<String>) getStatic(QuickContainerLock.class, "LOCKED_CONTAINERS"))
                    .contains("chest-key", "player_inventory");
            assertThat((Set<Integer>) getStatic(QuickContainerLock.class, "LOCKED_PLAYER_SLOTS")).contains(3);
            assertThat((java.util.Map<String, ?>) QuickTradeTestSupport.getStaticField(
                    QuickTrade.class, "FAVORITE_TRADES"))
                    .containsKeys("merchant-key", "new-merchant");
        }
    }

    @Test
    void remoteServerUsesMultiplayerProfile() throws Exception {
        Minecraft client = mockClient();
        ClientLevelStub.install(client, lookup);
        when(client.hasSingleplayerServer()).thenReturn(false);
        when(client.getCurrentServer()).thenReturn(
                new ServerData("remote", "example.com:25565", ServerData.Type.OTHER));

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickPersistentState.onClientTick(client);

            assertThat(getStatic("currentProfileId").toString()).isEqualTo("multiplayer:example.com:25565");
            assertThat(getStatic("currentStateFile").toString()).contains("multiplayer");
        }
    }

    private static Object getStatic(String name) throws Exception {
        return getStatic(QuickPersistentState.class, name);
    }

    private static Object getStatic(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private Minecraft mockClient() throws Exception {
        Minecraft client = mock(Minecraft.class);
        Field gameDirectory = Minecraft.class.getField("gameDirectory");
        gameDirectory.setAccessible(true);
        gameDirectory.set(client, tempDir.toFile());
        return client;
    }

    private static final class ClientLevelStub {
        private static void install(Minecraft client, RegistryAccess lookup) throws Exception {
            ClientLevel level = mock(ClientLevel.class);
            when(level.registryAccess()).thenReturn(lookup);
            Field field = Minecraft.class.getField("level");
            field.setAccessible(true);
            field.set(client, level);
        }
    }
}
