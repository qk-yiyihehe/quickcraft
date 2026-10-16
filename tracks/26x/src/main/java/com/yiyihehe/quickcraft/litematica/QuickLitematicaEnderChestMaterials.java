package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListSorter;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.data.ItemType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.phys.BlockHitResult;
import java.util.ArrayList;
import java.util.List;

/** 复用 QuickShulker 同步的末影箱库存；未提供同步时记录实际开箱内容，仅供 HUD 使用。 */
public final class QuickLitematicaEnderChestMaterials {
    private static final int OPEN_TIMEOUT_TICKS = 100; // 允许服务器在 5 秒内返回开箱内容。
    private static final Object2IntOpenHashMap<ItemType> COUNTS = new Object2IntOpenHashMap<>();
    private static List<ItemStack> snapshot = List.of();
    private static ChestMenu activeHandler;
    private static int pendingTicks;

    private QuickLitematicaEnderChestMaterials() { }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaEnderChestMaterials::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    public static void rememberContainerUse(Minecraft client, BlockHitResult hit) {
        // 只接管真实末影箱的成功交互，不能把同为 27 格的普通箱子当作末影箱。
        pendingTicks = client.level != null
                && client.level.getBlockState(hit.getBlockPos()).getBlock() instanceof EnderChestBlock ? OPEN_TIMEOUT_TICKS : 0;
    }

    public static void onContentsReceived(int syncId) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.player.containerMenu.containerId != syncId) { return; }
        if (pendingTicks > 0 && client.player.containerMenu instanceof ChestMenu handler
                && handler.getRowCount() == 3) {
            activeHandler = handler;
            pendingTicks = 0;
        }
        // 完整内容包可能触发其他功能立即关箱，必须在它们执行前保存。
        if (activeHandler == client.player.containerMenu) { capture(activeHandler.getContainer()); }
    }

    static void tick(Minecraft client) {
        if (client.player == null || client.level == null) { clear(); return; }
        if (QuickShulkerConnection.canOpen()) {
            // QuickShulker 在加入、重生、切维度及末影箱存取时同步这份玩家库存。
            capture(client.player.getEnderChestInventory());
            activeHandler = null;
            pendingTicks = 0;
            return;
        }
        if (pendingTicks > 0) { pendingTicks--; }
        if (activeHandler != null) {
            // 即使本 tick 已关箱，处理器仍保留最后一次存取后的槽位。
            capture(activeHandler.getContainer());
            if (client.player.containerMenu != activeHandler) { activeHandler = null; }
        }
    }

    private static void capture(Container inventory) {
        int size = inventory.getContainerSize();
        boolean changed = snapshot.size() != size;
        for (int slot = 0; !changed && slot < size; slot++) {
            changed = !ItemStack.matches(snapshot.get(slot), inventory.getItem(slot));
        }
        if (!changed) { return; }
        List<ItemStack> stacks = new ArrayList<>();
        COUNTS.clear();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot).copy();
            stacks.add(stack);
            addStack(stack, 1, 0);
        }
        snapshot = stacks;
    }

    private static void addStack(ItemStack stack, int multiplier, int depth) {
        if (stack.isEmpty()) { return; }
        int count = stack.getCount() * multiplier;
        COUNTS.addTo(new ItemType(stack, true, true), count);
        // 与容器材料解析保持相同的嵌套上限，兼容服务器提供的嵌套潜影盒。
        if (depth >= 4 || !(stack.getItem() instanceof BlockItem item)
                || !(item.getBlock() instanceof ShulkerBoxBlock)) { return; }
        var stored = InventoryUtils.getStoredItems(stack);
        if (stored != null) {
            for (ItemStack nested : stored) { addStack(nested, count, depth + 1); }
        }
    }

    public static List<MaterialListEntry> hud(MaterialListBase list, List<MaterialListEntry> entries) {
        if (!QuickCraftConfigs.ProjectionTools.INCLUDE_ENDER_CHEST_IN_MATERIAL_HUD.getBooleanValue()
                || COUNTS.isEmpty()) { return entries; }
        List<MaterialListEntry> result = new ArrayList<>();
        for (MaterialListEntry entry : entries) {
            int available = entry.getCountAvailable() + COUNTS.getInt(new ItemType(entry.getStack(), true, true));
            int needed = list.getMultiplier() == 1 ? entry.getCountMissing() : entry.getCountTotal() * list.getMultiplier();
            if (needed > available) {
                // HUD 使用副本，避免重复累加，也不把末影箱库存交给背包自动补料和备货。
                result.add(new MaterialListEntry(entry.getStack(), entry.getCountTotal(), entry.getCountMissing(),
                        entry.getCountMismatched(), available));
            }
        }
        result.sort(new MaterialListSorter(list));
        return result;
    }

    private static void clear() {
        COUNTS.clear();
        snapshot = List.of();
        activeHandler = null;
        pendingTicks = 0;
    }
}
