package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.component.DataComponentTypes;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import com.yiyihehe.quickcraft.gui.QuickCraftAnvilButton;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.network.packet.c2s.play.RenameItemC2SPacket;
import net.minecraft.item.Items;
import java.util.*;

/** 每批搬运必须清空光标，再用统计响应确认服务端处理完成，不能把半次搬运留给下一帧。 */
public final class QuickCraftAnvilAssistant {
    private static AnvilScreenHandler handler;
    private static ClientPlayNetworkHandler connection;
    private static QuickCraftAnvilPlanner planner;
    private static QuickCraftAnvilPlanner.Plan plan;
    private static List<Text> targets = List.of();
    private static List<Text> skipped = List.of();
    private static boolean pickaxe;
    private static boolean creative;
    private static List<ItemStack> expected;
    private static ItemStack expectedCursor = ItemStack.EMPTY;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("QuickCraft/Anvil");
    private static QuickCraftAnvilButton actionButton;
    private static int stepIndex;
    private static int equipmentSlot;
    private static int[] locations;
    private static int outputSlot = -1;
    private static boolean running;
    private static boolean executing;
    private static boolean waiting;
    private static boolean autoStart;
    private static boolean lastHotkey;
    private static long sentAt;
    private static Text status = message("idle");

    private QuickCraftAnvilAssistant() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickCraftAnvilAssistant::tick);
    }

    public static boolean ownsAnvil() { return planner != null || plan != null || executing; }

    public static boolean consumesHotkey(int key) {
        MinecraftClient client = MinecraftClient.getInstance();
        return QuickCraftConfigs.Crafting.ENABLE_ANVIL_ASSISTANT.getBooleanValue()
                && client.currentScreen instanceof AnvilScreen
                && ((key >= 0 && QuickCraftConfigs.Hotkeys.ANVIL_BUILD.getKeybind().matches(key))
                || QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.ANVIL_BUILD.getKeybind()));
    }

    public static void attach(QuickCraftAnvilButton action) {
        reset();
        actionButton = action;
        refreshButtons();
    }

    public static boolean scrollPlan(double mouseX, double mouseY, double horizontal, double vertical) {
        return actionButton != null && actionButton.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    public static void action(boolean hotkey) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!valid(client)) return;
        if (running || planner != null) { stop("stopped"); return; }
        if (plan != null) {
            for (int slot = 0; slot <= 2; slot++) {
                if (QuickContainerLock.isLockedSlot(handler, slot)) { stop("locked"); return; }
            }
            if (!matchesSnapshot()) { stop("changed"); return; }
            int remainingCost = plan.state().steps().subList(stepIndex, plan.state().steps().size()).stream()
                    .mapToInt(QuickCraftAnvilPlanner.Step::cost).sum();
            if (remainingCost > client.player.experienceLevel && !client.player.getAbilities().creativeMode) {
                status = message("experience", remainingCost);
                refreshButtons();
                return;
            }
            running = true;
            if (!executing) {
                executing = true;
                equipmentSlot = plan.state().equipmentSlot();
                locations = java.util.stream.IntStream.range(0, handler.slots.size()).toArray();
                outputSlot = -1;
                stepIndex = 0;
            }
            return;
        }
        handler = (AnvilScreenHandler) client.player.currentScreenHandler;
        connection = client.getNetworkHandler();
        if (!handler.getCursorStack().isEmpty() || !handler.getSlot(1).getStack().isEmpty()
                || QuickCraftAnvilRename.isRapidRenameActive()) { stop("inputs"); return; }
        for (int slot = 0; slot <= 2; slot++) {
            if (QuickContainerLock.isLockedSlot(handler, slot)) { stop("locked"); return; }
        }
        try {
            planner = new QuickCraftAnvilPlanner(client, handler);
            targets = planner.targetDescription();
            pickaxe = planner.isPickaxe();
            creative = planner.isCreative();
            autoStart = hotkey;
            snapshot();
            status = message("planning");
        } catch (IllegalArgumentException invalid) {
            stop(invalid.getMessage());
        }
        refreshButtons();
    }

    private static void tick(MinecraftClient client) {
        if (!valid(client)) {
            if (handler != null || ownsAnvil() || actionButton != null) reset();
            lastHotkey = false;
            return;
        }
        boolean down = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.ANVIL_BUILD.getKeybind());
        if (down && !lastHotkey) action(true);
        lastHotkey = down;
        if (handler != null && (client.player.currentScreenHandler != handler || client.getNetworkHandler() != connection)) {
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
                        status = message(creative ? "creative_ready" : skipped.isEmpty() ? "ready" : "ready_partial",
                                plan.state().steps().size(), plan.cost());
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

    private static void advance(MinecraftClient client) {
        if (!matchesSnapshot()) { stop("changed"); return; }
        if (stepIndex >= plan.state().steps().size()) {
            var missing = skipped;
            stop(missing.isEmpty() ? "complete" : "partial_complete");
            targets = missing.stream().map(item -> message("missing", item)).toList();
            refreshButtons();
            return;
        }
        for (int slot = 0; slot <= 2; slot++) {
            if (QuickContainerLock.isLockedSlot(handler, slot)) { stop("locked"); return; }
        }
        var step = plan.state().steps().get(stepIndex);
        if (creative) { advanceCreative(client, step); return; }
        int inputSlot = locations[step.inputSlot()];
        if (inputSlot != 0) {
            if (!same(handler.getSlot(inputSlot).getStack(), step.input())) { stop("changed"); return; }
            boolean moved = handler.getSlot(0).getStack().isEmpty()
                    ? transfer(client, inputSlot, 0)
                    : transfer(client, inputSlot, 0, inputSlot);
            if (moved) swapLocations(inputSlot, 0);
            return;
        }
        if (!same(handler.getSlot(0).getStack(), step.input())) { stop("changed"); return; }
        if (handler.getSlot(1).getStack().isEmpty()) {
            int bookSlot = locations[step.bookSlot()];
            if (bookSlot < 3 || !same(handler.getSlot(bookSlot).getStack(), step.book())) { stop("changed"); return; }
            if (client.player.experienceLevel < step.cost() && !client.player.getAbilities().creativeMode) {
                running = false;
                status = message("experience", step.cost());
                return;
            }
            // 默认显示名随客户端语言变化，发给服务器会被当作额外改名。
            String name = step.input().contains(DataComponentTypes.CUSTOM_NAME) ? step.input().getName().getString() : "";
            if (handler.setNewItemName(name)) connection.sendPacket(new RenameItemC2SPacket(name));
            if (transfer(client, bookSlot, 1)) {
                outputSlot = bookSlot;
                swapLocations(bookSlot, 1);
            }
            return;
        }
        // 结果槽会被服务端和命名框多次刷新，只在实际领取前校验完整结果及费用。
        if (!same(handler.getSlot(1).getStack(), step.book())
                || !same(handler.getSlot(2).getStack(), step.output()) || handler.getLevelCost() != step.cost()
                || !handler.getSlot(2).canTakeItems(client.player)) {
            LOGGER.warn("铁砧结果不符：步骤={}，预计费用={}，实际费用={}，预计产物={}，实际产物={}",
                    stepIndex + 1, step.cost(), handler.getLevelCost(), step.output(), handler.getSlot(2).getStack());
            stop("changed");
            return;
        }
        if (outputSlot < 3) { stop("changed"); return; }
        if (!handler.getSlot(outputSlot).getStack().isEmpty()) { stop("full"); return; }
        if (transfer(client, 2, outputSlot)) {
            swapLocations(0, outputSlot);
            outputSlot = -1;
            stepIndex++;
            status = message("working", stepIndex, plan.state().steps().size());
        }
    }

    // 步骤引用原始材料编号；左槽换料可能暂存另一件装备，必须同步其实际位置。
    private static void swapLocations(int first, int second) {
        for (int index = 0; index < locations.length; index++) {
            if (locations[index] == first) locations[index] = second;
            else if (locations[index] == second) locations[index] = first;
        }
    }

    private static void advanceCreative(MinecraftClient client, QuickCraftAnvilPlanner.Step step) {
        if (!client.player.getAbilities().creativeMode || !QuickCraftConfigs.Crafting.ANVIL_CREATIVE_NO_BOOKS.getBooleanValue()) {
            stop("changed");
            return;
        }
        if (QuickContainerLock.isLockedSlot(handler, equipmentSlot)) { stop("locked"); return; }
        if (!same(handler.getSlot(equipmentSlot).getStack(), step.input())) { stop("changed"); return; }
        if (equipmentSlot == 0) {
            for (int slot = 3; slot < handler.slots.size(); slot++) {
                var destination = handler.getSlot(slot);
                int index = destination.getIndex();
                if (destination.inventory != client.player.getInventory() || index < 0 || index >= 36
                        || !destination.getStack().isEmpty() || QuickContainerLock.isLockedSlot(handler, slot)
                        || !destination.canInsert(step.output())) continue;
                if (transfer(client, 0, slot)) equipmentSlot = slot;
                return;
            }
            stop("full");
            return;
        }
        var destination = handler.getSlot(equipmentSlot);
        int index = destination.getIndex();
        // 创造包使用玩家容器槽位：快捷栏为 36–44，主背包为 9–35；不能直接写铁砧输入槽。
        client.interactionManager.clickCreativeStack(step.output().copy(), index < 9 ? 36 + index : index);
        destination.setStack(step.output().copy());
        stepIndex++;
        awaitConfirmation();
    }

    private static boolean transfer(MinecraftClient client, int... slots) {
        if (!handler.getCursorStack().isEmpty()) { stop("changed"); return false; }
        for (int slot : slots) {
            if (QuickContainerLock.isLockedSlot(handler, slot)) { stop("locked"); return false; }
        }
        for (int slot : slots) {
            ItemStack beforeSlot = handler.getSlot(slot).getStack().copy();
            ItemStack beforeCursor = handler.getCursorStack().copy();
            rawClick(client, slot);
            if (same(beforeSlot, handler.getSlot(slot).getStack()) && same(beforeCursor, handler.getCursorStack())) {
                recoverCursor(client, slots[slots.length - 1]);
                stop("changed");
                return false;
            }
        }
        if (!handler.getCursorStack().isEmpty()) {
            recoverCursor(client, slots[slots.length - 1]);
            stop("changed");
            return false;
        }
        awaitConfirmation();
        return true;
    }

    private static void awaitConfirmation() {
        snapshot();
        waiting = true;
        sentAt = System.nanoTime();
        connection.sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
    }

    private static void rawClick(MinecraftClient client, int slot) {
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
    }

    private static void recoverCursor(MinecraftClient client, int preferredSlot) {
        if (handler.getCursorStack().isEmpty()) return;
        if (preferredSlot >= 3 && !QuickContainerLock.isLockedSlot(handler, preferredSlot)
                && handler.getSlot(preferredSlot).getStack().isEmpty()
                && handler.getSlot(preferredSlot).canInsert(handler.getCursorStack())) {
            rawClick(client, preferredSlot);
        }
        for (int slot = 3; slot < handler.slots.size() && !handler.getCursorStack().isEmpty(); slot++) {
            if (!QuickContainerLock.isLockedSlot(handler, slot) && handler.getSlot(slot).getStack().isEmpty()
                    && handler.getSlot(slot).canInsert(handler.getCursorStack())) rawClick(client, slot);
        }
    }

    public static void onStatistics(ClientPlayNetworkHandler source) {
        if (!waiting || source != connection) return;
        waiting = false;
        if (!matchesSnapshot()) stop("changed");
    }

    private static void snapshot() {
        expected = handler.slots.stream().map(slot -> slot.getStack().copy()).toList();
        expectedCursor = handler.getCursorStack().copy();
    }

    private static boolean matchesSnapshot() {
        if (handler == null || expected == null || handler.slots.size() != expected.size()) return false;
        if (!same(handler.getCursorStack(), expectedCursor)) {
            LOGGER.warn("铁砧光标状态不符：步骤={}，预计={}，实际={}", stepIndex + 1, expectedCursor, handler.getCursorStack());
            return false;
        }
        for (int slot = 0; slot < expected.size(); slot++) {
            if (slot == 2) continue;
            if (!same(handler.getSlot(slot).getStack(), expected.get(slot))) {
                LOGGER.warn("铁砧材料状态不符：步骤={}，槽位={}，预计={}，实际={}",
                        stepIndex + 1, slot, expected.get(slot), handler.getSlot(slot).getStack());
                return false;
            }
        }
        return true;
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return first.getCount() == second.getCount() && ItemStack.areItemsAndComponentsEqual(first, second);
    }

    private static boolean valid(MinecraftClient client) {
        return QuickCraftConfigs.Crafting.ENABLE_ANVIL_ASSISTANT.getBooleanValue()
                && client.player != null && client.world != null && client.interactionManager != null
                && client.currentScreen instanceof AnvilScreen
                && client.player.currentScreenHandler instanceof AnvilScreenHandler;
    }

    private static void stop(String reason) {
        var client = MinecraftClient.getInstance();
        if (executing && handler != null && client.player != null && client.player.currentScreenHandler == handler
                && plan != null && !handler.getCursorStack().isEmpty()) {
            ItemStack carried = handler.getCursorStack();
            boolean taskItem = plan.state().steps().stream().anyMatch(step -> same(carried, step.input())
                    || same(carried, step.book()) || same(carried, step.output()));
            if (taskItem) recoverCursor(client, locations == null ? equipmentSlot : locations[plan.state().equipmentSlot()]);
        }
        planner = null;
        plan = null;
        stepIndex = 0;
        locations = null;
        outputSlot = -1;
        targets = List.of();
        skipped = List.of();
        pickaxe = creative = false;
        running = executing = waiting = autoStart = false;
        expected = null;
        expectedCursor = ItemStack.EMPTY;
        handler = null;
        connection = null;
        status = message(reason);
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(status, true);
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
        pickaxe = creative = false;
        stepIndex = 0;
        locations = null;
        outputSlot = -1;
        actionButton = null;
    }

    private static void refreshButtons() {
        if (actionButton == null) return;
        actionButton.visible = QuickCraftConfigs.Crafting.ENABLE_ANVIL_ASSISTANT.getBooleanValue();
        actionButton.setMessage(message(running || planner != null ? "stop_button" : plan != null ? "start_button" : "preview_button"));
        List<Text> lines = new ArrayList<>();
        lines.add(status);
        if (pickaxe) lines.add(message("selected_plan", QuickCraftConfigs.Crafting.ANVIL_PICKAXE_PLAN.getOptionListValue().getDisplayName()));
        if (plan != null) {
            lines.add(message("source", message(plan.state().equipmentSlot() == 0 ? "left_slot" : "inventory_slot",
                    plan.state().equipmentSlot() - 2), plan.state().equipment().getName()));
            for (int index = 0; index < plan.state().steps().size(); index++) {
                var step = plan.state().steps().get(index);
                if (creative) { lines.add(message("creative_step")); continue; }
                var description = describeEnchantments(step.book());
                if (step.input().isOf(Items.ENCHANTED_BOOK)) {
                    lines.add(message("merge_step", index + 1, describeEnchantments(step.input()), description, step.cost()));
                } else {
                    lines.add(message("step", index + 1, description, step.cost()));
                }
            }
        }
        lines.addAll(1, targets);
        skipped.forEach(item -> lines.add(message("missing", item)));
        var tooltip = Text.empty();
        for (Text line : lines) {
            if (!tooltip.getString().isEmpty()) tooltip.append("\n");
            tooltip.append(line);
        }
        actionButton.setPlanTooltip(tooltip);

    }

    private static Text describeEnchantments(ItemStack stack) {
        var description = Text.empty();
        for (var enchantment : QuickCraftAnvilPlanner.bookDescription(stack)) {
            if (!description.getString().isEmpty()) description.append(", ");
            description.append(enchantment);
        }
        return description;
    }

    private static Text message(String key, Object... args) {
        return Text.translatable("quickcraft.anvil." + key, args);
    }
}
