package com.yiyihehe.quickcraft.portablestorage;

import com.yiyihehe.quickcraft.QuickSlotTransfer;
import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortInventory;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStackKey;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class QuickShulkerRestockFallback {
    private static final int THRESHOLD = 6;
    // Allow Tweakeroo and a totem's slot update to settle before choosing the consumed hand.
    private static final int SPECIAL_GRACE = 3;
    private static final int TOTEM_TIMEOUT = 20;
    private static final HandState main = new HandState();
    private static final HandState offhand = new HandState();
    private static ClientPlayerEntity owner;
    private static ClientWorld world;
    private static int selected = -1;
    private static int totemTicks;
    private static boolean wasUsing;
    private static boolean usedThisTick;

    private QuickShulkerRestockFallback() {}

    public static boolean enabled() {
        return QuickCraftConfigs.ContainerTools.ENABLE_AUTO_SHULKER_RESTOCK.getBooleanValue();
    }

    static int selectedSlot(ClientPlayerEntity player) {
        return QuickPortableStorageUiAccess.selectedSlot(player.getInventory());
    }

    public static void itemUseStarted(MinecraftClient client) {
        if (enabled() && client.player != null) {
            bind(client);
            usedThisTick = true;
        }
    }

    static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (!enabled() || player == null || client.world == null
                || player.isCreative() || player.isSpectator() || !player.isAlive()
                || client.currentScreen != null) {
            reset();
            return;
        }
        bind(client);
        int currentSlot = selectedSlot(player);
        if (selected != currentSlot) {
            selected = currentSlot;
            main.clear();
        }
        boolean using = usedThisTick || client.options.useKey.isPressed() || player.isUsingItem();
        observe(main, player.getMainHandStack(), currentSlot, using || wasUsing);
        observe(offhand, player.getOffHandStack(), PlayerInventory.OFF_HAND_SLOT, using || wasUsing);
        wasUsing = using;
        usedThisTick = false;
        if (totemTicks > 0 && --totemTicks == 0) {
            main.totem = false;
            offhand.totem = false;
        }
        if (player.currentScreenHandler == player.playerScreenHandler && !QuickPortableStorageTransfer.isRunning()
                && QuickShulkerConnection.canOpen()) {
            if (!submit(main, player, currentSlot, player.getMainHandStack())) {
                submit(offhand, player, PlayerInventory.OFF_HAND_SLOT, player.getOffHandStack());
            }
        }
        main.previous = player.getMainHandStack().copy();
        offhand.previous = player.getOffHandStack().copy();
    }

    private static void observe(HandState state, ItemStack current, int slot, boolean using) {
        ItemStack previous = state.previous;
        if (state.totem && current.isEmpty()) {
            state.armed = new Candidate(new ItemStack(Items.TOTEM_OF_UNDYING), slot, SPECIAL_GRACE, 0, false);
            state.totem = false;
        } else if (slot != PlayerInventory.OFF_HAND_SLOT && using && previous.isOf(Items.WATER_BUCKET) && current.isOf(Items.BUCKET)) {
            state.armed = new Candidate(previous, slot, SPECIAL_GRACE, current.getCount(), false);
        } else if (using && !previous.isEmpty() && previous.getMaxCount() > THRESHOLD
                && current.getCount() < previous.getCount() && current.getCount() <= THRESHOLD
                && (current.isEmpty() || ItemStack.areItemsAndComponentsEqual(current, previous))) {
            if (state.armed == null || !ItemStack.areItemsAndComponentsEqual(state.armed.template, previous)) {
                state.armed = new Candidate(previous, slot, 1, current.getCount(), false);
            }
        }
    }

    private static boolean submit(HandState state, ClientPlayerEntity player, int slot, ItemStack current) {
        Candidate candidate = state.armed;
        if (candidate == null) {
            return false;
        }
        if (candidate.slot != slot || current.getCount() > candidate.count
                || !canSupplement(candidate.template, current, slot)) {
            state.armed = null;
            state.attempt = null;
            return false;
        }
        if (QuickPortableStorageTransfer.easyPlaceHasPriority(MinecraftClient.getInstance())) {
            state.armed = null;
            state.attempt = null;
            return false;
        }
        if (!canTakeOver(player, candidate.template, candidate.tweakerooMiss)) {
            return false;
        }
        if (candidate.delay > 0) {
            state.armed = new Candidate(candidate.template, candidate.slot, candidate.delay - 1, candidate.count,
                    candidate.tweakerooMiss);
            return false;
        }
        Attempt attempt = new Attempt(current.getCount(), new QuickCrossBoxSortStackKey(candidate.template),
                shulkerContents(player.getInventory()));
        if (attempt.equals(state.attempt)) {
            return false;
        }
        if (QuickPortableStorageTransfer.restock(candidate.template, slot, current, candidate.tweakerooMiss)) {
            state.attempt = attempt;
            return true;
        }
        return false;
    }

    static boolean canTakeOver(ClientPlayerEntity player, ItemStack reference, boolean tweakerooMiss) {
        if (tweakerooMiss || !Tweakeroo.active()) {
            return true;
        }
        int selected = selectedSlot(player);
        for (var slot : player.playerScreenHandler.slots) {
            if (QuickSlotTransfer.isPlayerStorageSlot(slot) && slot.getIndex() != selected
                    && slot.hasStack() && !QuickPortableStorageTransfer.locked(player.playerScreenHandler, slot)
                    && InventoryUtils.areStacksEqualIgnoreDurability(slot.getStack(), reference)) {
                return false;
            }
        }
        return true;
    }

    private static List<Box> shulkerContents(PlayerInventory inventory) {
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (QuickCrossBoxSortInventory.isVanillaShulkerBox(stack)) {
                boxes.add(new Box(i, new QuickCrossBoxSortStackKey(stack)));
            }
        }
        return boxes;
    }

    public static void totemUsed(MinecraftClient client) {
        if (!enabled() || client.player == null) {
            return;
        }
        bind(client);
        if (client.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || main.previous.isOf(Items.TOTEM_OF_UNDYING)) {
            main.totem = true;
        } else if (client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || offhand.previous.isOf(Items.TOTEM_OF_UNDYING)) {
            offhand.totem = true;
        }
        totemTicks = TOTEM_TIMEOUT;
    }

    /** Called only after Tweakeroo reaches a qualified restock and leaves that hand untouched. */
    public static void afterTweakeroo(PlayerEntity player, Hand hand, ItemStack reference, ItemStack before) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled() || player != client.player || reference.isEmpty()
                || client.currentScreen != null
                || hand != Hand.MAIN_HAND && hand != Hand.OFF_HAND) {
            return;
        }
        ItemStack current = player.getStackInHand(hand);
        int slot = hand == Hand.MAIN_HAND ? selectedSlot(client.player) : PlayerInventory.OFF_HAND_SLOT;
        bind(client);
        var state = hand == Hand.MAIN_HAND ? main : offhand;
        if (!QuickPortableStorageTransfer.same(before, current)) {
            state.armed = null;
            state.attempt = null;
            return;
        }
        if (!canSupplement(reference, current, slot)) {
            return;
        }
        if (state == main && selected != slot) {
            selected = slot;
            state.clear();
        }
        Candidate pending = state.armed;
        int delay = pending != null && ItemStack.areItemsAndComponentsEqual(pending.template, reference)
                ? pending.delay : 1;
        state.armed = new Candidate(reference.copyWithCount(1), slot, delay, current.getCount(), true);
    }

    private static boolean canSupplement(ItemStack reference, ItemStack current, int slot) {
        return current.isEmpty()
                || ItemStack.areItemsAndComponentsEqual(reference, current) && current.getCount() < current.getMaxCount()
                || slot != PlayerInventory.OFF_HAND_SLOT && current.isOf(Items.BUCKET) && reference.isOf(Items.WATER_BUCKET);
    }

    private static void bind(MinecraftClient client) {
        if (owner != client.player || world != client.world) {
            reset();
            owner = client.player;
            world = client.world;
        }
    }

    private static void reset() {
        owner = null;
        world = null;
        selected = -1;
        main.clear();
        offhand.clear();
        totemTicks = 0;
        wasUsing = false;
        usedThisTick = false;
    }

    private static final class HandState {
        ItemStack previous = ItemStack.EMPTY;
        Candidate armed;
        Attempt attempt;
        boolean totem;

        void clear() {
            previous = ItemStack.EMPTY;
            armed = null;
            attempt = null;
            totem = false;
        }
    }

    private record Candidate(ItemStack template, int slot, int delay, int count, boolean tweakerooMiss) {}
    private record Attempt(int count, QuickCrossBoxSortStackKey template, List<Box> contents) {}
    private record Box(int slot, QuickCrossBoxSortStackKey key) {}

    private static final class Tweakeroo {
        private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("tweakeroo");
        private static final Method GET_ENABLED;
        private static final Object HAND_RESTOCK;
        private static boolean logged;

        static {
            Method getter = null;
            Object toggle = null;
            if (LOADED) {
                try {
                    Class<?> type = Class.forName("fi.dy.masa.tweakeroo.config.FeatureToggle");
                    getter = type.getMethod("getBooleanValue");
                    toggle = type.getField("TWEAK_HAND_RESTOCK").get(null);
                } catch (ReflectiveOperationException | LinkageError failure) {
                    log(failure);
                }
            }
            GET_ENABLED = getter;
            HAND_RESTOCK = toggle;
        }

        static boolean active() {
            if (GET_ENABLED == null || HAND_RESTOCK == null) {
                return LOADED;
            }
            try {
                return (boolean) GET_ENABLED.invoke(HAND_RESTOCK);
            } catch (ReflectiveOperationException failure) {
                log(failure);
                return true;
            }
        }

        private static void log(Throwable failure) {
            if (!logged) {
                logged = true;
                LoggerFactory.getLogger(QuickShulkerRestockFallback.class)
                        .warn("Could not read the Tweakeroo hand restock setting", failure);
            }
        }
    }
}
