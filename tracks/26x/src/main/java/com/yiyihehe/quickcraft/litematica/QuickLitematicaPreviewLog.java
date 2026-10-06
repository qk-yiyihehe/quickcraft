package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 开发者专用日志：任务跨线程关联，文件与开关同寿命；不拥有渲染和缓存任务。 */
public final class QuickLitematicaPreviewLog {
    public static final QuickLitematicaPreviewLog LOGGER = new QuickLitematicaPreviewLog();
    private static final String LOGGER_NAME = "QuickCraft/Preview3D";
    private static final Object FILE_LOCK = new Object();
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<DfuCall>> DFU_CALLS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final AtomicLong NEXT_TASK = new AtomicLong();
    private static final AtomicLong DFU_COUNT = new AtomicLong();
    private static final AtomicLong FILE_SEQUENCE = new AtomicLong();
    private static volatile long session;
    private static volatile Path logPath;
    private static FileAppender fileAppender;
    private static AsyncAppender asyncAppender;

    private QuickLitematicaPreviewLog() {}

    public static boolean enabled() {
        return logPath != null && QuickCraftConfigs.isLitematicaPreview3DLogEnabled();
    }

    public static Path logPath() { return logPath; }

    public static void setEnabled(boolean enabled) {
        synchronized (FILE_LOCK) {
            closeFile();
            if (!enabled) return;
            try {
                Path directory = FabricLoader.getInstance().getGameDir().resolve("logs");
                Files.createDirectories(directory);
                long fileId = FILE_SEQUENCE.incrementAndGet();
                Path path = directory.resolve("quickcraft-preview3d-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                        + "-" + fileId + ".log");
                LoggerContext context = (LoggerContext) LogManager.getContext(false);
                var configuration = context.getConfiguration();
                fileAppender = FileAppender.newBuilder().setName("QuickCraftPreview3DFile")
                        .withFileName(path.toString()).withAppend(false).withImmediateFlush(true)
                        .setLayout(PatternLayout.newBuilder().withCharset(StandardCharsets.UTF_8)
                                .withPattern("%d{HH:mm:ss.SSS} [%t/%level] %msg%n%throwable").build())
                        .setConfiguration(configuration).build();
                fileAppender.start();
                if (!fileAppender.isStarted() || !Files.isRegularFile(path)) {
                    throw new java.io.IOException("Log4j file appender did not open " + path);
                }
                configuration.addAppender(fileAppender);
                // 有界队列满时背压而不丢诊断；磁盘写入由 Log4j 专用线程处理。
                asyncAppender = AsyncAppender.newBuilder().setName("QuickCraftPreview3DAsync")
                        .setAppenderRefs(new AppenderRef[]{AppenderRef.createAppenderRef(fileAppender.getName(), null, null)})
                        .setBufferSize(4096).setBlocking(true).setConfiguration(configuration).build();
                asyncAppender.start();
                if (!asyncAppender.isStarted()) throw new java.io.IOException("Log4j async appender did not start");
                configuration.addAppender(asyncAppender);
                LoggerConfig loggerConfig = new LoggerConfig(LOGGER_NAME, Level.ALL, false);
                loggerConfig.addAppender(asyncAppender, Level.ALL, null);
                configuration.addLogger(LOGGER_NAME, loggerConfig);
                context.updateLoggers();
                session = fileId;
                logPath = path;
                LoggerFactory.getLogger("QuickCraft/Developer").info("3D 预览详细日志已开启：{}", path);
                LOGGER.info("日志会话开始：文件={}，缓存目录={}，Java={}，系统={}/{}，处理器={}，最大堆={} B",
                        path, FabricLoader.getInstance().getGameDir().resolve("litematica-preview-cache"),
                        System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                        Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory());
                FabricLoader.getInstance().getAllMods().stream()
                        .filter(mod -> java.util.Set.of("quickcraft", "minecraft", "fabricloader", "litematica", "malilib",
                                "fabric-api", "sodium", "iris", "continuity", "indium").contains(mod.getMetadata().getId()))
                        .forEach(mod -> LOGGER.info("运行依赖：模组={}，版本={}", mod.getMetadata().getId(),
                                mod.getMetadata().getVersion().getFriendlyString()));
                LOGGER.info("计时口径：单调时钟；阶段含排队/处理分项，重叠阶段不能相加；GPU 耗时仅指 CPU 调用，未强制 GPU 同步；首次 DFU 为 QC 首次观测，不保证冷启动");
            } catch (Exception e) {
                closeFile();
                LoggerFactory.getLogger("QuickCraft/Developer").error("无法创建 3D 开发者日志文件", e);
            }
        }
    }

    private static void closeFile() {
        Path previous = logPath;
        if (previous != null) LogManager.getLogger(LOGGER_NAME).info("日志会话结束：仅停止诊断，预览与缓存写入继续；之后发生的阶段不在本文件内");
        logPath = null;
        if (asyncAppender != null) {
            // stop 排空已经接收的记录；仅停止诊断，不触碰预览或后台保存。
            asyncAppender.stop();
            asyncAppender = null;
        }
        if (fileAppender != null) {
            fileAppender.stop();
            fileAppender = null;
        }
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        var configuration = context.getConfiguration();
        configuration.removeLogger(LOGGER_NAME);
        configuration.getAppenders().remove("QuickCraftPreview3DAsync");
        configuration.getAppenders().remove("QuickCraftPreview3DFile");
        context.updateLoggers();
        if (previous != null) LoggerFactory.getLogger("QuickCraft/Developer").info("3D 预览详细日志已关闭：{}", previous);
    }

    public void info(String message, Object... arguments) { record(Level.INFO, message, arguments); }
    public void debug(String message, Object... arguments) { record(Level.DEBUG, message, arguments); }
    public void warn(String message, Object... arguments) { record(Level.WARN, message, arguments); }
    public void error(String message, Object... arguments) { record(Level.ERROR, message, arguments); }

    private void record(Level level, String message, Object[] arguments) {
        if (!enabled()) return;
        long diagnosticStart = System.nanoTime();
        synchronized (FILE_LOCK) {
            if (!enabled()) return;
            Trace trace = CURRENT.get();
            String prefix = "[3D日志#" + session + "] ";
            if (trace != null) {
                trace.attach();
                prefix += "[任务#" + trace.id + "][事件#" + trace.events.incrementAndGet()
                        + "][+" + (System.nanoTime() - trace.createdAt) / 1_000_000L + "ms] ";
                if (arguments.length > 0 && arguments[arguments.length - 1] instanceof Throwable failure) {
                    String key = message + ":" + failure.getClass().getName() + ":" + failure.getMessage();
                    long count = trace.errors.merge(key, 1L, Long::sum);
                    if (count > 1) {
                        arguments = arguments.clone();
                        arguments[arguments.length - 1] = "同类异常累计=" + count + "，" + failure;
                        message += "，异常摘要={}";
                    }
                }
            }
            LogManager.getLogger(LOGGER_NAME).log(level, prefix + message, arguments);
            if (trace != null) trace.diagnosticNanos.addAndGet(System.nanoTime() - diagnosticStart);
        }
    }

    public static Trace current() { return CURRENT.get(); }
    public static Phase phase(String name) { return enabled() ? new Phase(name) : Phase.NONE; }
    public static long startTimer() { return enabled() ? System.nanoTime() : 0L; }
    public static long microsSince(long start) { return start == 0L ? -1L : (System.nanoTime() - start) / 1_000L; }
    static QuickLitematicaPreview3D.ProgressSink progressSink(String name) {
        if (!enabled()) return value -> {};
        int[] lastPercent = {-1};
        return value -> {
            int percent = Math.round(value * 100.0F);
            if (percent != lastPercent[0]) {
                lastPercent[0] = percent;
                LOGGER.info("工作进度：阶段={}，进度={}%，此值不是存盘完成标志", name, percent);
            }
        };
    }
    public static void file(String action, Path path) {
        if (!enabled()) return;
        try { LOGGER.info("文件{}：路径={}，存在={}，大小={} B", action, path, Files.exists(path), Files.isRegularFile(path) ? Files.size(path) : -1L); }
        catch (Exception e) { LOGGER.warn("文件属性读取失败：路径={}", path, e); }
    }

    public static final class Trace {
        final long id = NEXT_TASK.incrementAndGet();
        final Path source;
        final long createdAt;
        final AtomicLong events = new AtomicLong();
        final AtomicLong diagnosticNanos = new AtomicLong();
        final Map<String, Long> phases = new ConcurrentHashMap<>();
        final Map<String, Long> errors = new HashMap<>();
        final Map<String, Long> milestones = new HashMap<>();
        private long attachedSession;
        private long frameWindowStart;
        private long frameCount;
        private long frameNanos;
        private long maxFrameNanos;

        public Trace(Path source, long createdAt) { this.source = source; this.createdAt = createdAt; }

        private void attach() {
            if (attachedSession == session) return;
            attachedSession = session;
            milestones.clear();
            phases.clear(); errors.clear(); diagnosticNanos.set(0L);
            frameWindowStart = 0L; frameCount = 0L; frameNanos = 0L; maxFrameNanos = 0L;
            LogManager.getLogger(LOGGER_NAME).info("[3D日志#{}][任务#{}] 任务关联：源={}，创建至今={} ms，前段计时仅包含日志实际启用期间",
                    session, id, source, (System.nanoTime() - createdAt) / 1_000_000L);
        }

        public Scope bind() { return new Scope(this); }
        public Runnable wrap(Runnable work) { return () -> { try (Scope ignored = bind()) { work.run(); } }; }
        public Runnable wrap(String phaseName, Runnable work) {
            return wrap(() -> { try (Phase ignored = phase(phaseName)) { work.run(); } });
        }
        public <T> Consumer<T> wrapConsumer(Consumer<T> callback) {
            return value -> { try (Scope ignored = bind()) { callback.accept(value); } };
        }
        public void frame(boolean complete, Runnable draw) {
            if (!enabled()) { draw.run(); return; }
            try (Scope ignored = bind()) {
                long start = System.nanoTime();
                try {
                    draw.run();
                    milestone(complete ? "首次静态上传完成后的绘制调用完成" : "首次部分静态绘制调用完成");
                } catch (RuntimeException | Error e) {
                    LOGGER.error("绘制失败：静态上传完成={}", complete, e);
                    throw e;
                } finally {
                    long elapsed = System.nanoTime() - start;
                    frameCount++; frameNanos += elapsed; maxFrameNanos = Math.max(maxFrameNanos, elapsed);
                    if (frameWindowStart == 0L) frameWindowStart = start;
                    if (System.nanoTime() - frameWindowStart >= 2_000_000_000L) {
                        LOGGER.info("绘制窗口统计：帧数={}，CPU 调用平均/最大={}/{} us，静态上传完成={}",
                                frameCount, frameNanos / frameCount / 1_000L, maxFrameNanos / 1_000L, complete);
                        frameWindowStart = System.nanoTime(); frameCount = 0L; frameNanos = 0L; maxFrameNanos = 0L;
                    }
                }
            }
        }
        public void event(String message, Object... args) { if (enabled()) try (Scope ignored = bind()) { LOGGER.info(message, args); } }
        public void milestone(String name, Object... args) {
            if (!enabled()) return;
            try (Scope ignored = bind()) {
                synchronized (FILE_LOCK) {
                    attach();
                    if (milestones.putIfAbsent(name, System.nanoTime() - createdAt) == null) LOGGER.info(name, args);
                }
            }
        }
        public void summary(String reason) {
            if (!enabled()) return;
            try (Scope ignored = bind()) {
                synchronized (FILE_LOCK) {
                    attach();
                    LOGGER.info("任务阶段汇总：原因={}，创建至今={} ms，日志入口处理/入队={} us（不含参数准备），阶段累计(us)={}，异常统计={}，里程碑距创建时间(ns)={}",
                            reason, (System.nanoTime() - createdAt) / 1_000_000L, diagnosticNanos.get() / 1_000L,
                            new LinkedHashMap<>(phases), new LinkedHashMap<>(errors), new LinkedHashMap<>(milestones));
                }
            }
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Trace previous;
        private final int dfuDepth;
        private Scope(Trace trace) {
            previous = CURRENT.get();
            dfuDepth = DFU_CALLS.get().size();
            CURRENT.set(trace);
        }
        @Override public void close() {
            // 嵌套回调只清理自己未返回的 DFU，不能将失败归到恢复后的外层投影。
            ArrayDeque<DfuCall> calls = DFU_CALLS.get();
            while (calls.size() > dfuDepth) {
                DfuCall call = calls.pop();
                LOGGER.warn("DFU 未正常返回：类型={}，调用#{}，已等待={} us", call.type, call.number, microsSince(call.started));
            }
            if (previous == null) {
                DFU_CALLS.remove();
                CURRENT.remove();
            } else CURRENT.set(previous);
        }
    }

    public static final class Phase implements AutoCloseable {
        static final Phase NONE = new Phase();
        private final String name;
        private final long started;
        private final Trace owner;
        private final long observedSession;
        private Phase() { name = ""; started = 0L; owner = null; observedSession = 0L; }
        private Phase(String name) {
            this.name = name; owner = current();
            observedSession = session;
            LOGGER.info("阶段开始：{}", name);
            started = System.nanoTime();
        }
        @Override public void close() {
            if (started == 0L) return;
            if (!enabled() || observedSession != session) return;
            long elapsed = System.nanoTime() - started;
            if (owner != null) owner.phases.merge(name, elapsed / 1_000L, Long::sum);
            LOGGER.info("阶段结束：{}，耗时={} us（异常结果另见失败事件）", name, elapsed / 1_000L);
        }
    }

    public static void beginDfu(String type, int from, int to) {
        if (!enabled() || current() == null) return;
        long number = DFU_COUNT.incrementAndGet();
        LOGGER.info("DFU 实际调用开始：类型={}，版本={}->{}，调用#{}，首次观测={}，需要升级={}", type, from, to, number, number == 1L, from < to);
        DFU_CALLS.get().push(new DfuCall(type, number, System.nanoTime()));
    }

    public static void endDfu() {
        if (current() == null) return;
        ArrayDeque<DfuCall> calls = DFU_CALLS.get();
        if (calls.isEmpty()) return;
        DfuCall call = calls.pop();
        long micros = microsSince(call.started);
        if (current() != null) current().phases.merge("DFU:" + call.type, micros, Long::sum);
        LOGGER.info("DFU 实际调用完成：类型={}，调用#{}，耗时={} us", call.type, call.number, micros);
    }
    private record DfuCall(String type, long number, long started) {}

    /** 区域内只累计每种模型的处理量，退出区域后输出；避免逐方块 I/O 干扰计时。 */
    static final class RegionStats implements AutoCloseable {
        final String region;
        final long started = System.nanoTime();
        final Map<String, long[]> models = new LinkedHashMap<>();
        RegionStats(String region, long volume) { this.region = region; LOGGER.info("区域开始：名称={}，体积={}", region, volume); }
        void record(String state, long entity, long fluid, long model) {
            long[] totals = models.computeIfAbsent(state, ignored -> new long[5]);
            totals[0]++; totals[1] += entity; totals[2] += fluid; totals[3] += model; totals[4] = Math.max(totals[4], model);
        }
        @Override public void close() {
            models.forEach((state, totals) -> LOGGER.info("区域模型统计：区域={}，状态={}，方块数={}，动态记录/流体/模型累计={}/{}/{} us，模型最大={} us",
                    region, state, totals[0], totals[1] / 1_000L, totals[2] / 1_000L, totals[3] / 1_000L, totals[4] / 1_000L));
            LOGGER.info("区域完成：名称={}，状态种类={}，耗时={} us", region, models.size(), microsSince(started));
        }
    }
}
