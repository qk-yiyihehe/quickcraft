package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.platform.NativeImage;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BUG-53：剪贴板原生边界的所有权、清空时点和格式回退。 */
class QuickLitematicaPreviewClipboardTest {
    private static final Pointer OWNER = new Pointer(0x1234L);

    @Test
    void successTransfersBothDibHandlesToClipboard() throws Exception {
        FakeClipboard clipboard = new FakeClipboard();
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            QuickLitematicaPreviewClipboard.writeImage(image, false, clipboard, memory, OWNER);
        }

        assertThat(clipboard.owner).isEqualTo(OWNER);
        assertThat(clipboard.emptyCalls).isEqualTo(1);
        assertThat(clipboard.formats).containsExactly(8, 17);
        assertThat(clipboard.closeCalls).isEqualTo(1);
        assertThat(memory.freed).isEmpty();
    }

    @Test
    void openFailureDoesNotEmptyClipboardAndFreesHandles() {
        FakeClipboard clipboard = new FakeClipboard();
        clipboard.openResult = false;
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            assertThatThrownBy(() -> QuickLitematicaPreviewClipboard.writeImage(image, false, clipboard, memory, OWNER))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(clipboard.emptyCalls).isZero();
        assertThat(clipboard.setCalls).isZero();
        assertThat(clipboard.closeCalls).isZero();
        assertThat(memory.freed).hasSize(2);
    }

    @Test
    void emptyFailureClosesAndFreesHandles() {
        FakeClipboard clipboard = new FakeClipboard();
        clipboard.emptyResult = false;
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            assertThatThrownBy(() -> QuickLitematicaPreviewClipboard.writeImage(image, false, clipboard, memory, OWNER))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(clipboard.emptyCalls).isEqualTo(1);
        assertThat(clipboard.setCalls).isZero();
        assertThat(clipboard.closeCalls).isEqualTo(1);
        assertThat(memory.freed).hasSize(2);
    }

    @Test
    void preparationFailureDoesNotTouchClipboard() {
        FakeClipboard clipboard = new FakeClipboard();
        FakeMemory memory = new FakeMemory();
        memory.failAllocAt = 1;
        try (NativeImage image = new NativeImage(2, 2, false)) {
            assertThatThrownBy(() -> QuickLitematicaPreviewClipboard.writeImage(image, false, clipboard, memory, OWNER))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(clipboard.openCalls).isZero();
        assertThat(clipboard.emptyCalls).isZero();
        assertThat(memory.freed).hasSize(1);
    }

    @Test
    void primaryWriteFailureTriesDibV5AndReportsFailureOnlyWhenBothFail() {
        FakeClipboard clipboard = new FakeClipboard();
        clipboard.failFormats.add(8);
        clipboard.failFormats.add(17);
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            assertThatThrownBy(() -> QuickLitematicaPreviewClipboard.writeImage(image, false, clipboard, memory, OWNER))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(clipboard.formats).containsExactly(8, 17);
        assertThat(clipboard.closeCalls).isEqualTo(1);
        assertThat(memory.freed).hasSize(2);
    }

    @Test
    void nullOwnerIsRejectedBeforeAnyNativeCall() {
        FakeClipboard clipboard = new FakeClipboard();
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            assertThatThrownBy(() -> QuickLitematicaPreviewClipboard.writeImage(image, false, clipboard, memory, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(clipboard.openCalls).isZero();
        assertThat(memory.allocCalls).isZero();
    }

    @Test
    void transparentCopyPublishesPngFirstAndTransfersEveryAcceptedHandle() throws Exception {
        FakeClipboard clipboard = new FakeClipboard();
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            QuickLitematicaPreviewClipboard.writeImage(image, true, clipboard, memory, OWNER);
        }
        assertThat(clipboard.formats).containsExactly(0x5000, 8, 17);
        assertThat(memory.freed).isEmpty();
        assertThat(clipboard.closeCalls).isEqualTo(1);
    }

    @Test
    void successfulPngStillCountsWhenBothDibFormatsFail() throws Exception {
        FakeClipboard clipboard = new FakeClipboard();
        clipboard.failFormats.addAll(Set.of(8, 17));
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            QuickLitematicaPreviewClipboard.writeImage(image, true, clipboard, memory, OWNER);
        }
        assertThat(clipboard.formats).containsExactly(0x5000, 8, 17);
        assertThat(memory.freed).hasSize(2).doesNotContainAnyElementsOf(clipboard.accepted);
        assertThat(clipboard.accepted).hasSize(1);
    }

    @Test
    void failedPngIsFreedWhileDibOwnershipIsTransferred() throws Exception {
        FakeClipboard clipboard = new FakeClipboard();
        clipboard.failFormats.add(0x5000);
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            QuickLitematicaPreviewClipboard.writeImage(image, true, clipboard, memory, OWNER);
        }
        assertThat(memory.freed).hasSize(1).doesNotContainAnyElementsOf(clipboard.accepted);
        assertThat(clipboard.accepted).hasSize(2);
    }

    @Test
    void unavailablePngRegistrationStillPublishesDibFormats() throws Exception {
        FakeClipboard clipboard = new FakeClipboard();
        clipboard.pngFormat = 0;
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            QuickLitematicaPreviewClipboard.writeImage(image, true, clipboard, memory, OWNER);
        }
        assertThat(clipboard.formats).containsExactly(8, 17);
        assertThat(memory.freed).hasSize(1).doesNotContainAnyElementsOf(clipboard.accepted);
        assertThat(clipboard.registerBeforeEmpty).isTrue();
    }

    @Test
    void allTransparentFormatsFailAndEveryUntransferredHandleIsFreed() {
        FakeClipboard clipboard = new FakeClipboard();
        clipboard.failFormats.addAll(Set.of(0x5000, 8, 17));
        FakeMemory memory = new FakeMemory();
        try (NativeImage image = new NativeImage(2, 2, false)) {
            assertThatThrownBy(() -> QuickLitematicaPreviewClipboard.writeImage(image, true, clipboard, memory, OWNER))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(clipboard.formats).containsExactly(0x5000, 8, 17);
        assertThat(memory.freed).hasSize(3);
        assertThat(clipboard.closeCalls).isEqualTo(1);
        assertThat(clipboard.registerBeforeEmpty).isTrue();
    }

    private static final class FakeClipboard implements QuickLitematicaPreviewClipboard.WindowsClipboard {
        private Pointer owner;
        private int pngFormat = 0x5000;
        private boolean registerBeforeEmpty;
        private final Set<Pointer> accepted = new HashSet<>();
        private boolean openResult = true;
        private boolean emptyResult = true;
        private int openCalls;
        private int emptyCalls;
        private int setCalls;
        private int closeCalls;
        private final List<Integer> formats = new ArrayList<>();
        private final Set<Integer> failFormats = new HashSet<>();

        @Override
        public boolean OpenClipboard(Pointer owner) {
            this.owner = owner;
            this.openCalls++;
            return this.openResult;
        }

        @Override
        public boolean EmptyClipboard() {
            this.emptyCalls++;
            return this.emptyResult;
        }

        @Override
        public int RegisterClipboardFormat(String formatName) {
            this.registerBeforeEmpty = this.emptyCalls == 0;
            return this.pngFormat;
        }

        @Override
        public Pointer SetClipboardData(int format, Pointer memoryHandle) {
            this.setCalls++;
            this.formats.add(format);
            if (this.failFormats.contains(format)) return null;
            this.accepted.add(memoryHandle);
            return memoryHandle;
        }

        @Override
        public boolean CloseClipboard() {
            this.closeCalls++;
            return true;
        }
    }

    private static final class FakeMemory implements QuickLitematicaPreviewClipboard.WindowsMemory {
        private int allocCalls;
        private int failAllocAt;
        private final Set<Pointer> freed = new HashSet<>();

        @Override
        public Pointer GlobalAlloc(int flags, BaseTSD.SIZE_T bytes) {
            this.allocCalls++;
            return this.allocCalls == this.failAllocAt ? null : new Memory(bytes.longValue());
        }

        @Override
        public Pointer GlobalLock(Pointer memoryHandle) {
            return memoryHandle;
        }

        @Override
        public boolean GlobalUnlock(Pointer memoryHandle) {
            return true;
        }

        @Override
        public Pointer GlobalFree(Pointer memoryHandle) {
            this.freed.add(memoryHandle);
            return memoryHandle;
        }
    }
}
