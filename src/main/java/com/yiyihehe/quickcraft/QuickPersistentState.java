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
 * 目前只负责格子锁/容器锁和村民收藏交易。
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
