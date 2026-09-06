package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.futurecompat.FutureCompatEditorScreen;
import com.yiyihehe.quickcraft.futurecompat.FutureCompatScanService;
import com.yiyihehe.quickcraft.futurecompat.SchematicVersionGate;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;

/**
 * 未来版本映射的浏览器入口：在浏览器底部按钮行挂一个 malilib 按钮（与其他按钮同行、同间距节奏），
 * 仅当选中的文件被判定为未来版本时显示（检测期间/非未来文件时移出屏幕）。
 * 点击打开对应文件的映射编辑器；扫描为异步，每帧更新可见性。
 */
public final class QuickLitematicaFutureCompatBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickLitematicaFutureCompatBridge.class);
    /** 主菜单按钮（右对齐）的预留宽度：图标 + 文本 + 内边距的估算上限。 */
    private static final int MAIN_MENU_RESERVE = 96;
    private static final int ROW_RIGHT_MARGIN = 10;
    private static final int ROW_SPACING = 4;

    @Nullable
    private static ButtonGeneric browserButton;
    private static int browserButtonWidth;
    @Nullable
    private static Path lastFile;
    @Nullable
    private static Screen parentScreen;
    @Nullable
    private static FutureCompatScanService.ScanSnapshot lastSnapshot;

    private QuickLitematicaFutureCompatBridge() {
    }

    /** 浏览器界面重建（initGui）时调用：旧按钮对象已随旧界面作废，置空后下一帧懒重建。 */
    public static void onBrowserGuiInit() {
        browserButton = null;
    }

    /** 浏览器每帧调用（WidgetSchematicBrowserMixin 的绘制钩子）。 */
    public static void updateBrowserButton(GuiSchematicBrowserBase gui, @Nullable DirectoryEntry entry) {
        if (!QuickCraftConfigs.isMapFutureLitematicIdsEnabled()) {
            return;
        }
        if (browserButton == null) {
            browserButton = gui.addButton(new ButtonGeneric(
                    0, 0, -1, 20,
                    Component.translatable("quickcraft.future_compat.button.short").getString()),
                    (button, mouseButton) -> openEditor());
            browserButtonWidth = browserButton.getWidth();
        }

        boolean show = false;
        Path file = entry == null ? null : entry.getFullPath();
        if (file != null) {
            // 每帧调用：缓存命中直接返回；被失效（如保存映射后）自动重扫，inFlight 去重防重复提交
            FutureCompatScanService.get().scanAsync(file);
            FutureCompatScanService.ScanSnapshot snapshot = FutureCompatScanService.get().peek(file);
            show = snapshot != null && snapshot.decision() == SchematicVersionGate.ProbeDecision.FUTURE;
            if (show) {
                lastFile = file;
                lastSnapshot = snapshot;
                parentScreen = gui;
            }
        }

        int height = gui.height;
        if (show) {
            // 底部按钮行内、主菜单按钮左侧，行内节奏 +4px
            browserButton.setPosition(
                    gui.width - browserButtonWidth - ROW_RIGHT_MARGIN - MAIN_MENU_RESERVE - ROW_SPACING,
                    height - 26);
        } else {
            browserButton.setPosition(-20000, -20000);
        }
    }

    private static void openEditor() {
        if (lastFile == null || lastSnapshot == null || parentScreen == null) {
            return;
        }
        Minecraft.getInstance().setScreenAndShow(new FutureCompatEditorScreen(
                parentScreen, lastFile, lastSnapshot.result()));
    }
}
