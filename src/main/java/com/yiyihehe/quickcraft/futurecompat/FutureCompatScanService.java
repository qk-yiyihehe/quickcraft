package com.yiyihehe.quickcraft.futurecompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异步扫描服务（后台单线程 + 结果缓存），供浏览器信息区按钮消费。
 * 缓存键 = 文件路径；命中条件 = 修改时间/大小/映射指纹均未变化，任一变化自动重扫。
 * 只在"Schema 支持且为未来版本"时做三类扫描；其余场合返回 {@link ScanResult#EMPTY}。
 */
public final class FutureCompatScanService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FutureCompatScanService.class);
    private static final int CACHE_MAX_ENTRIES = 128;

    // 懒加载：vanilla 门依赖 Litematica 常量（其类初始化需要游戏环境），单测不得触发
    private static volatile FutureCompatScanService instance;

    private final SchematicVersionGate gate;
    private final Supplier<@Nullable RegistrySnapshot> snapshotSupplier;
    private final Supplier<FutureCompatMappings> mappingsSupplier;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "quickcraft-future-compat-scan");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CachedScan> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ScanResult>> inFlight = new ConcurrentHashMap<>();

    /** 缓存的一次快照：文件被判定为未来版本与否 + 扫描结果（非未来版本恒为 EMPTY）。 */
    public record ScanSnapshot(SchematicVersionGate.ProbeDecision decision, ScanResult result) {
    }

    private record CachedScan(long modifiedMillis, long size, String fingerprint,
                              SchematicVersionGate.ProbeDecision decision, ScanResult result) {
    }

    public FutureCompatScanService(SchematicVersionGate gate,
                                   Supplier<@Nullable RegistrySnapshot> snapshotSupplier,
                                   Supplier<FutureCompatMappings> mappingsSupplier) {
        this.gate = gate;
        this.snapshotSupplier = snapshotSupplier;
        this.mappingsSupplier = mappingsSupplier;
    }

    public static FutureCompatScanService get() {
        FutureCompatScanService service = instance;
        if (service == null) {
            synchronized (FutureCompatScanService.class) {
                service = instance;
                if (service == null) {
                    instance = service = new FutureCompatScanService(
                            SchematicVersionGate.vanilla(),
                            FutureSchematicCompatibility::liveSnapshot,
                            FutureSchematicCompatibility::currentMappings);
                }
            }
        }
        return service;
    }

    /** 玩家映射保存后调用：指纹变化使全部缓存失效（下次扫描自动重算）。 */
    public void invalidateMappings() {
        this.cache.clear();
    }

    public CompletableFuture<ScanResult> scanAsync(Path file) {
        ScanSnapshot snapshot = peek(file);
        if (snapshot != null) {
            return CompletableFuture.completedFuture(snapshot.result());
        }
        String key = cacheKey(file);
        return this.inFlight.computeIfAbsent(key, k -> CompletableFuture
                .supplyAsync(() -> scan(file), this.executor)
                .whenComplete((result, throwable) -> this.inFlight.remove(k)));
    }

    /** 已缓存的快照（含版本判定；含指纹/文件变化校验）；未缓存或已失效返回 null。UI 每帧读取用。 */
    @Nullable
    public ScanSnapshot peek(Path file) {
        String fingerprint = this.mappingsSupplier.get().fingerprint();
        CachedScan cached = this.cache.get(cacheKey(file));
        if (cached != null && cached.fingerprint().equals(fingerprint) && fileUnchanged(file, cached)) {
            return new ScanSnapshot(cached.decision(), cached.result());
        }
        return null;
    }

    /** 仅供测试与确需同步结果的场合；UI 一律走 {@link #scanAsync}。 */
    public ScanResult scan(Path file) {
        try {
            String fingerprint = this.mappingsSupplier.get().fingerprint();
            CachedScan cached = this.cache.get(cacheKey(file));
            if (cached != null && cached.fingerprint().equals(fingerprint) && fileUnchanged(file, cached)) {
                return cached.result();
            }

            CachedScan fresh = computeCached(file, fingerprint);
            if (this.cache.size() >= CACHE_MAX_ENTRIES) {
                this.cache.clear(); // 简单防膨胀：超限整体失效，下次按需重建
            }
            this.cache.put(cacheKey(file), fresh);
            return fresh.result();
        } catch (UncheckedIOException e) {
            LOGGER.warn("Failed to read '{}' for future-version scan", file, e.getCause());
            return ScanResult.EMPTY;
        } catch (Exception e) {
            LOGGER.warn("Future-version scan failed for '{}'", file, e);
            return ScanResult.EMPTY;
        }
    }

    private CachedScan computeCached(Path file, String fingerprint) {
        CompoundTag root = readCompressed(file);
        SchematicVersionGate.ProbeDecision decision = this.gate.classify(
                root.getIntOr("Version", -1), root.getIntOr("MinecraftDataVersion", 0));
        ScanResult result = ScanResult.EMPTY;
        if (decision == SchematicVersionGate.ProbeDecision.FUTURE) {
            RegistrySnapshot snapshot = this.snapshotSupplier.get();
            if (snapshot != null) {
                result = SchematicScanner.scan(root, snapshot, this.mappingsSupplier.get());
            }
        }
        return new CachedScan(lastModifiedMillis(file), fileSize(file), fingerprint, decision, result);
    }

    private static CompoundTag readCompressed(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String cacheKey(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    private static boolean fileUnchanged(Path file, CachedScan cached) {
        try {
            return lastModifiedMillis(file) == cached.modifiedMillis() && fileSize(file) == cached.size();
        } catch (UncheckedIOException e) {
            return false;
        }
    }

    private static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
