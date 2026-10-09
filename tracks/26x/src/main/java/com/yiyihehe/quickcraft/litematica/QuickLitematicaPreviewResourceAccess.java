package com.yiyihehe.quickcraft.litematica;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class QuickLitematicaPreviewResourceAccess implements SimpleResourceReloadListener<String> {
    static void register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new QuickLitematicaPreviewResourceAccess());
    }

    @Override
    public Identifier getFabricId() { return Identifier.fromNamespaceAndPath("quickcraft", "preview_mesh_signature"); }

    @Override
    public CompletableFuture<String> load(ResourceManager manager, Executor executor) {
        return CompletableFuture.supplyAsync(() -> QuickLitematicaPreviewResources.load(() -> {
            Map<String, Callable<InputStream>> resources = new TreeMap<>();
            manager.listResources("", id -> QuickLitematicaPreviewResources.relevant(id.getPath())).forEach(
                    (id, resource) -> resources.put(id.toString(), resource::open));
            return resources;
        }), executor).thenApplyAsync(data -> {
            // 准备屏障放行前取消旧模型构建，避免混用重载前后的模型和 atlas。
            QuickLitematicaPreviewResources.prepared();
            return data;
        }, net.minecraft.client.Minecraft.getInstance());
    }

    @Override
    public CompletableFuture<Void> apply(String data, ResourceManager manager, Executor executor) {
        return CompletableFuture.runAsync(() -> QuickLitematicaPreviewResources.apply(data + ":mipmap=" + net.minecraft.client.Minecraft.getInstance().options.mipmapLevels().get()), executor);
    }
}
