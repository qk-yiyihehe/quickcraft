package com.yiyihehe.quickcraft.compat.voidwarehouse;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 让投影容器材料的「已有数量」把<b>虚空仓库</b>也算进去。
 *
 * <h2>数据从哪来</h2>
 * <p>服务端能力由 {@code carpet-Neko-Addition} 提供：客户端发一条只读的
 * {@code carpet_neko_addition:printer_query}，服务端按物品 id 回
 * {@code printer_query_result}。这条通道与 {@code litematica-printer} 用的是同一条，
 * 所以两条路只可能有一条真的收到数据 —— 见下面的「共存」。</p>
 *
 * <h2>与 litematica-printer 共存</h2>
 * <p>Fabric 的全局接收器<b>每个通道只能有一个</b>：{@code registerGlobalReceiver}
 * 在通道已被占用时不报错，只是返回 {@code false}（先注册者独占）。printer 通常更早注册，
 * 因此这里分两条路：</p>
 * <ul>
 *   <li><b>自己收到通道</b>：自己发查询、自己缓存，数据最贴合当前界面。</li>
 *   <li><b>通道被 printer 占用</b>：不抢注册，改为反射读取 printer 已同步好的
 *       {@code VoidWarehouseMaterialCache}。printer 没装或没同步到数据时，本功能安静地按 0 处理。</li>
 * </ul>
 * <p>两条路都是只读的，不会改动仓库库存。</p>
 *
 * <h2>什么时候才真正发查询</h2>
 * <p>由「有没有人在看材料列表」驱动：材料行计算已有数量时会调 {@link #noteDemand}，
 * 只有最近 {@value #DEMAND_TTL_TICKS} tick 内被问过，才按 {@value #REFRESH_INTERVAL_TICKS} tick
 * 的节奏刷新。<b>没人看就不发</b> —— 这条通道是服务端资源，不该为关掉的界面持续发包。</p>
 */
@Environment(EnvType.CLIENT)
public final class QuickLitematicaVoidWarehouseMaterials {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft-VoidWarehouseMaterials");

    /** 正常刷新节奏（5 秒）。 */
    private static final int REFRESH_INTERVAL_TICKS = 100;
    /** 等回执的超时；超时就放行下一轮，避免一次丢包把刷新永久卡死。 */
    private static final int RESPONSE_TIMEOUT_TICKS = 40;
    /** 服务端回「限流/规则没开」后的退避时间。 */
    private static final int REJECT_BACKOFF_TICKS = 1200;
    /** 需求有效窗口：超过这个时间没人来问，就认为材料列表已经关掉了。 */
    private static final int DEMAND_TTL_TICKS = 200;
    /** 快照上限，与服务端回执上限一致，防止被改过的服务端撑爆内存。 */
    private static final int MAX_CACHE_ENTRIES = VoidWarehouseQueryResultPayload.MAX_ENTRIES;

    /** printer 的缓存类；只在反射可用时非空。 */
    private static final String PRINTER_CACHE_CLASS =
            "me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseMaterialCache";

    private static boolean initialized;
    /** 自己是否真的拿到了回执通道（false 表示被 printer 占了）。 */
    private static boolean ownsChannel;
    /** 通道被占时，printer 缓存句柄；反射失败全部为 null。 */
    private static Object printerCache;
    private static Method printerAmountOfMethod;
    private static Method printerIsReadyMethod;

    private static final Map<String, Long> AMOUNTS = new LinkedHashMap<>();
    private static boolean ready;
    private static boolean failureLogged;

    /** 最近被问到的物品 id（保持顺序便于排查），用于拼查询清单。材料列表可能在构建线程调用，需同步。 */
    private static final Set<String> DEMAND = new LinkedHashSet<>();
    private static volatile long lastDemandTick = Long.MIN_VALUE;

    private static int nextNonce = 1;
    private static int latestNonce;
    private static boolean pending;
    private static long sentAtTick;
    private static long lastRequestTick = Long.MIN_VALUE;
    private static long rejectedUntilTick = Long.MIN_VALUE;

    private QuickLitematicaVoidWarehouseMaterials() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        registerPayloads();
        ownsChannel = registerReceiver();
        resolvePrinterCache();

        if (!ownsChannel && printerCache == null) {
            // 既抢不到通道、又读不到 printer 的数据：功能在这个环境里不可能有数据。
            LOGGER.info("[VoidWarehouse] printer 已占用 {} 且无法读取其缓存，虚空仓库来源在此环境不可用",
                    VoidWarehouseQueryPayload.TYPE.id());
        }

        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaVoidWarehouseMaterials::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    /**
     * 登记 payload 类型。
     *
     * <p>必须容错：printer 可能已经用同一个通道 id 登记过（{@code PayloadTypeRegistry}
     * 是全局静态表，重复登记会抛）。已经登记过就说明线上格式已就位，直接跳过即可。</p>
     */
    private static void registerPayloads() {
        try {
            PayloadTypeRegistry.serverboundPlay().register(VoidWarehouseQueryPayload.TYPE, VoidWarehouseQueryPayload.CODEC);
        } catch (RuntimeException exception) {
            logOnce("无法登记请求通道（可能已由其它模组登记）", exception);
        }
        try {
            PayloadTypeRegistry.clientboundPlay().register(
                    VoidWarehouseQueryResultPayload.TYPE, VoidWarehouseQueryResultPayload.CODEC);
        } catch (RuntimeException exception) {
            logOnce("无法登记回执通道（可能已由其它模组登记）", exception);
        }
    }

    /** @return true 表示这条回执通道归我们；false 表示已被别人占用 */
    private static boolean registerReceiver() {
        try {
            return ClientPlayNetworking.registerGlobalReceiver(
                    VoidWarehouseQueryResultPayload.TYPE,
                    (payload, context) -> receive(payload)
            );
        } catch (RuntimeException exception) {
            logOnce("无法注册回执接收器", exception);
            return false;
        }
    }

    /** 通道被 printer 占用时，反射拿它的缓存读取方法；拿不到就保持 null。 */
    private static void resolvePrinterCache() {
        if (ownsChannel || !FabricLoader.getInstance().isModLoaded("litematica-printer")) {
            return;
        }
        try {
            Class<?> cacheClass = Class.forName(PRINTER_CACHE_CLASS, false,
                    QuickLitematicaVoidWarehouseMaterials.class.getClassLoader());
            Object instance = cacheClass.getMethod("get").invoke(null);
            if (instance == null) {
                return;
            }
            printerCache = instance;
            printerAmountOfMethod = cacheClass.getMethod("amountOf", String.class);
            printerIsReadyMethod = cacheClass.getMethod("isReady");
        } catch (Throwable throwable) {
            printerCache = null;
            printerAmountOfMethod = null;
            printerIsReadyMethod = null;
            logOnce("无法读取 litematica-printer 的虚空仓库缓存", throwable);
        }
    }

    /**
     * 记录「有人在问这个物品的库存」。
     *
     * <p>由材料行的已有数量计算调用，同时充当查询驱动信号。</p>
     */
    public static void noteDemand(List<ItemStack> targets) {
        if (!isSourceEnabled() || targets == null || targets.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        lastDemandTick = client.level.getGameTime();
        if (!ownsChannel) {
            // 通道不归我们，清单没有意义（printer 自己会拼）。
            return;
        }
        synchronized (DEMAND) {
            for (ItemStack stack : targets) {
                String id = idOf(stack);
                if (id == null) {
                    continue;
                }
                if (DEMAND.size() >= VoidWarehouseQueryPayload.MAX_IDS && !DEMAND.contains(id)) {
                    // 超过单次上限就不再收新 id：宁可少显示一部分，也不发一个超规格的包。
                    break;
                }
                DEMAND.add(id);
            }
        }
    }

    /**
     * 这批目标物品在虚空仓库里的合计数量。
     *
     * @param targets 与材料行相同的目标物品（可含多个替换目标）
     * @return 仓库数量合计；无数据时 0
     */
    public static long amountFor(List<ItemStack> targets) {
        if (!isSourceEnabled() || targets == null || targets.isEmpty()) {
            return 0L;
        }
        Set<String> seen = new LinkedHashSet<>();
        long total = 0L;
        for (ItemStack stack : targets) {
            String id = idOf(stack);
            if (id == null || !seen.add(id)) {
                continue;
            }
            long amount = amountOf(id);
            if (amount <= 0L) {
                continue;
            }
            // 饱和相加：仓库单条就能到 Long.MAX_VALUE。
            total = total > Long.MAX_VALUE - amount ? Long.MAX_VALUE : total + amount;
        }
        return total;
    }

    private static long amountOf(String id) {
        if (ownsChannel) {
            synchronized (AMOUNTS) {
                if (!ready) {
                    return 0L;
                }
                Long value = AMOUNTS.get(id);
                return value == null ? 0L : value;
            }
        }
        return printerWarehouseAmountOf(id);
    }

    /** 读 printer 的缓存；不可用或没同步到任何数据时返回 0。 */
    private static long printerWarehouseAmountOf(String id) {
        if (printerCache == null || printerAmountOfMethod == null || printerIsReadyMethod == null) {
            return 0L;
        }
        try {
            if (!Boolean.TRUE.equals(printerIsReadyMethod.invoke(printerCache))) {
                return 0L;
            }
            Object value = printerAmountOfMethod.invoke(printerCache, id);
            return value instanceof Long amount ? amount : 0L;
        } catch (Throwable throwable) {
            printerCache = null;
            printerAmountOfMethod = null;
            printerIsReadyMethod = null;
            logOnce("读取 litematica-printer 虚空仓库缓存失败，本次会话不再尝试", throwable);
            return 0L;
        }
    }

    private static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            clear();
            return;
        }
        if (!isSourceEnabled() || !ownsChannel || !isSupported()) {
            if (ready || !AMOUNTS.isEmpty()) {
                clearSnapshot();
            }
            return;
        }

        long now = client.level.getGameTime();

        if (pending) {
            if (now - sentAtTick <= RESPONSE_TIMEOUT_TICKS) {
                return;
            }
            // 超时放行，否则一次丢包就把刷新永久卡死。
            pending = false;
        }
        if (now < rejectedUntilTick) {
            return;
        }
        if (lastRequestTick != Long.MIN_VALUE && now - lastRequestTick < REFRESH_INTERVAL_TICKS) {
            return;
        }
        if (lastDemandTick == Long.MIN_VALUE || now - lastDemandTick > DEMAND_TTL_TICKS) {
            // 没人在看材料列表：不再发包，但保留已有快照，避免界面刚关就闪回 0。
            return;
        }

        List<String> ids;
        synchronized (DEMAND) {
            ids = new ArrayList<>(DEMAND);
            DEMAND.clear();
        }
        if (ids.isEmpty()) {
            return;
        }

        lastRequestTick = now;
        int nonce = send(ids);
        if (nonce == 0) {
            pending = false;
            return;
        }
        pending = true;
        sentAtTick = now;
    }

    private static int send(List<String> ids) {
        int nonce = nextNonce++;
        if (nextNonce <= 0) {
            nextNonce = 1;
        }
        latestNonce = nonce;
        try {
            ClientPlayNetworking.send(new VoidWarehouseQueryPayload(nonce, ids));
            return nonce;
        } catch (RuntimeException exception) {
            logOnce("发送虚空仓库查询失败", exception);
            return 0;
        }
    }

    private static void receive(VoidWarehouseQueryResultPayload payload) {
        if (payload.nonce() != latestNonce) {
            // 旧请求的迟到回执：丢掉，别让它覆盖更新的快照。
            pending = false;
            return;
        }
        switch (payload.status()) {
            case VoidWarehouseQueryResultPayload.OK -> {
                apply(payload.itemIds(), payload.amounts());
                pending = false;
            }
            case VoidWarehouseQueryResultPayload.THROTTLED -> {
                // 服务端限流：退避一段时间再试。
                rejectedUntilTick = lastRequestTick + REJECT_BACKOFF_TICKS;
                pending = false;
            }
            case VoidWarehouseQueryResultPayload.RULE_OFF -> {
                // 规则没开：清空快照，避免继续显示上一台服务器的库存。规则可能被临时打开，
                // 所以只退避、不永久禁用。
                rejectedUntilTick = lastRequestTick + REJECT_BACKOFF_TICKS;
                clearSnapshot();
                pending = false;
            }
            default -> pending = false;
        }
    }

    /** 用一份新快照<b>整体替换</b>旧内容：换了投影之后旧键不该继续挂着。 */
    private static void apply(List<String> ids, List<Long> values) {
        if (ids == null || values == null) {
            return;
        }
        int size = Math.min(Math.min(ids.size(), values.size()), MAX_CACHE_ENTRIES);
        synchronized (AMOUNTS) {
            AMOUNTS.clear();
            for (int index = 0; index < size; index++) {
                String id = ids.get(index);
                Long value = values.get(index);
                if (id == null || id.isEmpty() || value == null || value <= 0L) {
                    continue;
                }
                AMOUNTS.merge(id, value, Long::sum);
            }
            ready = true;
        }
    }

    /** 服务端能力探测：只有服务端装了对应模组并声明了这条通道才发得出去。 */
    public static boolean isSupported() {
        try {
            return ClientPlayNetworking.canSend(VoidWarehouseQueryPayload.TYPE);
        } catch (RuntimeException exception) {
            // canSend 在没有连接时会抛，那本身就是「不支持」。
            return false;
        }
    }

    /**
     * 本功能是否可能给出任何数字。
     *
     * <p>开关关着时调用方应当<b>完全不动</b>返回值，所以这里同时判开关与数据来源可用性。</p>
     */
    public static boolean isSourceEnabled() {
        return QuickCraftConfigs.isVoidWarehouseMaterialSourceEnabled();
    }

    private static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() == Items.AIR) {
            return null;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        // 服务端会拿这个字符串再解析一次，所以必须是 namespace:path 的完整形式。
        return key == null ? null : key.toString();
    }

    private static void clearSnapshot() {
        synchronized (AMOUNTS) {
            AMOUNTS.clear();
            ready = false;
        }
    }

    private static void clear() {
        clearSnapshot();
        synchronized (DEMAND) {
            DEMAND.clear();
        }
        lastDemandTick = Long.MIN_VALUE;
        lastRequestTick = Long.MIN_VALUE;
        rejectedUntilTick = Long.MIN_VALUE;
        pending = false;
        latestNonce = 0;
    }

    private static void logOnce(String message, Throwable throwable) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        LOGGER.warn("[VoidWarehouse] {}", message, throwable);
    }
}
