package com.yiyihehe.quickcraft.portablestorage;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortInventory;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStackKey;
import fi.dy.masa.malilib.config.IConfigBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.SolidBucketItem;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class QuickShulkerRestockFallback {
    private static final int THRESHOLD = 6;
    private static final int RESTOCK_GRACE = 2;
    // 消耗物品触发的实体事件和物品栏更新可能分开到达。
    private static final int SPECIAL_GRACE = 3;
    private static final int CONSUMPTION_TIMEOUT = 20;
    private static final HandState main = new HandState();
    private static final HandState offhand = new HandState();
    private static LocalPlayer owner;
    private static ClientLevel world;
    private static int selected = -1;
    private static long tickNumber;
    private static boolean wasUsing;
    private static boolean usedThisTick;

    private QuickShulkerRestockFallback() {}

    public static boolean enabled() {
        return QuickCraftConfigs.ContainerTools.ENABLE_AUTO_SHULKER_RESTOCK.getBooleanValue();
    }

    static int selectedSlot(LocalPlayer player) {
        return player.getInventory().getSelectedSlot();
    }

    public static void itemUseStarted(Minecraft client) {
        if (enabled() && client.player != null) {
            bind(client);
            usedThisTick = true;
        }
    }

    static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (!enabled() || player == null || client.level == null || player.isCreative() || player.isSpectator()
                || !player.isAlive() || QuickClientScreenAccess.currentScreen(client) != null) {
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
        boolean using = usedThisTick || client.options.keyUse.isDown() || player.isUsingItem();
        boolean usedItem = (using || wasUsing) && !client.options.keyDrop.isDown() && !client.options.keySwapOffhand
                .isDown();
        observe(main, player.getMainHandItem(), usedItem);
        observe(offhand, player.getOffhandItem(), usedItem);
        wasUsing = using;
        usedThisTick = false;
        if (player.containerMenu == player.inventoryMenu && !QuickPortableStorageTransfer.isRunning()) {
            if (!submit(main, player, currentSlot, player.getMainHandItem())) {
                submit(offhand, player, Inventory.SLOT_OFFHAND, player.getOffhandItem());
            }
        }
        main.previous = player.getMainHandItem().copy();
        offhand.previous = player.getOffhandItem().copy();
    }

    private static void observe(HandState state, ItemStack current, boolean using) {
        ItemStack previous = state.previous;
        if (!state.consumed.isEmpty()) {
            if (tickNumber > state.consumedUntil || (!current.isEmpty() && (!matches(state.consumed, current) || current
                    .getDamageValue() < state.consumed.getDamageValue()))) {
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
        } else if ((current.isEmpty() && !previous.is(Items.TOTEM_OF_UNDYING)) || (ItemStack.isSameItemSameComponents(
                current,
                previous
        ) && current.getCount() < previous.getCount() && current.getCount() <= THRESHOLD)) {
            arm(state, previous, current, RESTOCK_GRACE, false);
        }
    }

    private static void arm(HandState state, ItemStack template, ItemStack current, int grace, boolean tweakerooTried) {
        Candidate pending = state.armed;
        boolean continued = pending != null && ItemStack.isSameItemSameComponents(pending.template, template)
                && ItemStack.isSameItemSameComponents(pending.observed, current);
        // 出现余留物或槽位被清空时，其他模组又有机会补货。
        if (!continued) {
            state.waitStarted = -1;
        }
        state.armed = new Candidate(
                template.copyWithCount(1),
                current.copy(),
                grace,
                tweakerooTried || continued && pending.tweakerooTried
        );
    }

    private static boolean submit(HandState state, LocalPlayer player, int slot, ItemStack current) {
        Candidate candidate = state.armed;
        if (candidate == null) {
            return false;
        }
        if (!QuickPortableStorageTransfer.same(candidate.observed, current)) {
            state.armed = null;
            state.attempt = null;
            return false;
        }
        if (QuickPortableStorageTransfer.easyPlaceHasPriority(Minecraft.getInstance())) {
            state.armed = null;
            state.attempt = null;
            return false;
        }
        int ipnDelay = Ipn.refillDelay(slot, candidate.template, current);
        if (ipnDelay < 0 || (!candidate.tweakerooTried && ItemStack.isSameItemSameComponents(
                candidate.template,
                current
        ) && Tweakeroo.active())) {
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
        Attempt attempt = new Attempt(
                current.getCount(),
                new QuickCrossBoxSortStackKey(candidate.template),
                shulkerContents(player.getInventory())
        );
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
        if (ItemStack.isSameItemSameComponents(reference, replacement)) {
            return true;
        }
        if (!replacement.is(reference.getItem())) {
            return false;
        }
        ItemStack comparable;
        if (reference.getItem() instanceof MobBucketItem) {
            // 分别捕获的生物可能携带不同数据，例如蝌蚪的年龄。
            comparable = replacement.copy();
            comparable.set(DataComponents.BUCKET_ENTITY_DATA, reference.get(DataComponents.BUCKET_ENTITY_DATA));
        } else if (reference.isDamageableItem() && replacement.isDamageableItem()) {
            comparable = replacement.copy();
            comparable.setDamageValue(reference.getDamageValue());
        } else {
            return false;
        }
        return ItemStack.isSameItemSameComponents(reference, comparable);
    }

    private static List<Box> shulkerContents(Inventory inventory) {
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (QuickCrossBoxSortInventory.isVanillaShulkerBox(stack)) {
                boxes.add(new Box(i, new QuickCrossBoxSortStackKey(stack)));
            }
        }
        return boxes;
    }

    public static void totemUsed(Minecraft client) {
        if (!enabled() || client.player == null) {
            return;
        }
        bind(client);
        ItemStack first = client.player.getMainHandItem();
        if (first.is(Items.TOTEM_OF_UNDYING) || main.previous.is(Items.TOTEM_OF_UNDYING)) {
            markConsumed(
                    client.player,
                    InteractionHand.MAIN_HAND,
                    first.is(Items.TOTEM_OF_UNDYING) ? first : main.previous
            );
        } else {
            ItemStack second = client.player.getOffhandItem();
            if (second.is(Items.TOTEM_OF_UNDYING) || offhand.previous.is(Items.TOTEM_OF_UNDYING)) {
                markConsumed(
                        client.player,
                        InteractionHand.OFF_HAND,
                        second.is(Items.TOTEM_OF_UNDYING) ? second : offhand.previous
                );
            }
        }
    }

    public static void itemBroken(Minecraft client, InteractionHand hand) {
        if (!enabled() || client.player == null) {
            return;
        }
        bind(client);
        HandState state = hand == InteractionHand.MAIN_HAND ? main : offhand;
        ItemStack current = client.player.getItemInHand(hand);
        ItemStack reference = current.isDamageableItem() ? current : state.previous;
        if (reference.isDamageableItem()) {
            markConsumed(client.player, hand, reference);
        }
    }

    private static void markConsumed(LocalPlayer player, InteractionHand hand, ItemStack template) {
        HandState state = hand == InteractionHand.MAIN_HAND ? main : offhand;
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

    /** 仅在 Tweakeroo 到达满足条件的补货流程且该手物品仍未被改变时调用。 */
    public static void afterTweakeroo(Player player, InteractionHand hand, ItemStack reference, ItemStack before) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled()
                || player != client.player
                || reference.isEmpty()
                || QuickClientScreenAccess.currentScreen(client) != null
                || hand != InteractionHand.MAIN_HAND && hand != InteractionHand.OFF_HAND) {
            return;
        }
        ItemStack current = player.getItemInHand(hand);
        bind(client);
        var state = hand == InteractionHand.MAIN_HAND ? main : offhand;
        if (!QuickPortableStorageTransfer.same(before, current)) {
            state.armed = null;
            state.attempt = null;
            state.consumed = ItemStack.EMPTY;
            return;
        }
        if (!current.isEmpty() && !isRemainder(reference, current) && (!ItemStack.isSameItemSameComponents(
                reference,
                current
        ) || current.getCount() >= current.getMaxStackSize())) {
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
        if (current.is(Items.BUCKET)) {
            return !reference.is(Items.BUCKET) && (reference.getItem() instanceof BucketItem || reference
                    .getItem() instanceof SolidBucketItem || reference.is(Items.MILK_BUCKET));
        }
        if (current.is(Items.GLASS_BOTTLE)) {
            return reference.is(Items.POTION) || reference.is(Items.HONEY_BOTTLE);
        }
        return current.is(Items.BOWL) && (reference.is(Items.MUSHROOM_STEW) || reference.is(Items.RABBIT_STEW)
                || reference.is(Items.BEETROOT_SOUP) || reference.is(Items.SUSPICIOUS_STEW));
    }

    private static void bind(Minecraft client) {
        if (owner != client.player || world != client.level) {
            reset();
            owner = client.player;
            world = client.level;
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

    private record Candidate(ItemStack template, ItemStack observed, int grace, boolean tweakerooTried) {
    }

    private record Attempt(int count, QuickCrossBoxSortStackKey template, List<Box> contents) {
    }

    private record Box(int slot, QuickCrossBoxSortStackKey key) {
    }

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
                LoggerFactory.getLogger(QuickShulkerRestockFallback.class).warn(
                        "Could not read the Tweakeroo hand restock setting",
                        failure
                );
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
        private static final Option THRESHOLD = option(
                "AutoRefillSettings",
                "getSTACKABLE_THRESHOLD",
                "getIntegerValue"
        );
        private static final Option BLACKLIST = option("AutoRefillSettings", "getAUTOREFILL_BLACKLIST", "getValue");
        private static final Option PER_SLOT = option(
                "AutoRefillSettings",
                "getAUTO_REFILL_ENABLE_PER_SLOT_CONFIG",
                "getBooleanValue"
        );
        private static final Set<?> DISABLED_SLOTS = disabledSlots();

        /** 延迟为负数表示还没达到 IPN 的补货阈值。 */
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
                if (current.getCount() > threshold && ItemStack.isSameItemSameComponents(template, current)) {
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
            String id = BuiltInRegistries.ITEM.getKey(template.getItem()).toString();
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
                    return (Set<?>) handler.getMethod("getDisabledSlots").invoke(
                            handler.getField("INSTANCE").get(null)
                    );
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
                LoggerFactory.getLogger(QuickShulkerRestockFallback.class).warn(
                        "Could not read Inventory Profiles Next auto refill settings",
                        failure
                );
            }
        }

        private record Option(Object config, Method getter) {
            Object read() throws ReflectiveOperationException {
                return getter.invoke(config);
            }
        }
    }
}
