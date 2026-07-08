package com.yiyihehe.quickcraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * QuickCraft 的轻量本地状态持久化。
 *
 * <p>目前只负责格子锁/容器锁和村民收藏交易。状态按单人存档路径或多人服务器地址隔离，
 * 避免同一个玩家在不同世界之间共享容器锁和交易收藏。</p>
 */
public final class QuickPersistentState {
    private static final String STATE_DIR_NAME = "state";
    private static final String STATE_FILE_NAME = "state.json";
    private static final String SINGLEPLAYER_DIR = "singleplayer";
    private static final String MULTIPLAYER_DIR = "multiplayer";

    private static String currentProfileId;
    private static Path currentStateFile;

    private QuickPersistentState() {
    }

    public static void onClientTick(MinecraftClient client) {
        ProfileContext profile = resolveProfileContext(client);
        if (profile == null) {
            if (currentProfileId != null) {
                clearLoadedState();
            }
            return;
        }

        if (profile.profileId().equals(currentProfileId) && profile.stateFile().equals(currentStateFile)) {
            return;
        }

        currentProfileId = profile.profileId();
        currentStateFile = profile.stateFile();
        loadCurrentProfileState(client);
    }

    /**
     * 保存当前世界/服务器的 QuickCraft 本地状态。
     *
     * <p>调用方只需要在业务状态变更后触发保存；这里统一写入 schema、profileId 和各功能自己的状态块。</p>
     */
    public static void saveCurrentProfileState() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || currentStateFile == null) {
            return;
        }

        Path dir = currentStateFile.getParent();
        if (dir == null) {
            return;
        }

        if (!Files.exists(dir)) {
            FileUtils.createDirectoriesIfMissing(dir);
        }
        if (!Files.isDirectory(dir)) {
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("profileId", currentProfileId);
        QuickContainerLock.writePersistentState(root);
        QuickTrade.writePersistentState(root, client.world.getRegistryManager());
        JsonUtils.writeJsonToFileAsPath(root, currentStateFile);
    }

    private static void loadCurrentProfileState(MinecraftClient client) {
        QuickContainerLock.clearPersistentState();
        QuickTrade.clearPersistentState();

        if (client == null || client.world == null || currentStateFile == null) {
            return;
        }
        if (!Files.exists(currentStateFile) || !Files.isReadable(currentStateFile)) {
            return;
        }

        JsonElement element = JsonUtils.parseJsonFileAsPath(currentStateFile);
        if (element == null || !element.isJsonObject()) {
            return;
        }

        JsonObject root = element.getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);
        QuickTrade.loadPersistentState(root, client.world.getRegistryManager());
    }

    private static void clearLoadedState() {
        currentProfileId = null;
        currentStateFile = null;
        QuickContainerLock.clearPersistentState();
        QuickTrade.clearPersistentState();
    }

    private static ProfileContext resolveProfileContext(MinecraftClient client) {
        if (client == null || client.world == null) {
            return null;
        }

        if (client.isInSingleplayer()) {
            IntegratedServer server = client.getServer();
            if (server == null) {
                return null;
            }

            Path saveRoot = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            String displayName = saveRoot.getFileName() != null ? saveRoot.getFileName().toString() : saveRoot.toString();
            return createProfileContext(SINGLEPLAYER_DIR, saveRoot.toString(), displayName);
        }

        ServerInfo serverInfo = client.getCurrentServerEntry();
        if (serverInfo == null || serverInfo.address == null || serverInfo.address.isBlank()) {
            return null;
        }

        String address = serverInfo.address.trim();
        return createProfileContext(MULTIPLAYER_DIR, address, address);
    }

    /**
     * 为存档或服务器生成稳定目录名。
     *
     * <p>可读名称只用于目录展示，末尾 UUID 短后缀来自原始 key，避免同名存档或同名服务器互相覆盖。</p>
     */
    private static ProfileContext createProfileContext(String scope, String rawKey, String displayName) {
        String profileId = scope + ":" + rawKey;
        Path stateFile = FileUtils.getConfigDirectoryAsPath()
                .resolve(QuickCraft.MOD_ID)
                .resolve(STATE_DIR_NAME)
                .resolve(scope)
                .resolve(buildDirectoryName(displayName, rawKey))
                .resolve(STATE_FILE_NAME);
        return new ProfileContext(profileId, stateFile);
    }

    private static String buildDirectoryName(String displayName, String rawKey) {
        if (displayName == null || displayName.isBlank()) {
            displayName = "profile";
        }
        String sanitized = displayName
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("[. ]+$", "");
        if (sanitized.isBlank()) {
            sanitized = "profile";
        }
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }

        String suffix = UUID.nameUUIDFromBytes(rawKey.getBytes(StandardCharsets.UTF_8))
                .toString()
                .substring(0, 8);
        return sanitized + "-" + suffix;
    }

    private record ProfileContext(String profileId, Path stateFile) {
    }
}
