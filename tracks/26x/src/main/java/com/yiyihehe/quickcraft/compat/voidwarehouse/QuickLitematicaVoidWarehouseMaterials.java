package com.yiyihehe.quickcraft.compat.voidwarehouse;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.mixin.MalilibGuiListAccessor;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class QuickLitematicaVoidWarehouseMaterials {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft-VoidWarehouseMaterials");
    private static final int REFRESH_INTERVAL_TICKS = 100;
    private static final int RESPONSE_TIMEOUT_TICKS = 40;
    private static final int REJECT_BACKOFF_TICKS = 1200;
    private static final int DEMAND_TTL_TICKS = 200;
    private static final String PRINTER_CACHE_CLASS =
            "me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseMaterialCache";

    // 材料行可能由后台任务构建。需求和库存快照由同一把锁保护。
    private static final Map<String, Long> AMOUNTS = new HashMap<>();
    private static final Set<String> DEMAND = new LinkedHashSet<>();
    private static long demandUntil;
    private static volatile long tick;
    private static volatile VoidWarehouseQuery query;
    private static volatile PrinterCache printerCache;
    private static int nextNonce;
    private static Request pending;
    private static long nextRequestTick;

    private QuickLitematicaVoidWarehouseMaterials() { }

    public static boolean isInstalled() {
        return FabricLoader.getInstance().isModLoaded("carpet_neko_addition");
    }

    public static void initialize() {
        if (!isInstalled()) { return; }
        // 等所有客户端入口运行完再认领接收器，让 printer 先注册自己的处理器。
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                if (ClientPlayNetworking.getGlobalReceivers().contains(VoidWarehouseQuery.RESULT_TYPE.id())) {
                    loadPrinterCache();
                } else {
                    var protocol = VoidWarehouseQuery.load();
                    if (ClientPlayNetworking.registerGlobalReceiver(VoidWarehouseQuery.RESULT_TYPE,
                            (payload, context) -> receive(protocol, payload))) {
                        query = protocol;
                    } else {
                        loadPrinterCache();
                    }
                }
            } catch (ReflectiveOperationException | IllegalArgumentException | ClassCastException | LinkageError exception) {
                LOGGER.warn("无法接入虚空仓库库存查询", exception);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaVoidWarehouseMaterials::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    private static void loadPrinterCache() throws ReflectiveOperationException {
        if (!FabricLoader.getInstance().isModLoaded("litematica-printer")) { return; }
        Class<?> type = Class.forName(PRINTER_CACHE_CLASS);
        Object cache = type.getMethod("get").invoke(null);
        if (cache != null) {
            printerCache = new PrinterCache(cache, type.getMethod("amountOf", String.class), type.getMethod("isReady"));
        }
    }

    private static boolean canUseSource() {
        return (query != null || printerCache != null) && QuickCraftConfigs.isVoidWarehouseMaterialSourceEnabled()
                && ClientPlayNetworking.canSend(VoidWarehouseQuery.REQUEST_ID);
    }

    public static int addProjectionAvailable(int inventory, ItemStack stack) {
        // printer 持有通道时会自行修改原生材料行，只有容器汇总行需要另读它的缓存。
        if (query == null || !canUseSource()) { return inventory; }
        return addStack(inventory, stack);
    }

    public static int addAvailable(int inventory, List<ItemStack> targets) {
        if (!canUseSource() || targets.isEmpty()) { return inventory; }
        if (targets.size() == 1) { return addStack(inventory, targets.getFirst()); }

        Set<String> seen = new LinkedHashSet<>();
        int total = inventory;
        for (ItemStack stack : targets) {
            if (stack.isEmpty()) { continue; }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (seen.add(id)) {
                total = VoidWarehouseMaterialCounts.merge(total, amountFor(id));
            }
        }
        return total;
    }

    private static int addStack(int inventory, ItemStack stack) {
        if (stack.isEmpty()) { return inventory; }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return VoidWarehouseMaterialCounts.merge(inventory, amountFor(id));
    }

    private static long amountFor(String id) {
        var cache = printerCache;
        if (cache != null) {
            try {
                return (boolean) cache.isReady().invoke(cache.instance())
                        ? (long) cache.amountOf().invoke(cache.instance(), id) : 0;
            } catch (ReflectiveOperationException | ClassCastException exception) {
                printerCache = null;
                LOGGER.warn("读取 litematica-printer 的虚空仓库缓存失败", exception);
                return 0;
            }
        }
        synchronized (DEMAND) {
            if (tick >= demandUntil) {
                DEMAND.clear();
                AMOUNTS.clear();
            }
            DEMAND.add(id);
            demandUntil = tick + DEMAND_TTL_TICKS;
            return AMOUNTS.getOrDefault(id, 0L);
        }
    }

    private static void tick(Minecraft client) {
        tick++;
        var protocol = query;
        if (protocol == null) { return; }
        if (client.player == null || !canUseSource()) {
            clear();
            return;
        }
        if (pending != null && tick >= pending.sentAt() + RESPONSE_TIMEOUT_TICKS) {
            pending = null;
        }
        if (pending != null || tick < nextRequestTick) { return; }

        noteOpenMaterialList(client);
        List<String> ids;
        synchronized (DEMAND) {
            if (tick >= demandUntil) {
                DEMAND.clear();
                AMOUNTS.clear();
                return;
            }
            if (DEMAND.isEmpty()) { return; }
            var iterator = DEMAND.iterator();
            ids = new ArrayList<>(Math.min(DEMAND.size(), VoidWarehouseQuery.MAX_IDS));
            while (ids.size() < VoidWarehouseQuery.MAX_IDS && iterator.hasNext()) {
                ids.add(iterator.next());
                iterator.remove();
            }
        }

        nextRequestTick = tick + REFRESH_INTERVAL_TICKS;
        int nonce = ++nextNonce;
        try {
            ClientPlayNetworking.send(protocol.createRequest(nonce, ids));
            pending = new Request(nonce, tick, Set.copyOf(ids));
        } catch (ReflectiveOperationException | IllegalArgumentException | IllegalStateException exception) {
            LOGGER.warn("发送虚空仓库查询失败", exception);
        }
    }

    private static void receive(VoidWarehouseQuery protocol, CustomPacketPayload payload) {
        var request = pending;
        if (request == null || !canUseSource()) { return; }

        VoidWarehouseQuery.Result result;
        try {
            result = protocol.readResult(payload);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            LOGGER.warn("读取虚空仓库查询回执失败", exception);
            return;
        }
        // 迟到回执不能结束更新一次查询的等待。
        if (result.nonce() != request.nonce()) { return; }
        pending = null;

        if (result.status() == VoidWarehouseQuery.OK) {
            apply(request, result);
        } else if (result.status() == VoidWarehouseQuery.RULE_OFF || result.status() == VoidWarehouseQuery.THROTTLED) {
            nextRequestTick = tick + REJECT_BACKOFF_TICKS;
            if (result.status() == VoidWarehouseQuery.RULE_OFF) {
                synchronized (DEMAND) { AMOUNTS.clear(); }
                refreshOpenMaterialList();
            }
        }
    }

    private static void apply(Request request, VoidWarehouseQuery.Result result) {
        var ids = result.itemIds();
        var amounts = result.amounts();
        if (ids.size() != amounts.size()) { return; }
        Map<String, Long> received = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i) instanceof String id && request.ids().contains(id)
                    && amounts.get(i) instanceof Long amount && amount > 0) {
                received.put(id, amount);
            }
        }
        boolean changed = false;
        synchronized (DEMAND) {
            // 回执省略库存为零的项。只替换本批查询过的物品，保留其它批次的结果。
            for (String id : request.ids()) {
                Long amount = received.get(id);
                Long previous = amount == null ? AMOUNTS.remove(id) : AMOUNTS.put(id, amount);
                changed |= !Objects.equals(amount, previous);
            }
        }
        if (changed) { refreshOpenMaterialList(); }
    }

    private static void refreshOpenMaterialList() {
        if (QuickClientScreenAccess.currentScreen(Minecraft.getInstance()) instanceof GuiMaterialList gui) {
            // 原版隐藏已有材料时会移除候选项，库存下降后要从完整清单恢复。
            gui.getMaterialList().refreshPreFilteredList();
            ((MalilibGuiListAccessor) gui).quickcraft$getListWidget().refreshEntries();
        }
    }

    private static void noteOpenMaterialList(Minecraft client) {
        if (!(QuickClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) { return; }
        synchronized (DEMAND) {
            // 仍需查询已被「隐藏已有材料」移出界面的条目，否则库存下降时不能恢复它们。
            for (var entry : gui.getMaterialList().getMaterialsAll()) {
                if (entry instanceof MaterialTargets source) {
                    for (ItemStack stack : source.quickcraft$getWarehouseTargets()) {
                        if (!stack.isEmpty()) {
                            DEMAND.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                            demandUntil = tick + DEMAND_TTL_TICKS;
                        }
                    }
                }
            }
        }
    }

    private static void clear() {
        synchronized (DEMAND) {
            DEMAND.clear();
            AMOUNTS.clear();
            demandUntil = 0;
        }
        pending = null;
        nextRequestTick = 0;
    }

    private record Request(int nonce, long sentAt, Set<String> ids) { }
    private record PrinterCache(Object instance, Method amountOf, Method isReady) { }

    public interface MaterialTargets {
        List<ItemStack> quickcraft$getWarehouseTargets();
    }
}
