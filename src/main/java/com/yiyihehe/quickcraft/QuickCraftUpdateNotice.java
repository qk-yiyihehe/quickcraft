package com.yiyihehe.quickcraft;

import com.google.gson.JsonParser;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 每次启动检查一次，网络线程只发布结果，聊天显示与配置保存留在客户端线程。 */
final class QuickCraftUpdateNotice {
    private static boolean checked;
    private static volatile QuickCraftNotice pending;

    private QuickCraftUpdateNotice() {
    }

    static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!QuickCraftConfigs.ModSupport.CHECK_UPDATE_NOTICES.getBooleanValue()) {
                pending = null;
                return;
            }
            if (!checked) {
                checked = true;
                FabricLoader loader = FabricLoader.getInstance();
                String minecraft = loader.getModContainer("minecraft").orElseThrow()
                        .getMetadata().getVersion().getFriendlyString();
                String installed = loader.getModContainer(QuickCraft.MOD_ID).orElseThrow()
                        .getMetadata().getVersion().getFriendlyString();
                Thread.ofPlatform().daemon(true).name("QuickCraft update notice")
                        .start(() -> pending = fetch(minecraft, installed));
            }
            QuickCraftNotice notice = pending;
            if (notice == null || client.player == null || client.world == null || client.currentScreen != null) {
                return;
            }
            pending = null;
            if (QuickCraftConfigs.hasSeenNotice(notice.id())) {
                return;
            }
            var message = Text.literal("[QuickCraft] ").formatted(Formatting.GOLD)
                    .append(Text.translatable(notice.announcement()
                            ? "quickcraft.message.update_notice.announcement"
                            : "quickcraft.message.update_notice.update", notice.version()).formatted(Formatting.WHITE))
                    .append(Text.literal(notice.message(client.options.language)).formatted(Formatting.WHITE))
                    .append(Text.literal(" "))
                    .append(Text.translatable("quickcraft.message.update_notice.open")
                            .styled(style -> style.withColor(Formatting.AQUA).withUnderline(true)
                                    .withClickEvent(QuickCraftUpdateNoticeAccess.openUrl(URI.create(notice.url())))));
            client.inGameHud.getChatHud().addMessage(message);
            QuickCraftConfigs.markNoticeSeen(notice.id());
        });
    }

    private static QuickCraftNotice fetch(String minecraft, String installed) {
        HttpURLConnection connection = null;
        try {
            String filter = URLEncoder.encode("[\"" + minecraft + "\"]", StandardCharsets.UTF_8);
            connection = (HttpURLConnection) URI.create("https://api.modrinth.com/v2/project/totNXL64/version"
                    + "?loaders=%5B%22fabric%22%5D&game_versions=" + filter).toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "qk-yiyihehe/quickcraft/" + installed
                    + " (https://github.com/qk-yiyihehe/quickcraft)");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (var stream = connection.getInputStream()) {
                // ponytail: 单次响应最多 2 MiB；历史日志超限时改为先查版本、再取单条日志。
                int limit = 2 * 1024 * 1024;
                byte[] body = stream.readNBytes(limit + 1);
                if (body.length > limit) {
                    return null;
                }
                return QuickCraftNotice.select(JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                        .getAsJsonArray(), minecraft, installed);
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
