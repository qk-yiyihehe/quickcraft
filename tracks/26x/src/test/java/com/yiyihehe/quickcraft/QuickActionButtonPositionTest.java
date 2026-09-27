package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuickActionButtonPositionTest {
    private static final String FIRST_BUTTON = "testFirstButton";
    private static final String SECOND_BUTTON = "testSecondButton";

    @AfterEach
    void cleanUp() {
        QuickCraftConfigs.resetActionButtonOffset(FIRST_BUTTON);
        QuickCraftConfigs.resetActionButtonOffset(SECOND_BUTTON);
    }

    @Test
    void storesOffsetsIndependently() {
        QuickCraftConfigs.setActionButtonOffset(FIRST_BUTTON, 12, -8);
        QuickCraftConfigs.setActionButtonOffset(SECOND_BUTTON, -3, 21);

        assertEquals(new QuickCraftConfigs.ButtonOffset(12, -8),
                QuickCraftConfigs.getActionButtonOffset(FIRST_BUTTON));
        assertEquals(new QuickCraftConfigs.ButtonOffset(-3, 21),
                QuickCraftConfigs.getActionButtonOffset(SECOND_BUTTON));
    }

    @Test
    void zeroOffsetAndResetRestoreDefaults() {
        QuickCraftConfigs.setActionButtonOffset(FIRST_BUTTON, 4, 5);
        QuickCraftConfigs.setActionButtonOffset(FIRST_BUTTON, 0, 0);
        assertEquals(new QuickCraftConfigs.ButtonOffset(0, 0),
                QuickCraftConfigs.getActionButtonOffset(FIRST_BUTTON));

        QuickCraftConfigs.setActionButtonOffset(FIRST_BUTTON, 7, 9);
        QuickCraftConfigs.resetActionButtonOffset(FIRST_BUTTON);
        assertEquals(new QuickCraftConfigs.ButtonOffset(0, 0),
                QuickCraftConfigs.getActionButtonOffset(FIRST_BUTTON));
    }
}
