package com.yiyihehe.quickcraft.config;

import com.google.gson.JsonParser;
import com.yiyihehe.quickcraft.QuickCraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/** CONF-02：合法 JSON 中配置节点类型错误时，只隔离该节点，其他配置继续加载。 */
class QuickCraftConfigsTypeSafetyTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Crafting/Migrations 类型错误时其他合法配置仍加载")
    void wrongNodeTypesDoNotAbortLoading() throws Exception {
        Path configFile = tempDir.resolve(QuickCraft.MOD_ID + ".json");
        Files.writeString(configFile, """
                {
                  "ShownNoticeIds": ["type_safety_notice"],
                  "Crafting": [],
                  "ContainerTools": {"quickTradeStandby": true},
                  "Migrations": true,
                  "ButtonPositions": {"demo": {"x": 3, "y": -4}}
                }
                """, StandardCharsets.UTF_8);

        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        try (MockedStatic<QuickCraftConfigsAccess> access = mockStatic(QuickCraftConfigsAccess.class)) {
            access.when(QuickCraftConfigsAccess::getConfigDirectory).thenReturn(tempDir);
            access.when(() -> QuickCraftConfigsAccess.parseJsonFile(any(Path.class))).thenAnswer(
                    invocation -> JsonParser.parseString(
                            Files.readString(invocation.getArgument(0), StandardCharsets.UTF_8)
                    )
            );
            QuickCraftConfigs.loadFromFile();
        }

        assertThat(QuickCraftConfigs.hasSeenNotice("type_safety_notice")).isTrue();
        assertThat(QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.getBooleanValue()).isTrue();
        assertThat(QuickCraftConfigs.getActionButtonOffset("demo")).isEqualTo(
                new QuickCraftConfigs.ButtonOffset(3, -4)
        );
    }

    @Test
    @DisplayName("四个配置节点都是错误类型时按缺键处理，不抛异常")
    void allWrongNodeTypesFallBackToMissingKeys() throws Exception {
        Path configFile = tempDir.resolve(QuickCraft.MOD_ID + ".json");
        Files.writeString(configFile, """
                {
                  "Crafting": "broken",
                  "ContainerTools": [],
                  "Migrations": null,
                  "ButtonPositions": false
                }
                """, StandardCharsets.UTF_8);

        try (MockedStatic<QuickCraftConfigsAccess> access = mockStatic(QuickCraftConfigsAccess.class)) {
            access.when(QuickCraftConfigsAccess::getConfigDirectory).thenReturn(tempDir);
            access.when(() -> QuickCraftConfigsAccess.parseJsonFile(any(Path.class))).thenAnswer(
                    invocation -> JsonParser.parseString(
                            Files.readString(invocation.getArgument(0), StandardCharsets.UTF_8)
                    )
            );
            QuickCraftConfigs.loadFromFile();
        }

        assertThat(QuickCraftConfigs.getActionButtonOffset("missing")).isEqualTo(
                new QuickCraftConfigs.ButtonOffset(0, 0)
        );
    }
}
