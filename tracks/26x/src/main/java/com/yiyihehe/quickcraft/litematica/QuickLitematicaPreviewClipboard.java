package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.platform.NativeImage;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;
import com.sun.jna.platform.win32.User32;

/** Headless 客户端的图片剪贴板；独立管理 Win32 句柄及向系统移交后的内存所有权。 */
final class QuickLitematicaPreviewClipboard {
    private static final int COMPAT_CLIPBOARD_MAX_DIMENSION = 4096;
    private static final QuickLitematicaPreviewLog LOGGER = QuickLitematicaPreviewLog.LOGGER;

    private QuickLitematicaPreviewClipboard() { }

    static void copyImage(NativeImage image, boolean preserveTransparency) throws InterruptedException {
        int width = image.getWidth();
        int height = image.getHeight();
        double compatScale = Math.min(1.0, COMPAT_CLIPBOARD_MAX_DIMENSION / (double) Math.max(width, height));
        int compatWidth = Math.max(1, (int) Math.round(width * compatScale));
        int compatHeight = Math.max(1, (int) Math.round(height * compatScale));
        byte[] pngBytes;
        try {
            pngBytes = preserveTransparency ? encodeClipboardPng(image, compatWidth, compatHeight) : null;
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode transparent clipboard PNG", e);
        }
        long pixelBytes = (long) width * height * 4L;
        long compatPixelBytes = (long) compatWidth * compatHeight * 4L;
        Pointer dibV5Handle = WindowsMemory.INSTANCE.GlobalAlloc(
                WindowsMemory.GHND,
                new BaseTSD.SIZE_T(WindowsMemory.BITMAP_V5_HEADER_SIZE + pixelBytes)
        );
        Pointer dibHandle = WindowsMemory.INSTANCE.GlobalAlloc(
                WindowsMemory.GHND,
                new BaseTSD.SIZE_T(WindowsMemory.BITMAP_INFO_HEADER_SIZE + compatPixelBytes)
        );
        if (dibV5Handle == null || dibHandle == null) {
            if (dibV5Handle != null) {
                WindowsMemory.INSTANCE.GlobalFree(dibV5Handle);
            }
            if (dibHandle != null) {
                WindowsMemory.INSTANCE.GlobalFree(dibHandle);
            }
            throw new IllegalStateException("GlobalAlloc failed");
        }

        boolean clipboardOwnsDibV5 = false;
        boolean clipboardOwnsDib = false;
        boolean clipboardOwnsPng = false;
        boolean clipboardOpen = false;
        Pointer pngHandle = null;
        try {
            Pointer dibV5Memory = WindowsMemory.INSTANCE.GlobalLock(dibV5Handle);
            Pointer dibMemory = WindowsMemory.INSTANCE.GlobalLock(dibHandle);
            if (dibV5Memory == null || dibMemory == null) {
                if (dibV5Memory != null) {
                    WindowsMemory.INSTANCE.GlobalUnlock(dibV5Handle);
                }
                if (dibMemory != null) {
                    WindowsMemory.INSTANCE.GlobalUnlock(dibHandle);
                }
                throw new IllegalStateException("GlobalLock failed");
            }
            try {
                dibV5Memory.write(0, createBitmapV5Header(width, height), 0, WindowsMemory.BITMAP_V5_HEADER_SIZE);
                dibMemory.write(
                        0,
                        createBitmapInfoHeader(compatWidth, compatHeight),
                        0,
                        WindowsMemory.BITMAP_INFO_HEADER_SIZE
                );
                int[] row = new int[width];
                boolean sameDimensions = width == compatWidth && height == compatHeight;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        row[x] = image.getPixel(x, y);
                    }
                    dibV5Memory.write(WindowsMemory.BITMAP_V5_HEADER_SIZE + (long) y * width * 4L, row, 0, width);
                    if (sameDimensions) {
                        dibMemory.write(
                                WindowsMemory.BITMAP_INFO_HEADER_SIZE + (long) (height - 1 - y) * width * 4L,
                                row,
                                0,
                                width
                        );
                    }
                }
                if (!sameDimensions) {
                    int[] compatRow = new int[compatWidth];
                    for (int y = 0; y < compatHeight; y++) {
                        int sourceY = Math.min(height - 1, (int) ((y + 0.5) * height / compatHeight));
                        for (int x = 0; x < compatWidth; x++) {
                            int sourceX = Math.min(width - 1, (int) ((x + 0.5) * width / compatWidth));
                            compatRow[x] = image.getPixel(sourceX, sourceY);
                        }
                        dibMemory.write(
                                WindowsMemory.BITMAP_INFO_HEADER_SIZE
                                        + (long) (compatHeight - 1 - y) * compatWidth * 4L,
                                compatRow,
                                0,
                                compatWidth
                        );
                    }
                }
            } finally {
                WindowsMemory.INSTANCE.GlobalUnlock(dibV5Handle);
                WindowsMemory.INSTANCE.GlobalUnlock(dibHandle);
            }

            if (pngBytes != null) {
                pngHandle = WindowsMemory.INSTANCE.GlobalAlloc(
                        WindowsMemory.GHND,
                        new BaseTSD.SIZE_T(pngBytes.length)
                );
                if (pngHandle == null) {
                    throw new IllegalStateException("GlobalAlloc(PNG) failed");
                }
                Pointer pngMemory = WindowsMemory.INSTANCE.GlobalLock(pngHandle);
                if (pngMemory == null) {
                    throw new IllegalStateException("GlobalLock(PNG) failed");
                }
                try {
                    pngMemory.write(0, pngBytes, 0, pngBytes.length);
                } finally {
                    WindowsMemory.INSTANCE.GlobalUnlock(pngHandle);
                }
            }

