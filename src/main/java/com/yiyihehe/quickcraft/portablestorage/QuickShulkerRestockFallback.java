package com.yiyihehe.quickcraft.portablestorage;

import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortInventory;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStackKey;
import fi.dy.masa.malilib.config.IConfigBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BucketItem;
import net.minecraft.item.EntityBucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PowderSnowBucketItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class QuickShulkerRestockFallback {
    private static final int THRESHOLD = 6;
    private static final int RESTOCK_GRACE = 2;
    // The entity event and the inventory update for a consumed item can arrive separately.
    private static final int SPECIAL_GRACE = 3;
    private static final int CONSUMPTION_TIMEOUT = 20;
    private static final HandState main = new HandState();
    private static final HandState offhand = new HandState();
    private static ClientPlayerEntity owner;
    private static ClientWorld world;
    private static int selected = -1;
    private static long tickNumber;
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
        tickNumber++;
        int currentSlot = selectedSlot(player);
        if (selected != currentSlot) {
            selected = currentSlot;
            main.clear();
        }
        boolean using = usedThisTick || client.options.useKey.isPressed() || player.isUsingItem();
        boolean usedItem = (using || wasUsing) && !client.options.dropKey.isPressed()
                && !client.options.swapHandsKey.isPressed();
        observe(main, player.getMainHandStack(), usedItem);
        observe(offhand, player.getOffHandStack(), usedItem);
        wasUsing = using;
        usedThisTick = false;
        if (player.currentScreenHandler == player.playerScreenHandler && !QuickPortableStorageTransfer.isRunning()) {
            if (!submit(main, player, currentSlot, player.getMainHandStack())) {
                submit(offhand, player, PlayerInventory.OFF_HAND_SLOT, player.getOffHandStack());
            }
        }
        main.previous = player.getMainHandStack().copy();
        offhand.previous = player.getOffHandStack().copy();
    }

    private static void observe(HandState state, ItemStack current, boolean using) {
        ItemStack previous = state.previous;
        if (!state.consumed.isEmpty()) {
            if (tickNumber > state.consumedUntil || (!current.isEmpty()
                    && (!matches(state.consumed, current) || current.getDamage() < state.consumed.getDamage()))) {
                state.consumed = ItemStack.EMPTY;
            } else if (current.isEmpty()) {
                arm(state, state.consumed, current, SPECIAL_GRACE, false);
                state.consumed = ItemStack.EMPTY;
                return;
            }
        }
        if (!using || previous.isEmpty()) {
            return;
        }
        if (isRemainder(previous, current)) {
            arm(state, previous, current, SPECIAL_GRACE, false);
        } else if ((current.isEmpty() && !previous.isOf(Items.TOTEM_OF_UNDYING))
                || (ItemStack.areItemsAndComponentsEqual(current, previous)
                && current.getCount() < previous.getCount() && current.getCount() <= THRESHOLD)) {
            arm(state, previous, current, RESTOCK_GRACE, false);
        }
    }

    private static void arm(HandState state, ItemStack template, ItemStack current, int grace, boolean tweakerooTried) {
        Candidate pending = state.armed;
        boolean continued = pending != null && ItemStack.areItemsAndComponentsEqual(pending.template, template)
                && ItemStack.areItemsAndComponentsEqual(pending.observed, current);
        // A remainder or an emptied slot is a new refill opportunity for the other mods.
        if (!continued) {
            state.waitStarted = -1;
        }
        state.armed = new Candidate(template.copyWithCount(1), current.copy(), grace,
                tweakerooTried || continued && pending.tweakerooTried);
    }

    private static boolean submit(HandState state, ClientPlayerEntity player, int slot, ItemStack current) {
        Candidate candidate = state.armed;
        if (candidate == null) {
            return false;
        }
        if (!QuickPortableStorageTransfer.same(candidate.observed, current)) {
            state.armed = null;
            state.attempt = null;
            return false;
        }
        if (QuickPortableStorageTransfer.easyPlaceHasPriority(MinecraftClient.getInstance())) {
            state.armed = null;
            state.attempt = null;
            return false;
        }
        int ipnDelay = Ipn.refillDelay(slot, candidate.template, current);
        if (ipnDelay < 0 || (!candidate.tweakerooTried
                && ItemStack.areItemsAndComponentsEqual(candidate.template, current) && Tweakeroo.active())) {
            state.waitStarted = -1;
            return false;
        }
        if (state.waitStarted < 0) {
            state.waitStarted = tickNumber;
        }
        if (tickNumber - state.waitStarted < Math.max(candidate.grace, ipnDelay)) {
            return false;
        }
        var source = QuickPortableStorageTransfer.findInventoryRestockSource(player, candidate.template);
        if (source != null) {
            if (QuickPortableStorageTransfer.restockFromInventory(player, slot, candidate.template, current, source)) {
                state.armed = null;
                state.attempt = null;
                return true;
            }
            return false;
        }
        if (!QuickShulkerConnection.canOpen()) {
            return false;
        }
        Attempt attempt = new Attempt(current.getCount(), new QuickCrossBoxSortStackKey(candidate.template),
                shulkerContents(player.getInventory()));
        if (attempt.equals(state.attempt)) {
            return false;
        }
        if (QuickPortableStorageTransfer.restock(candidate.template, slot, current)) {
            state.attempt = attempt;
            return true;
        }
        return false;
    }

    static boolean matches(ItemStack reference, ItemStack replacement) {
        if (ItemStack.areItemsAndComponentsEqual(reference, replacement)) {
            return true;
        }
        if (!replacement.isOf(reference.getItem())) {
            return false;
        }
        ItemStack comparable;
        if (reference.getItem() instanceof EntityBucketItem) {
            // Individually caught mobs carry different data, such as the tadpole's age.
            comparable = replacement.copy();
            comparable.set(DataComponentTypes.BUCKET_ENTITY_DATA, reference.get(DataComponentTypes.BUCKET_ENTITY_DATA));
        } else if (reference.isDamageable() && replacement.isDamageable()) {
            comparable = replacement.copy();
            comparable.setDamage(reference.getDamage());
        } else {
            return false;
        }
        return ItemStack.areItemsAndComponentsEqual(reference, comparable);
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
        ItemStack first = client.player.getMainHandStack();
        if (first.isOf(Items.TOTEM_OF_UNDYING) || main.previous.isOf(Items.TOTEM_OF_UNDYING)) {
            markConsumed(client.player, Hand.MAIN_HAND,
                    first.isOf(Items.TOTEM_OF_UNDYING) ? first : main.previous);
        } else {
            ItemStack second = client.player.getOffHandStack();
            if (second.isOf(Items.TOTEM_OF_UNDYING) || offhand.previous.isOf(Items.TOTEM_OF_UNDYING)) {
                markConsumed(client.player, Hand.OFF_HAND,
                        second.isOf(Items.TOTEM_OF_UNDYING) ? second : offhand.previous);
            }
        }
    }

    public static void itemBroken(MinecraftClient client, Hand hand) {
        if (!enabled() || client.player == null) {
            return;
        }
        bind(client);
        HandState state = hand == Hand.MAIN_HAND ? main : offhand;
        ItemStack current = client.player.getStackInHand(hand);
        ItemStack reference = current.isDamageable() ? current : state.previous;
        if (reference.isDamageable()) {
            markConsumed(client.player, hand, reference);
        }
    }

    private static void markConsumed(ClientPlayerEntity player, Hand hand, ItemStack template) {
        HandState state = hand == Hand.MAIN_HAND ? main : offhand;
        if (state == main) {
            int currentSlot = selectedSlot(player);
            if (selected != currentSlot) {
                selected = currentSlot;
                state.clear();
            }
        }
        state.consumed = template.copy();
        state.consumedUntil = tickNumber + CONSUMPTION_TIMEOUT;
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
        bind(client);
        var state = hand == Hand.MAIN_HAND ? main : offhand;
        if (!QuickPortableStorageTransfer.same(before, current)) {
            state.armed = null;
            state.attempt = null;
            state.consumed = ItemStack.EMPTY;
            return;
        }
        if (!current.isEmpty() && !isRemainder(reference, current)
                && (!ItemStack.areItemsAndComponentsEqual(reference, current) || current.getCount() >= current.getMaxCount())) {
            return;
        }
        if (state == main) {
            int slot = selectedSlot(client.player);
            if (selected != slot) {
                selected = slot;
                state.clear();
            }
        }
        arm(state, reference, current, RESTOCK_GRACE, true);
    }

    static boolean isRemainder(ItemStack reference, ItemStack current) {
        if (current.getCount() != 1) {
            return false;
        }
        if (current.isOf(Items.BUCKET)) {
            return !reference.isOf(Items.BUCKET) && (reference.getItem() instanceof BucketItem
                    || reference.getItem() instanceof PowderSnowBucketItem || reference.isOf(Items.MILK_BUCKET));
        }
        if (current.isOf(Items.GLASS_BOTTLE)) {
            return reference.isOf(Items.POTION) || reference.isOf(Items.HONEY_BOTTLE);
        }
        return current.isOf(Items.BOWL) && (reference.isOf(Items.MUSHROOM_STEW) || reference.isOf(Items.RABBIT_STEW)
                || reference.isOf(Items.BEETROOT_SOUP) || reference.isOf(Items.SUSPICIOUS_STEW));
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
        tickNumber = 0;
        wasUsing = false;
        usedThisTick = false;
    }

    private static final class HandState {
        ItemStack previous = ItemStack.EMPTY;
        Candidate armed;
        Attempt attempt;
        long waitStarted = -1;
        ItemStack consumed = ItemStack.EMPTY;
        long consumedUntil;

        void clear() {
            previous = ItemStack.EMPTY;
            armed = null;
            attempt = null;
            waitStarted = -1;
            consumed = ItemStack.EMPTY;
            consumedUntil = 0;
        }
    }

    private record Candidate(ItemStack template, ItemStack observed, int grace, boolean tweakerooTried) {}
    private record Attempt(int count, QuickCrossBoxSortStackKey template, List<Box> contents) {}
    private record Box(int slot, QuickCrossBoxSortStackKey key) {}

    private static final class Tweakeroo {
        private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("tweakeroo");
        private static final IConfigBoolean HAND_RESTOCK = handRestock();

        static boolean active() {
            return HAND_RESTOCK == null ? LOADED : HAND_RESTOCK.getBooleanValue();
        }

        private static IConfigBoolean handRestock() {
            if (!LOADED) {
                return null;
            }
            try {
                Class<?> type = Class.forName("fi.dy.masa.tweakeroo.config.FeatureToggle");
                return IConfigBoolean.class.cast(type.getField("TWEAK_HAND_RESTOCK").get(null));
            } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
                LoggerFactory.getLogger(QuickShulkerRestockFallback.class)
                        .warn("Could not read the Tweakeroo hand restock setting", failure);
                return null;
            }
        }
    }

    private static final class Ipn {
        private static final int MAX_WAIT_TICKS = 100;
        private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("inventoryprofilesnext");
        private static boolean logged;
        private static final Option ENABLED = option("ModSettings", "getENABLE_AUTO_REFILL", "getBooleanValue");
        private static final Option WAIT = option("AutoRefillSettings", "getAUTO_REFILL_WAIT_TICK", "getIntegerValue");
        private static final Option THRESHOLD = option("AutoRefillSettings", "getSTACKABLE_THRESHOLD", "getIntegerValue");
        private static final Option BLACKLIST = option("AutoRefillSettings", "getAUTOREFILL_BLACKLIST", "getValue");
        private static final Option PER_SLOT = option("AutoRefillSettings", "getAUTO_REFILL_ENABLE_PER_SLOT_CONFIG", "getBooleanValue");
        private static final Set<?> DISABLED_SLOTS = disabledSlots();

        /** A negative delay means IPN's threshold has not yet been reached. */
        static int refillDelay(int slot, ItemStack template, ItemStack current) {
            if (!LOADED) {
                return 0;
            }
            try {
                if (ENABLED != null && Boolean.FALSE.equals(ENABLED.read())) {
                    return 0;
                }
                if (DISABLED_SLOTS.contains(slot) && (PER_SLOT == null || Boolean.TRUE.equals(PER_SLOT.read()))) {
                    return 0;
                }
                if (BLACKLIST != null && isBlacklisted((String) BLACKLIST.read(), template)) {
                    return 0;
                }
                int threshold = THRESHOLD == null ? 0 : (int) THRESHOLD.read();
                if (current.getCount() > threshold && ItemStack.areItemsAndComponentsEqual(template, current)) {
                    return -1;
                }
                int wait = WAIT == null ? MAX_WAIT_TICKS : (int) WAIT.read();
                return Math.clamp(wait, 0, MAX_WAIT_TICKS) + RESTOCK_GRACE;
            } catch (ReflectiveOperationException failure) {
                log(failure);
                return MAX_WAIT_TICKS + RESTOCK_GRACE;
            }
        }

        private static boolean isBlacklisted(String blacklist, ItemStack template) {
            if (blacklist.isEmpty()) {
                return false;
            }
            String id = Registries.ITEM.getId(template.getItem()).toString();
            for (String entry : blacklist.split(",")) {
                if (id.equals(entry.trim())) {
                    return true;
                }
            }
            return false;
        }

        private static Set<?> disabledSlots() {
            if (LOADED) {
                try {
                    Class<?> handler = Class.forName("org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler");
                    return (Set<?>) handler.getMethod("getDisabledSlots").invoke(handler.getField("INSTANCE").get(null));
                } catch (ReflectiveOperationException | LinkageError failure) {
                    log(failure);
                }
            }
            return Set.of();
        }

        private static Option option(String settings, String getterName, String valueName) {
            if (!LOADED) {
                return null;
            }
            try {
                Class<?> type = Class.forName("org.anti_ad.mc.ipnext.config." + settings);
                Method getter = type.getMethod(getterName);
                Object config = getter.invoke(type.getField("INSTANCE").get(null));
                return new Option(config, getter.getReturnType().getMethod(valueName));
            } catch (ReflectiveOperationException | LinkageError failure) {
                log(failure);
                return null;
            }
        }

        private static void log(Throwable failure) {
            if (!logged) {
                logged = true;
                LoggerFactory.getLogger(QuickShulkerRestockFallback.class)
                        .warn("Could not read Inventory Profiles Next auto refill settings", failure);
            }
        }

        private record Option(Object config, Method getter) {
            Object read() throws ReflectiveOperationException {
                return getter.invoke(config);
            }
        }
    }
}
