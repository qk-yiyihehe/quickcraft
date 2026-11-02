package com.yiyihehe.quickcraft.compat.voidwarehouse;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 客户端 → 服务端：「这些物品在虚空仓库里各有多少？」。
 *
 * <p>这是 {@code carpet-Neko-Addition} 服务端协议里 {@code printer_query} 的客户端镜像。
 * 通道名与线上格式必须和服务端、以及 {@code litematica-printer} 里那份副本完全一致，
 * 否则表现是「材料列表里仓库库存永远是 0」—— 不报错、不崩服，只是没效果。</p>
 *
 * <p>本类刻意<b>使用自己的包名</b>而不是复用 {@code neko.voidwarehouse.restock}：
 * 同一个 jar 里再声明一份与 printer 同名的类，会在两者同时安装时变成两份同 FQCN
 * 的类，排障时极难分清谁在起作用。真正必须一致的是<b>通道标识与字段顺序</b>，
 * 类名不参与线上协议。</p>
 *
 * <p>与取货通道 {@code printer_take} 的分工：查询是<b>纯只读</b>的，
 * 不会改动仓库库存；本功能只做统计，所以只发这条。</p>
 */
public record VoidWarehouseQueryPayload(int nonce, List<String> itemIds) implements CustomPacketPayload {

    /** 查询通道：{@code carpet_neko_addition:printer_query}（C2S）。归服务端命名空间，两端解析出的标识必须相等。 */
    public static final CustomPacketPayload.Type<VoidWarehouseQueryPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_query"));

    /**
     * 一次最多问多少种物品。
     *
     * <p>必须与服务端的 {@code PrinterQueryPayload.MAX_IDS} 一致：{@code ByteBufCodecs.list(n)}
     * 在<b>解码</b>时按 {@code n} 限长，不一致会读出错误的条数甚至直接抛异常。
     * 超出上限时调用方分批询问，不会丢数据。</p>
     */
    public static final int MAX_IDS = 1024;

    private static final StreamCodec<ByteBuf, List<String>> IDS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_IDS).apply(ByteBufCodecs.STRING_UTF8);

    public static final StreamCodec<FriendlyByteBuf, VoidWarehouseQueryPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VoidWarehouseQueryPayload::nonce,
            IDS_CODEC, VoidWarehouseQueryPayload::itemIds,
            VoidWarehouseQueryPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
