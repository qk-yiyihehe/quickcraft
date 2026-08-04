package com.yiyihehe.quickcraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

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

    public static void onClientTick(Minecraft client) {
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
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || currentStateFile == null) {
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
        QuickTrade.writePersistentState(root, client.level.registryAccess());
        JsonUtils.writeJsonToFile(root, currentStateFile);
    }

    private static void loadCurrentProfileState(Minecraft client) {
        QuickContainerLock.clearPersistentState();
        QuickTrade.clearPersistentState();

        if (client == null || client.level == null || currentStateFile == null) {
            return;
        }
        if (!Files.exists(currentStateFile) || !Files.isReadable(currentStateFile)) {
            return;
        }

        JsonElement element = JsonUtils.parseJsonFile(currentStateFile);
        if (element == null || !element.isJsonObject()) {
            return;
        }

        JsonObject root = element.getAsJsonObject();
        QuickContainerLock.loadPersistentState(root);
        QuickTrade.loadPersistentState(root, client.level.registryAccess());
    }

    private static void clearLoadedState() {
        currentProfileId = null;
        currentStateFile = null;
        QuickContainerLock.clearPersistentState();
        QuickTrade.clearPersistentState();
    }

    private static ProfileContext resolveProfileContext(Minecraft client) {
        if (client == null || client.level == null) {
            return null;
        }

        if (client.hasSingleplayerServer()) {
            IntegratedServer server = client.getSingleplayerServer();
            if (server == null) {
                return null;
            }

            Path saveRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            String displayName = saveRoot.getFileName() != null ? saveRoot.getFileName().toString() : saveRoot.toString();
            return createProfileContext(SINGLEPLAYER_DIR, saveRoot.toString(), displayName);
        }

        ServerData serverInfo = client.getCurrentServer();
        if (serverInfo == null || serverInfo.ip == null || serverInfo.ip.isBlank()) {
            return null;
        }

        String address = serverInfo.ip.trim();
        return createProfileContext(MULTIPLAYER_DIR, address, address);
    }

    private static ProfileContext createProfileContext(String scope, String rawKey, String displayName) {
        String profileId = scope + ":" + rawKey;
        Path stateFile = FileUtils.getConfigDirectory()
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
