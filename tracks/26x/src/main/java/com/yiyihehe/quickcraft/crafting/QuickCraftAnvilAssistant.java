package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import java.util.*;

/** 每批搬运必须清空光标，再用统计响应确认服务端处理完成，不能把半次搬运留给下一帧。 */
public final class QuickCraftAnvilAssistant {
    private static AnvilMenu handler;
    private static ClientPacketListener connection;
    private static QuickCraftAnvilPlanner planner;
    private static QuickCraftAnvilSearch.Result<QuickCraftAnvilPlanner.State> plan;
    private static List<Component> targets = List.of();
    private static List<Component> skipped = List.of();
    private static boolean pickaxe;
    private static List<ItemStack> expected;
    private static ItemStack expectedCursor = ItemStack.EMPTY;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("QuickCraft/Anvil");
    private static Button actionButton;
    private static int stepIndex;
    private static int equipmentSlot;
    private static boolean running;
    private static boolean executing;
    private static boolean waiting;
    private static boolean autoStart;
    private static boolean lastHotkey;
    private static long sentAt;
    private static Component status = message("idle");

    private QuickCraftAnvilAssistant() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickCraftAnvilAssistant::tick);
    }

    public static boolean ownsAnvil() { return planner != null || plan != null || executing; }

    public static boolean consumesHotkey(int key) {
        Minecraft client = Minecraft.getInstance();
        return QuickCraftConfigs.Crafting.ENABLE_ANVIL_ASSISTANT.getBooleanValue()
                && com.yiyihehe.quickcraft.QuickClientScreenAccess.currentScreen(client) instanceof AnvilScreen
                && ((key >= 0 && QuickCraftConfigs.Hotkeys.ANVIL_BUILD.getKeybind().matches(key))
                || QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.ANVIL_BUILD.getKeybind()));
    }

    public static void attach(Button action) {
        reset();
        actionButton = action;
        refreshButtons();
    }

    public static void action(boolean hotkey) {
        Minecraft client = Minecraft.getInstance();
        if (!valid(client)) return;
        if (running || planner != null) { stop("stopped"); return; }
        if (plan != null) {
            for (int slot = 0; slot <= 2; slot++) {
                if (QuickContainerLock.isLockedSlot(handler, slot)) { stop("locked"); return; }
            }
            if (!matchesSnapshot()) { stop("changed"); return; }
            int remainingCost = plan.state().steps().subList(stepIndex, plan.state().steps().size()).stream()
                    .mapToInt(QuickCraftAnvilPlanner.Step::cost).sum();
            if (remainingCost > client.player.experienceLevel && !client.player.hasInfiniteMaterials()) {
                status = message("experience", remainingCost);
                refreshButtons();
                return;
            }
            running = true;
            if (!executing) {
                executing = true;
                equipmentSlot = plan.state().equipmentSlot();
                stepIndex = 0;
            }
            return;
        }
        handler = (AnvilMenu) client.player.containerMenu;
        connection = client.getConnection();
        if (!handler.getCarried().isEmpty() || !handler.getSlot(1).getItem().isEmpty()
                || QuickCraftAnvilRename.isRapidRenameActive()) { stop("inputs"); return; }
        for (int slot = 0; slot <= 2; slot++) {
            if (QuickContainerLock.isLockedSlot(handler, slot)) { stop("locked"); return; }
        }
        try {
            planner = new QuickCraftAnvilPlanner(client, handler);
            targets = planner.targetDescription();
            pickaxe = planner.isPickaxe();
            autoStart = hotkey;
            snapshot();
            status = message("planning");
        } catch (IllegalArgumentException invalid) {
            stop(invalid.getMessage());
        }
        refreshButtons();
    }

    private static void tick(Minecraft client) {
        if (!valid(client)) {
            if (handler != null || ownsAnvil() || actionButton != null) reset();
            lastHotkey = false;
            return;
        }
        boolean down = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.ANVIL_BUILD.getKeybind());
        if (down && !lastHotkey) action(true);
        lastHotkey = down;
        if (handler != null && (client.player.containerMenu != handler || client.getConnection() != connection)) {
            stop("changed");
        }
        if (planner != null) {
            if (!matchesSnapshot()) { stop("changed"); return; }
            try {
                if (planner.advance()) {
                    plan = planner.result();
                    var missing = planner.missingDescription();
                    planner = null;
                    if (plan == null) {
                        stop("no_route");
                        targets = missing.stream().map(item -> message("missing", item)).toList();
                        refreshButtons();
                    }
                    else {
                        skipped = missing;
                        status = message(skipped.isEmpty() ? "ready" : "ready_partial", plan.state().steps().size(), plan.cost());
                        refreshButtons();
                        if (autoStart) action(true);
                    }
                }
            } catch (IllegalStateException tooLarge) { stop("too_large"); }
        }
        if (waiting && System.nanoTime() - sentAt > 10_000_000_000L) { stop("timeout"); return; }
        if (running && !waiting) advance(client);
        refreshButtons();
    }

    private static void advance(Minecraft client) {
        if (!matchesSnapshot()) { stop("changed"); return; }
        if (stepIndex >= plan.state().steps().size()) {
            var missing = skipped;
            stop(missing.isEmpty() ? "complete" : "partial_complete");
            targets = missing.stream().map(item -> message("missing", item)).toList();
            refreshButtons();
            return;
        }
        var step = plan.state().steps().get(stepIndex);
        if (equipmentSlot != 0) {
            if (!same(handler.getSlot(equipmentSlot).getItem(), step.input())) { stop("changed"); return; }
            boolean moved = handler.getSlot(0).getItem().isEmpty()
                    ? transfer(client, equipmentSlot, 0)
                    : transfer(client, equipmentSlot, 0, equipmentSlot);
            if (moved) equipmentSlot = 0;
            return;
        }
        if (!same(handler.getSlot(0).getItem(), step.input())) { stop("changed"); return; }
        if (handler.getSlot(1).getItem().isEmpty()) {
            if (!same(handler.getSlot(step.bookSlot()).getItem(), step.book())) { stop("changed"); return; }
            if (client.player.experienceLevel < step.cost() && !client.player.hasInfiniteMaterials()) {
                running = false;
                status = message("experience", step.cost());
                return;
            }
            String name = step.input().getHoverName().getString();
            if (handler.setItemName(name)) connection.send(new ServerboundRenameItemPacket(name));
            transfer(client, step.bookSlot(), 1);
            return;
        }
        // 结果槽会被服务端和命名框多次刷新，只在实际领取前校验完整结果及费用。
        if (!same(handler.getSlot(1).getItem(), step.book())
                || !same(handler.getSlot(2).getItem(), step.output()) || handler.getCost() != step.cost()
                || !handler.getSlot(2).mayPickup(client.player)) {
            LOGGER.warn("铁砧结果不符：步骤={}，预计费用={}，实际费用={}，预计产物={}，实际产物={}",
                    stepIndex + 1, step.cost(), handler.getCost(), step.output(), handler.getSlot(2).getItem());
            stop("changed");
            return;
        }
        if (!handler.getSlot(step.bookSlot()).getItem().isEmpty()) { stop("full"); return; }
        if (transfer(client, 2, step.bookSlot())) {
            equipmentSlot = step.bookSlot();
            stepIndex++;
            status = message("working", stepIndex, plan.state().steps().size());
        }
    }

    private static boolean transfer(Minecraft client, int... slots) {
        if (!handler.getCarried().isEmpty()) { stop("changed"); return false; }
        for (int slot : slots) {
            if (QuickContainerLock.isLockedSlot(handler, slot)) { stop("locked"); return false; }
        }
        for (int slot : slots) {
            ItemStack beforeSlot = handler.getSlot(slot).getItem().copy();
            ItemStack beforeCursor = handler.getCarried().copy();
            rawClick(client, slot);
            if (same(beforeSlot, handler.getSlot(slot).getItem()) && same(beforeCursor, handler.getCarried())) {
                recoverCursor(client, slots[slots.length - 1]);
                stop("changed");
                return false;
            }
        }
        if (!handler.getCarried().isEmpty()) {
            recoverCursor(client, slots[slots.length - 1]);
            stop("changed");
            return false;
        }
        snapshot();
        waiting = true;
        sentAt = System.nanoTime();
        connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
        return true;
    }

    private static void rawClick(Minecraft client, int slot) {
        client.gameMode.handleContainerInput(handler.containerId, slot, 0, ContainerInput.PICKUP, client.player);
    }

    private static void recoverCursor(Minecraft client, int preferredSlot) {
        if (handler.getCarried().isEmpty()) return;
        if (preferredSlot >= 3 && !QuickContainerLock.isLockedSlot(handler, preferredSlot)
                && handler.getSlot(preferredSlot).getItem().isEmpty()
                && handler.getSlot(preferredSlot).mayPlace(handler.getCarried())) {
            rawClick(client, preferredSlot);
        }
        for (int slot = 3; slot < handler.slots.size() && !handler.getCarried().isEmpty(); slot++) {
            if (!QuickContainerLock.isLockedSlot(handler, slot) && handler.getSlot(slot).getItem().isEmpty()
                    && handler.getSlot(slot).mayPlace(handler.getCarried())) rawClick(client, slot);
        }
    }

    public static void onStatistics(ClientPacketListener source) {
        if (!waiting || source != connection) return;
        waiting = false;
        if (!matchesSnapshot()) stop("changed");
    }

    private static void snapshot() {
        expected = handler.slots.stream().map(slot -> slot.getItem().copy()).toList();
        expectedCursor = handler.getCarried().copy();
    }

    private static boolean matchesSnapshot() {
        if (handler == null || expected == null || handler.slots.size() != expected.size()) return false;
        if (!same(handler.getCarried(), expectedCursor)) {
            LOGGER.warn("铁砧光标状态不符：步骤={}，预计={}，实际={}", stepIndex + 1, expectedCursor, handler.getCarried());
            return false;
        }
        for (int slot = 0; slot < expected.size(); slot++) {
            if (slot == 2) continue;
            if (!same(handler.getSlot(slot).getItem(), expected.get(slot))) {
                LOGGER.warn("铁砧材料状态不符：步骤={}，槽位={}，预计={}，实际={}",
                        stepIndex + 1, slot, expected.get(slot), handler.getSlot(slot).getItem());
                return false;
            }
        }
        return true;
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return first.getCount() == second.getCount() && ItemStack.isSameItemSameComponents(first, second);
    }

    private static boolean valid(Minecraft client) {
        return QuickCraftConfigs.Crafting.ENABLE_ANVIL_ASSISTANT.getBooleanValue()
                && client.player != null && client.level != null && client.gameMode != null
                && com.yiyihehe.quickcraft.QuickClientScreenAccess.currentScreen(client) instanceof AnvilScreen
                && client.player.containerMenu instanceof AnvilMenu;
    }

    private static void stop(String reason) {
        var client = Minecraft.getInstance();
        if (executing && handler != null && client.player != null && client.player.containerMenu == handler
                && plan != null && !handler.getCarried().isEmpty()) {
            ItemStack carried = handler.getCarried();
            boolean taskItem = plan.state().steps().stream().anyMatch(step -> same(carried, step.input())
                    || same(carried, step.book()) || same(carried, step.output()));
            if (taskItem) recoverCursor(client, equipmentSlot);
        }
        planner = null;
        plan = null;
        stepIndex = 0;
        targets = List.of();
        skipped = List.of();
        pickaxe = false;
        running = executing = waiting = autoStart = false;
        expected = null;
        expectedCursor = ItemStack.EMPTY;
        handler = null;
        connection = null;
        status = message(reason);
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendOverlayMessage(status);
        refreshButtons();
    }

    private static void reset() {
        if (actionButton != null) actionButton.visible = false;
        planner = null;
        plan = null;
        handler = null;
        connection = null;
        expected = null;
        expectedCursor = ItemStack.EMPTY;
        running = executing = waiting = autoStart = false;
        status = message("idle");
        targets = List.of();
        skipped = List.of();
        pickaxe = false;
        stepIndex = 0;
        actionButton = null;
    }

    private static void refreshButtons() {
        if (actionButton == null) return;
        actionButton.visible = QuickCraftConfigs.Crafting.ENABLE_ANVIL_ASSISTANT.getBooleanValue();
        actionButton.setMessage(message(running || planner != null ? "stop_button" : plan != null ? "start_button" : "preview_button"));
        List<Component> lines = new ArrayList<>();
        lines.add(status);
        if (pickaxe) lines.add(message("selected_plan", QuickCraftConfigs.Crafting.ANVIL_PICKAXE_PLAN.getOptionListValue().getDisplayName()));
        if (plan != null) {
            Minecraft client = Minecraft.getInstance();
            lines.add(message("source", message(plan.state().equipmentSlot() == 0 ? "left_slot" : "inventory_slot",
                    plan.state().equipmentSlot() - 2), plan.state().equipment().getHoverName()));
            int level = client.player.experienceLevel;
            int remainingCost = plan.state().steps().subList(stepIndex, plan.state().steps().size()).stream()
                    .mapToInt(QuickCraftAnvilPlanner.Step::cost).sum();
            if (remainingCost <= level) {
                double spent = QuickCraftAnvilSearch.experienceAt(level, client.player.experienceProgress)
                        - QuickCraftAnvilSearch.experienceAt(level - remainingCost, client.player.experienceProgress);
                lines.add(message("points", (int) Math.ceil(spent), level - remainingCost));
            }
            for (int index = 0; index < plan.state().steps().size(); index++) {
                var step = plan.state().steps().get(index);
                var description = Component.empty();
                for (var enchantment : QuickCraftAnvilPlanner.bookDescription(step.book())) {
                    if (!description.getString().isEmpty()) description.append(", ");
                    description.append(enchantment);
                }
                lines.add(message("step", index + 1, description, step.cost()));
            }
        }
        lines.addAll(1, targets);
        skipped.forEach(item -> lines.add(message("missing", item)));
        var tooltip = Component.empty();
        for (Component line : lines) {
            if (!tooltip.getString().isEmpty()) tooltip.append("\n");
            tooltip.append(line);
        }
        actionButton.setTooltip(Tooltip.create(tooltip));

    }

    private static Component message(String key, Object... args) {
        return Component.translatable("quickcraft.anvil." + key, args);
    }
}
