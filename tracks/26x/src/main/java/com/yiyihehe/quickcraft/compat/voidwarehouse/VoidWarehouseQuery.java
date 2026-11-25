package com.yiyihehe.quickcraft.compat.voidwarehouse;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/** Fabric 按通道选编解码器，发送和接收都必须使用 Neko 已注册的包类型。 */
record VoidWarehouseQuery(
        Constructor<? extends CustomPacketPayload> request,
        Class<?> resultClass,
        Method nonce,
        Method status,
        Method itemIds,
        Method amounts
) {
    static final Identifier REQUEST_ID = Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_query");
    static final CustomPacketPayload.Type<CustomPacketPayload> RESULT_TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_query_result")
    );
    static final int MAX_IDS = 1024;
    static final int OK = 0;
    static final int RULE_OFF = 1;
    static final int THROTTLED = 3;

    static VoidWarehouseQuery load() throws ReflectiveOperationException {
        String payloadPackage = "neko.voidwarehouse.restock.";
        var requestClass = Class.forName(payloadPackage + "PrinterQueryPayload").asSubclass(CustomPacketPayload.class);
        var resultClass = Class.forName(payloadPackage + "PrinterQueryResultPayload").asSubclass(
                CustomPacketPayload.class
        );
        return new VoidWarehouseQuery(
                requestClass.getConstructor(int.class, List.class),
                resultClass,
                resultClass.getMethod("nonce"),
                resultClass.getMethod("status"),
                resultClass.getMethod("itemIds"),
                resultClass.getMethod("amounts")
        );
    }

    CustomPacketPayload createRequest(int requestNonce, List<String> ids) throws ReflectiveOperationException {
        return this.request.newInstance(requestNonce, ids);
    }

    Result readResult(CustomPacketPayload payload) throws ReflectiveOperationException {
        this.resultClass.cast(payload);
        return new Result(
                (int) this.nonce.invoke(payload),
                (int) this.status.invoke(payload),
                (List<?>) this.itemIds.invoke(payload),
                (List<?>) this.amounts.invoke(payload)
        );
    }

    record Result(int nonce, int status, List<?> itemIds, List<?> amounts) {
    }
}
