package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** 单人请求绑定玩家和内置服务器实例；迟到回调不能结束后续整理。 */
final class QuickCrossBoxSortIntegratedSession {
    private final Screen screen;
    private final boolean groupedFallback;
    private QuickCrossBoxSortResult result;
    private int age;

    private QuickCrossBoxSortIntegratedSession(Screen screen, boolean groupedFallback) {
        this.screen = screen;
        this.groupedFallback = groupedFallback;
    }

    static QuickCrossBoxSortIntegratedSession create(
            Minecraft client,
            Screen screen,
            boolean onePerBox,
            List<Integer> menuSlots
    ) {
        var server = client.getSingleplayerServer();
        var player = client.player;
        if (server == null || player == null) {
            return null;
        }
        var menu = player.containerMenu;
        var inventory = new net.minecraft.world.SimpleContainer(
                menuSlots.stream().map(slot -> menu.getSlot(slot).getItem().copy()).toArray(
                        net.minecraft.world.item.ItemStack[]::new
                )
        );
        var boxes = QuickCrossBoxSortInventory.scan(inventory).boxes();
        if (boxes.isEmpty()) {
            QuickCrossBoxSort.message(
                    screen,
                    Component.translatable("quickcraft.message.cross_box_sort.nothing_to_sort")
            );
            return null;
        }
        var singles = QuickCrossBoxSortSpares.singles(inventory, slot -> false);
        int missing = Math.max(0, QuickCrossBoxSortSpares.needed(boxes) - boxes.size() - singles.size());
        var splits = onePerBox ? QuickCrossBoxSortSpares.splits(inventory, slot -> false, missing, false)
                : List.<QuickCrossBoxSortSpares.Spare>of();
        var spareSlots = new ArrayList<>(singles);
        splits.forEach(split -> spareSlots.add(split.targetSlot()));
        var plan = QuickCrossBoxSortPlanner.plan(boxes, spareSlots, QuickCrossBoxSort.comparator(), onePerBox);
        boolean groupedFallback = QuickCrossBoxSort.reportPlan(
                screen,
                QuickCrossBoxSortSpares.needed(boxes),
                plan.targets().size(),
                onePerBox
        );
        var request = QuickCrossBoxSortRequest.create(boxes, plan.targets(), splits, menu.containerId, menuSlots);
        var session = new QuickCrossBoxSortIntegratedSession(screen, groupedFallback);
        server.execute(() -> {
            var serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
            var result = serverPlayer == null ? QuickCrossBoxSortResult.MISMATCH
                    : QuickCrossBoxSortIntegratedServer.apply(request, serverPlayer);
            client.execute(() -> {
                if (client.player == player && client.getSingleplayerServer() == server) {
                    session.result = result;
                }
            });
        });
        return session;
    }

    boolean tick(Minecraft client) {
        if (result != null) {
            if (result.success()) {
                QuickCrossBoxSort.reportCompletion(
                        screen,
                        groupedFallback,
                        result.emptied(),
                        result.remaining(),
                        result.moves()
                );
            } else {
                QuickCrossBoxSort.message(screen, Component.translatable(result.failureKey()));
            }
            return false;
        }
        if (++age > 200) {
            QuickCrossBoxSort.message(screen, Component.translatable("quickcraft.message.cross_box_sort.sync_timeout"));
            return false;
        }
        return true;
    }
}
