package com.yiyihehe.quickcraft.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.fabricmc.loader.impl.FabricLoaderImpl;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftIpnDefaultsTest {
    private static Path previousGameDir;
    private static Path previousConfigDir;

    @BeforeAll
    static void initializeMinecraftVersion() throws ReflectiveOperationException {
        SharedConstants.createGameVersion();
        Field initialized = Bootstrap.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.setBoolean(null, true);

        // MaLiLib 0.27.14–0.27.18 在配置类初始化时读取游戏目录，单元测试没有启动 Fabric Loader。
        Field gameDir = FabricLoaderImpl.class.getDeclaredField("gameDir");
        gameDir.setAccessible(true);
        previousGameDir = (Path) gameDir.get(FabricLoaderImpl.INSTANCE);
        gameDir.set(FabricLoaderImpl.INSTANCE, Path.of(".").toAbsolutePath().normalize());
        Field configDir = FabricLoaderImpl.class.getDeclaredField("configDir");
        configDir.setAccessible(true);
        previousConfigDir = (Path) configDir.get(FabricLoaderImpl.INSTANCE);
        configDir.set(FabricLoaderImpl.INSTANCE, Path.of(".").toAbsolutePath().normalize());
    }

    @AfterAll
    static void restoreGameDir() throws ReflectiveOperationException {
        Field gameDir = FabricLoaderImpl.class.getDeclaredField("gameDir");
        gameDir.setAccessible(true);
        gameDir.set(FabricLoaderImpl.INSTANCE, previousGameDir);
        Field configDir = FabricLoaderImpl.class.getDeclaredField("configDir");
        configDir.setAccessible(true);
        configDir.set(FabricLoaderImpl.INSTANCE, previousConfigDir);
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