            clipboardOpen = openWindowsClipboard();
            if (!clipboardOpen || !WindowsClipboard.INSTANCE.EmptyClipboard()) {
                throw new IllegalStateException("Could not open/empty clipboard");
            }

            if (WindowsClipboard.INSTANCE.SetClipboardData(WindowsClipboard.CF_DIBV5, dibV5Handle) == null) {
                throw new IllegalStateException("SetClipboardData(CF_DIBV5) failed");
            }
            clipboardOwnsDibV5 = true;

            if (WindowsClipboard.INSTANCE.SetClipboardData(WindowsClipboard.CF_DIB, dibHandle) == null) {
                throw new IllegalStateException("SetClipboardData(CF_DIB) failed");
            }
            clipboardOwnsDib = true;

            if (pngHandle != null) {
                int pngFormat = getWindowsPngClipboardFormat();
                if (pngFormat != 0 && WindowsClipboard.INSTANCE.SetClipboardData(pngFormat, pngHandle) != null) {
                    clipboardOwnsPng = true;
                }
            }
        } finally {
            if (clipboardOpen) {
                WindowsClipboard.INSTANCE.CloseClipboard();
            }
            if (!clipboardOwnsDibV5) {
                WindowsMemory.INSTANCE.GlobalFree(dibV5Handle);
            }
            if (!clipboardOwnsDib) {
                WindowsMemory.INSTANCE.GlobalFree(dibHandle);
            }
            if (pngHandle != null && !clipboardOwnsPng) {
                WindowsMemory.INSTANCE.GlobalFree(pngHandle);
            }
        }
    }

    private static byte[] encodeClipboardPng(NativeImage image, int width, int height) throws IOException {
        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        if (width == image.getWidth() && height == image.getHeight()) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    bufferedImage.setRGB(x, y, image.getPixel(x, y));
                }
            }
        } else {
            for (int y = 0; y < height; y++) {
                int sourceY = Math.min(image.getHeight() - 1, (int) ((y + 0.5) * image.getHeight() / height));
                for (int x = 0; x < width; x++) {
                    int sourceX = Math.min(image.getWidth() - 1, (int) ((x + 0.5) * image.getWidth() / width));
                    bufferedImage.setRGB(x, y, image.getPixel(sourceX, sourceY));
                }
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "PNG", output);
        return output.toByteArray();
    }

    private static boolean openWindowsClipboard() throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (WindowsClipboard.INSTANCE.OpenClipboard(null)) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static byte[] createBitmapV5Header(int width, int height) {
        ByteBuffer header = ByteBuffer.allocate(WindowsMemory.BITMAP_V5_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(WindowsMemory.BITMAP_V5_HEADER_SIZE);
        header.putInt(width);
        header.putInt(-height);
        header.putShort((short) 1);
        header.putShort((short) 32);
        header.putInt(WindowsMemory.BI_BITFIELDS);
        header.putInt(width * height * 4);
        header.position(40);
        header.putInt(0x00FF0000);
        header.putInt(0x0000FF00);
        header.putInt(0x000000FF);
        header.putInt(0xFF000000);
        header.putInt(WindowsMemory.LCS_SRGB);
        header.position(108);
        header.putInt(WindowsMemory.LCS_GM_IMAGES);
        return header.array();
    }

    private static byte[] createBitmapInfoHeader(int width, int height) {
        ByteBuffer header = ByteBuffer.allocate(WindowsMemory.BITMAP_INFO_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(WindowsMemory.BITMAP_INFO_HEADER_SIZE);
        header.putInt(width);
        header.putInt(height);
        header.putShort((short) 1);
        header.putShort((short) 32);
        header.putInt(WindowsMemory.BI_RGB);
        header.putInt(width * height * 4);
        return header.array();
    }

    private static int getWindowsPngClipboardFormat() {
        try {
            return User32.INSTANCE.RegisterClipboardFormat("PNG");
        } catch (Throwable ignored) {
            LOGGER.warn("预览局部失败：入口=getWindowsPngClipboardFormat，继续原有回退", ignored);
            return 0;
        }
    }

    private interface WindowsClipboard extends StdCallLibrary {
        WindowsClipboard INSTANCE = Native.load("user32", WindowsClipboard.class, W32APIOptions.DEFAULT_OPTIONS);
        int CF_DIB = 8;
        int CF_DIBV5 = 17;

        boolean OpenClipboard(Pointer owner);

        boolean EmptyClipboard();

        int RegisterClipboardFormat(String formatName);

        Pointer SetClipboardData(int format, Pointer memoryHandle);

        boolean CloseClipboard();
    }

    private interface WindowsMemory extends StdCallLibrary {
        WindowsMemory INSTANCE = Native.load("kernel32", WindowsMemory.class, W32APIOptions.DEFAULT_OPTIONS);
        int GHND = 0x0042;
        int BITMAP_INFO_HEADER_SIZE = 40;
        int BITMAP_V5_HEADER_SIZE = 124;
        int BI_RGB = 0;
        int BI_BITFIELDS = 3;
        int LCS_SRGB = 0x73524742;
        int LCS_GM_IMAGES = 4;

        Pointer GlobalAlloc(int flags, BaseTSD.SIZE_T bytes);

        Pointer GlobalLock(Pointer memoryHandle);

        boolean GlobalUnlock(Pointer memoryHandle);

        Pointer GlobalFree(Pointer memoryHandle);
    }
}
