package com.yiyihehe.quickcraft.config;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftIpnDefaultsTest {
    @BeforeAll
    static void initializeGameVersion() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        resetMigrationState();
        setButtonsVisible(true);
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        resetMigrationState();
        setButtonsVisible(true);
    }

    @Test
    @DisplayName("未加载 IPN 时不改变按钮配置，也不消耗一次性迁移")
    void leavesDefaultsUntouchedWithoutIpn() {
        assertThat(QuickCraftConfigs.applyIpnButtonDefaultsIfNeeded(false)).isFalse();
        assertThat(QuickCraftConfigs.ContainerTools.SHOW_QUICK_STASH_BUTTON.getBooleanValue()).isTrue();
        assertThat(QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_LOCK_BUTTON.getBooleanValue()).isTrue();

        assertThat(QuickCraftConfigs.applyIpnButtonDefaultsIfNeeded(true)).isTrue();
    }

    @Test
    @DisplayName("IPN 默认只关闭一次，玩家手动重新开启后不再覆盖")
    void onlyAppliesIpnDefaultsOnce() {
        assertThat(QuickCraftConfigs.applyIpnButtonDefaultsIfNeeded(true)).isTrue();
        assertThat(QuickCraftConfigs.ContainerTools.SHOW_QUICK_STASH_BUTTON.getBooleanValue()).isFalse();
        assertThat(QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_LOCK_BUTTON.getBooleanValue()).isFalse();

        setButtonsVisible(true);

        assertThat(QuickCraftConfigs.applyIpnButtonDefaultsIfNeeded(true)).isFalse();
        assertThat(QuickCraftConfigs.ContainerTools.SHOW_QUICK_STASH_BUTTON.getBooleanValue()).isTrue();
        assertThat(QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_LOCK_BUTTON.getBooleanValue()).isTrue();
    }

    private static void setButtonsVisible(boolean visible) {
        QuickCraftConfigs.ContainerTools.SHOW_QUICK_STASH_BUTTON.setBooleanValue(visible);
        QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_LOCK_BUTTON.setBooleanValue(visible);
    }

    private static void resetMigrationState() throws ReflectiveOperationException {
        Field field = QuickCraftConfigs.class.getDeclaredField("ipnButtonDefaultsApplied");
        field.setAccessible(true);
        field.setBoolean(null, false);
    }
}
