package com.yiyihehe.quickcraft.litematica;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class QuickLitematicaPreviewResourceAccess implements SimpleResourceReloadListener<String> {
    static void register() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new QuickLitematicaPreviewResourceAccess());
    }

    @Override
    public Identifier getFabricId() { return Identifier.of("quickcraft", "preview_mesh_signature"); }

    @Override
    public CompletableFuture<String> load(ResourceManager manager, Executor executor) {
        return CompletableFuture.supplyAsync(() -> QuickLitematicaPreviewResources.load(() -> {
            Map<String, Callable<InputStream>> resources = new TreeMap<>();
            manager.findResources("", id -> QuickLitematicaPreviewResources.relevant(id.getPath())).forEach(
                    (id, resource) -> resources.put(id.toString(), resource::getInputStream));
            return resources;
        }), executor).thenApplyAsync(data -> {
            // 准备屏障放行前取消旧模型构建，避免混用重载前后的模型和 atlas。
            QuickLitematicaPreviewResources.prepared();
            return data;
        }, net.minecraft.client.MinecraftClient.getInstance());
    }

    @Override
    public CompletableFuture<Void> apply(String data, ResourceManager manager, Executor executor) {
        return CompletableFuture.runAsync(() -> QuickLitematicaPreviewResources.apply(data + ":mipmap=" + net.minecraft.client.MinecraftClient.getInstance().options.getMipmapLevels().getValue()), executor);
    }
}
