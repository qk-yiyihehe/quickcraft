package com.yiyihehe.quickcraft.compat.voidwarehouse;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 服务端 → 客户端：「你要的那批物品，仓库里各有多少」。
 *
 * <p>同样是 {@code printer_query_result} 的客户端镜像，字段与顺序必须与服务端一致。</p>
 *
 * <p>回执把 {@code itemIds} 原样回显一遍：{@code itemIds[i]} 对应 {@code amounts[i]}，
 * 因此「拿上一轮的回执去套这一轮的清单」这种错配在结构上就不可能发生 ——
 * 客户端只要校验 {@code nonce} 就能判断这是不是自己最新那次请求的答案。</p>
 *
 * <p>只回有货的物品：仓库里没有的不占位置，「不在表里」就是 0。</p>
 */
public record VoidWarehouseQueryResultPayload(int nonce, int status, List<String> itemIds, List<Long> amounts)
        implements CustomPacketPayload {

    /** 回执通道：{@code carpet_neko_addition:printer_query_result}（S2C）。 */
    public static final CustomPacketPayload.Type<VoidWarehouseQueryResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_query_result"));

    /** 成功：{@code itemIds} / {@code amounts} 有效（可能都为空 = 这些物品仓库里一个都没有）。 */
    public static final int OK = 0;
    /** 拒绝：Carpet 规则没开（{@code voidWarehouse} 或 {@code voidWarehouseRestock}）。 */
    public static final int RULE_OFF = 1;
    /** 包本身不合法（清单为空 / 全是解析不出来的 id）。 */
    public static final int BAD_REQUEST = 2;
    /** 撞上限流：客户端问得太快。 */
    public static final int THROTTLED = 3;

    /**
     * 回执最多多少条。
     *
     * <p>必须 ≥ 请求侧的 {@link VoidWarehouseQueryPayload#MAX_IDS}，否则「客户端问了个满清单、
     * 服务端刚好全都有」时会在客户端解码侧被截断报错。</p>
     */
    public static final int MAX_ENTRIES = VoidWarehouseQueryPayload.MAX_IDS;

    private static final StreamCodec<ByteBuf, List<String>> IDS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_ENTRIES).apply(ByteBufCodecs.STRING_UTF8);

    private static final StreamCodec<ByteBuf, List<Long>> AMOUNTS_CODEC =
            ByteBufCodecs.<ByteBuf, Long>list(MAX_ENTRIES).apply(ByteBufCodecs.VAR_LONG);

    public static final StreamCodec<FriendlyByteBuf, VoidWarehouseQueryResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VoidWarehouseQueryResultPayload::nonce,
            ByteBufCodecs.VAR_INT, VoidWarehouseQueryResultPayload::status,
            IDS_CODEC, VoidWarehouseQueryResultPayload::itemIds,
            AMOUNTS_CODEC, VoidWarehouseQueryResultPayload::amounts,
            VoidWarehouseQueryResultPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
