package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

/** 单人请求绑定玩家和内置服务器实例；迟到回调不能结束后续整理。 */
final class QuickCrossBoxSortIntegratedSession {
    private QuickCrossBoxSortResult result;
    private int age;

    static QuickCrossBoxSortIntegratedSession create(MinecraftClient client, boolean onePerBox, List<Integer> menuSlots) {
        var server = client.getServer();
        var player = client.player;
        if (server == null || player == null) { return null; }
        var menu = player.currentScreenHandler;
        var inventory = new net.minecraft.inventory.SimpleInventory(menuSlots.stream()
                .map(slot -> menu.getSlot(slot).getStack().copy()).toArray(net.minecraft.item.ItemStack[]::new));
        var boxes = QuickCrossBoxSortInventory.scan(inventory).boxes();
        if (boxes.isEmpty()) {
            QuickCrossBoxSort.message(Text.translatable("quickcraft.message.cross_box_sort.nothing_to_sort"));
            return null;
        }
        var singles = QuickCrossBoxSortSpares.singles(inventory, slot -> false);
        int missing = Math.max(0, QuickCrossBoxSortSpares.needed(boxes) - boxes.size() - singles.size());
        var splits = onePerBox ? QuickCrossBoxSortSpares.splits(inventory, slot -> false, missing, false) : List.<QuickCrossBoxSortSpares.Spare>of();
        var spareSlots = new ArrayList<>(singles);
        splits.forEach(split -> spareSlots.add(split.targetSlot()));
        var plan = QuickCrossBoxSortPlanner.plan(boxes, spareSlots, QuickCrossBoxSort.comparator(), onePerBox);
        QuickCrossBoxSort.reportPlan(QuickCrossBoxSortSpares.needed(boxes), plan.targets().size(), onePerBox);
        var request = QuickCrossBoxSortRequest.create(boxes, plan.targets(), splits, menu.syncId, menuSlots);
        var session = new QuickCrossBoxSortIntegratedSession();
        server.execute(() -> {
            var serverPlayer = server.getPlayerManager().getPlayer(player.getUuid());
            var result = serverPlayer == null ? QuickCrossBoxSortResult.MISMATCH
                    : QuickCrossBoxSortIntegratedServer.apply(request, serverPlayer);
            client.execute(() -> {
                if (client.player == player && client.getServer() == server) {
                    session.result = result;
                }
            });
        });
        return session;
    }

    boolean tick(MinecraftClient client) {
        if (result != null) {
            QuickCrossBoxSort.message(result.success()
                    ? Text.translatable("quickcraft.message.cross_box_sort.completed", result.emptied(), result.remaining(), result.moves())
                    : Text.translatable(result.failureKey()));
            return false;
        }
        if (++age > 200) {
            QuickCrossBoxSort.message(Text.translatable("quickcraft.message.cross_box_sort.sync_timeout"));
            return false;
        }
        return true;
    }
}
