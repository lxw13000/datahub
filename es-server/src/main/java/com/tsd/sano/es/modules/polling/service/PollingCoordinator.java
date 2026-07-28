package com.tsd.sano.es.modules.polling.service;

import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.config.SyncTableConfig;
import com.tsd.sano.es.modules.polling.model.SyncCheckpoint;
import com.tsd.sano.es.modules.reconcile.service.ReconcileStatisticsService;
import com.tsd.sano.es.modules.config.EsServiceModeManager;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polling多表同步协调器。
 *
 * <p>当前部署只允许一个all实例。协调器在单个JVM内保证一张表最多运行一个Worker，
 * 并负责checkpoint初始化、Worker启停和统一drain；MySQL读取、ES写入、日期推进及
 * 错误暂停由单表Worker完成。</p>
 */
@Component
public class PollingCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PollingCoordinator.class);

    /**
     * 物理索引名称中的业务日期格式。
     */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /** 当前实例是否允许启动同步工作的运行模式门禁。 */
    private final EsServiceModeManager serviceModeManager;

    /** Polling开关、并发表数及表目录配置。 */
    private final EsImportProperties properties;

    /** 单表同步使用的MySQL批次读取器。 */
    private final PollingJdbcReader jdbcReader;

    /** 单表同步使用的ES整批写入器。 */
    private final PollingBulkWriter bulkWriter;

    /** Polling日期索引和checkpoint持久化服务。 */
    private final PollingIndexService pollingIndexService;

    /** 日期关闭后的异步统计对账入口。 */
    private final ReconcileStatisticsService reconcileStatisticsService;

    /** Polling系统性错误的业务通知入口。 */
    private final PollingNotifyService notifyService;

    /**
     * 当前进程正在运行或正在退出的表；tableName在同一实例中只能出现一次。
     */
    private final ConcurrentMap<String, WorkerRuntime> activeWorkers = new ConcurrentHashMap<>();

    /**
     * 人工暂停过程中禁止重新启动的表，避免Worker退出与checkpoint置为PAUSED之间发生重启竞态。
     */
    private final Set<String> blockedTables = ConcurrentHashMap.newKeySet();

    /**
     * 启动阶段checkpoint初始化失败、等待后续调度周期独立重试的表。
     *
     * <p>单表失败不能中止协调器或阻塞其他表；初始化恢复后立即移除并进入正常Worker启动流程。</p>
     */
    private final Set<String> pendingInitializationTables = ConcurrentHashMap.newKeySet();

    /**
     * 本轮drain已经退出的Worker最终快照。
     *
     * <p>Worker退出后会从activeWorkers移除，因此必须保留最终checkpoint保存结果，
     * 供统一drain判断是否可以安全停止旧实例。</p>
     */
    private final ConcurrentMap<String, PollingTableWorker.Snapshot> drainedWorkers =
            new ConcurrentHashMap<>();

    /**
     * 协调器线程编号，仅用于生成便于定位日志的线程名称。
     */
    private final AtomicInteger coordinatorThreadNumber = new AtomicInteger();

    /**
     * Worker线程编号，仅用于生成便于定位日志的线程名称。
     */
    private final AtomicInteger workerThreadNumber = new AtomicInteger();

    /**
     * 当前是否由部署drain要求停止；与应用永久关闭分开处理。
     */
    private volatile boolean drainRequested;

    /**
     * drain取消后是否应在全部旧Worker退出时重新启动协调器。
     */
    private volatile boolean restartAfterDrain;

    /**
     * Spring容器是否正在永久关闭，关闭期间禁止因drain取消重新启动。
     */
    private volatile boolean applicationStopping;

    /**
     * 当前协调器生命周期状态。
     */
    private volatile State state = State.NOT_STARTED;

    /**
     * 最近一次初始化或Worker启动周期错误摘要。
     */
    private volatile String lastError;

    /**
     * 定期发现尚未运行的RUNNING表并启动Worker。
     */
    private ScheduledExecutorService scheduler;

    /**
     * 单表Worker执行器；线程上限等于当前实例最多并行的表数。
     */
    private ExecutorService workerExecutor;

    /**
     * 注入Polling运行需要的同步组件。
     */
    public PollingCoordinator(
            EsServiceModeManager serviceModeManager,
            EsImportProperties properties,
            PollingJdbcReader jdbcReader,
            PollingBulkWriter bulkWriter,
            PollingIndexService pollingIndexService,
            ReconcileStatisticsService reconcileStatisticsService,
            PollingNotifyService notifyService
    ) {
        this.serviceModeManager = serviceModeManager;
        this.properties = properties;
        this.jdbcReader = jdbcReader;
        this.bulkWriter = bulkWriter;
        this.pollingIndexService = pollingIndexService;
        this.reconcileStatisticsService = reconcileStatisticsService;
        this.notifyService = notifyService;
    }

    /**
     * Spring应用就绪后初始化Polling，不阻止query模式注册相同Bean。
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        // Polling协调器在query模式下不启动，但仍允许注册Bean，避免与query模式的Bean冲突。
        if (!serviceModeManager.isSyncEnabled()) {
            state = State.DISABLED;
            lastError = null;
            log.info("===> ES-Polling coordinator disabled by server mode. mode={}",
                    serviceModeManager.currentMode());
            return;
        }
        // 生命周期状态是协调器是否可重复启动的唯一判断依据。
        if (state == State.STARTING || state == State.RUNNING || state == State.STOPPING) {
            return;
        }

        List<SyncTableConfig> pollingTables = properties.getPollingTables();
        // 未启用Polling或没有Polling表时明确进入DISABLED，不创建任何调度线程。
        if (!properties.getPolling().isEnabled() || pollingTables.isEmpty()) {
            state = State.DISABLED;
            lastError = null;
            log.info("===> ES-Polling coordinator disabled. enabled={}, tableCount={}",
                    properties.getPolling().isEnabled(), pollingTables.size());
            return;
        }

        state = State.STARTING;
        try {
            // 内部索引属于全部Polling表的共同前置条件，缺失时无法隔离为单表错误。
            if (!pollingIndexService.checkpointIndexExists()) {
                state = State.INITIALIZATION_FAILED;
                lastError = "Polling checkpoint index is missing: "
                        + PollingIndexService.CHECKPOINT_INDEX;
                log.error("===> ES-Polling coordinator initialization failed. error={}", lastError);
                return;
            }

            Instant now = Instant.now();
            for (SyncTableConfig tableConfig : pollingTables) {
                String tableName = tableConfig.getTableName();
                try {
                    pollingIndexService.initialize(tableConfig, now);
                    pendingInitializationTables.remove(tableName);
                } catch (RuntimeException error) {
                    // checkpoint读取、创建或单表配置漂移只影响当前表，其余表继续完成启动。
                    pendingInitializationTables.add(tableName);
                    log.warn("===> ES-Polling checkpoint initialization failed, retry later. "
                                    + "table={}, error={}",
                            tableName, error.getMessage(), error);
                }
            }

            int maxActiveTables = properties.getPolling().getMaxActiveTables();
            if (maxActiveTables <= 0) {
                throw new IllegalStateException("sano.import.polling.max-active-tables must be positive");
            }

            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable,
                        "es-polling-coordinator-" + coordinatorThreadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            workerExecutor = Executors.newFixedThreadPool(maxActiveTables, runnable -> {
                Thread thread = new Thread(runnable,
                        "es-polling-worker-" + workerThreadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

            state = State.RUNNING;
            lastError = null;

            long scanIntervalMillis = Math.max(1L, properties.getPolling().getPollInterval().toMillis());
            scheduler.scheduleWithFixedDelay(
                    this::runWorkerStartCycleSafely,
                    0L,
                    scanIntervalMillis,
                    TimeUnit.MILLISECONDS
            );
            log.info("===> ES-Polling coordinator started. tableCount={}, maxActiveTables={}",
                    pollingTables.size(), maxActiveTables);
        } catch (RuntimeException error) {
            state = State.INITIALIZATION_FAILED;
            lastError = errorMessage(error);
            if (scheduler != null) {
                scheduler.shutdown();
            }
            if (workerExecutor != null) {
                workerExecutor.shutdown();
            }
            log.error("===> ES-Polling coordinator initialization failed. error={}",
                    lastError, error);
        }
    }

    /**
     * 启动尚未在本实例运行的RUNNING表，直到达到表级并发上限。
     */
    private synchronized void runWorkerStartCycle() {
        if (state != State.RUNNING) {
            return;
        }

        int availableSlots = properties.getPolling().getMaxActiveTables() - activeWorkers.size();
        if (availableSlots <= 0) {
            return;
        }

        for (SyncTableConfig tableConfig : properties.getPollingTables()) {
            if (availableSlots <= 0 || state != State.RUNNING) {
                return;
            }
            String tableName = tableConfig.getTableName();
            if (blockedTables.contains(tableName) || activeWorkers.containsKey(tableName)) {
                continue;
            }

            if (pendingInitializationTables.contains(tableName)) {
                try {
                    pollingIndexService.initialize(tableConfig, Instant.now());
                    pendingInitializationTables.remove(tableName);
                    log.info("===> ES-Polling checkpoint initialization recovered. table={}", tableName);
                } catch (RuntimeException error) {
                    log.warn("===> ES-Polling checkpoint initialization retry failed. "
                                    + "table={}, error={}",
                            tableName, error.getMessage(), error);
                    continue;
                }
            }

            Optional<SyncCheckpoint> started;
            try {
                started = pollingIndexService.start(tableName, Instant.now());
            } catch (RuntimeException error) {
                // 单表checkpoint读取或原子更新失败不能中断本轮扫描，后续表仍需正常启动。
                log.warn("===> ES-Polling worker start failed, retry later. table={}, error={}",
                        tableName, error.getMessage(), error);
                continue;
            }
            if (started.isEmpty()) {
                continue;
            }

            SyncCheckpoint checkpoint = started.get();
            try {
                // 当前日期物理索引必须在Worker读取前就绪；失败时持久暂停该表，避免反复启动。
                pollingIndexService.prepareIndex(tableConfig, checkpoint.getSyncDate());
            } catch (RuntimeException error) {
                String message = errorMessage(error);
                try {
                    boolean paused = pollingIndexService.pauseOnError(
                            tableName,
                            checkpoint.getSyncDate(),
                            checkpoint.getLastId(),
                            message,
                            Instant.now()
                    );
                    if (paused) {
                        String indexName = tableConfig.getIndexAlias() + "_" + INDEX_DATE_FORMATTER.format(checkpoint.getSyncDate());
                        notifyService.notifyStopped(
                                tableName,
                                indexName,
                                checkpoint.getSyncDate(),
                                checkpoint.getLastId(),
                                error
                        );
                    }
                } catch (RuntimeException persistenceError) {
                    log.warn("===> ES-Polling index preparation failed and checkpoint pause failed. "
                                    + "table={}, error={}",
                            tableName, persistenceError.getMessage(), persistenceError);
                }
                continue;
            }

            PollingTableWorker worker = new PollingTableWorker(
                    tableConfig,
                    checkpoint,
                    properties,
                    jdbcReader,
                    bulkWriter,
                    pollingIndexService,
                    reconcileStatisticsService,
                    notifyService
            );
            WorkerRuntime runtime = new WorkerRuntime(worker);
            if (activeWorkers.putIfAbsent(tableName, runtime) != null) {
                continue;
            }

            try {
                workerExecutor.execute(() -> {
                    try {
                        worker.run();
                    } finally {
                        if (drainRequested) {
                            drainedWorkers.put(tableName, worker.snapshot());
                        }
                        activeWorkers.remove(tableName, runtime);
                        if (drainRequested && !applicationStopping) {
                            completeDrainOrRestart();
                        }
                    }
                });
                availableSlots--;
            } catch (RuntimeException error) {
                activeWorkers.remove(tableName, runtime);
                worker.requestStop();
                throw error;
            }
        }
    }

    /**
     * 返回协调器和当前表Worker的只读运行快照。
     */
    public Snapshot snapshot() {
        Map<String, PollingTableWorker.Snapshot> workers = new LinkedHashMap<>();
        activeWorkers.forEach((tableName, runtime) -> workers.put(tableName, runtime.worker.snapshot()));
        return new Snapshot(
                state,
                drainRequested,
                lastError,
                Map.copyOf(workers),
                Map.copyOf(drainedWorkers)
        );
    }

    /**
     * 请求所有本实例Polling Worker在当前SQL或Bulk完成后保存查询游标。
     *
     * <p>该方法不阻塞HTTP线程；统一drain通过 {@link #drainStatus()} 轮询最终结果。</p>
     */
    public synchronized DrainStatus requestDrain() {
        if (drainRequested) {
            return drainStatus();
        }

        drainRequested = true;
        restartAfterDrain = false;
        drainedWorkers.clear();
        if (state == State.RUNNING || state == State.STARTING) {
            state = State.STOPPING;
        } else if (state == State.NOT_STARTED) {
            state = State.STOPPED;
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdown();
        }
        activeWorkers.values().forEach(runtime -> runtime.worker.requestStop());
        completeDrainOrRestart();
        return drainStatus();
    }

    /**
     * 返回本轮Polling drain的Worker退出和checkpoint保存结果。
     */
    public synchronized DrainStatus drainStatus() {
        completeDrainOrRestart();
        Map<String, String> failedTables = new LinkedHashMap<>();
        drainedWorkers.forEach((tableName, worker) -> {
            if (!worker.checkpointSaved()) {
                failedTables.put(tableName, StringUtils.defaultIfBlank(
                        worker.lastError(), "Polling final checkpoint was not saved"));
            }
        });
        boolean stopped = activeWorkers.isEmpty()
                && (!drainRequested || state == State.NOT_STARTED || state == State.STOPPED
                || state == State.DISABLED
                || state == State.INITIALIZATION_FAILED);
        return new DrainStatus(
                drainRequested,
                stopped,
                stopped && failedTables.isEmpty(),
                Map.copyOf(failedTables),
                Map.copyOf(drainedWorkers)
        );
    }

    /**
     * 取消部署drain；已收到停止标记的Worker仍先退出，再由协调器重新启动。
     */
    public synchronized void resumeAfterDrainCancel() {
        if (!drainRequested || applicationStopping) {
            return;
        }
        restartAfterDrain = true;
        completeDrainOrRestart();
    }

    /**
     * 人工暂停一张表：先阻止重新启动并等待本机Worker保存进度，再持久化PAUSED。
     */
    public synchronized boolean pauseTable(String tableName) {
        blockedTables.add(tableName);
        WorkerRuntime runtime = activeWorkers.get(tableName);
        if (runtime != null) {
            runtime.worker.requestStop();
            long deadline = System.currentTimeMillis()
                    + Math.max(1, properties.getCommon().getDrainTimeoutSeconds()) * 1000L;
            while (activeWorkers.get(tableName) == runtime && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (activeWorkers.get(tableName) == runtime
                    || !runtime.worker.snapshot().checkpointSaved()) {
                return false;
            }
        }
        return pollingIndexService.pauseManually(tableName, Instant.now());
    }

    /**
     * 人工恢复一张表，并允许协调器重新启动对应Worker。
     */
    public synchronized boolean resumeTable(String tableName) {
        boolean resumed = pollingIndexService.resume(tableName, Instant.now());
        blockedTables.remove(tableName);
        runWorkerStartCycleSafely();
        return resumed;
    }

    /**
     * 应用退出时停止调度，并要求所有Worker保存各自的查询游标。
     */
    @PreDestroy
    public synchronized void stop() {
        applicationStopping = true;
        restartAfterDrain = false;
        if (state == State.STOPPED || state == State.NOT_STARTED) {
            state = State.STOPPED;
            return;
        }

        if (state != State.DISABLED && state != State.INITIALIZATION_FAILED) {
            state = State.STOPPING;
        }
        activeWorkers.values().forEach(runtime -> runtime.worker.requestStop());

        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                boolean stopped = workerExecutor.awaitTermination(
                        Math.max(1, properties.getCommon().getDrainTimeoutSeconds()),
                        TimeUnit.SECONDS
                );
                if (!stopped) {
                    log.error("===> ES-Polling workers did not stop within drain timeout. activeTables={}",
                            activeWorkers.keySet());
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                log.warn("===> ES-Polling coordinator stop wait interrupted.");
            }
        }
        state = State.STOPPED;
        log.info("===> ES-Polling coordinator stopped. remainingWorkers={}", activeWorkers.size());
    }

    /**
     * Worker退出后完成drain状态切换，并在cancel已请求时重新启动协调器。
     */
    private synchronized void completeDrainOrRestart() {
        if (!drainRequested || !activeWorkers.isEmpty()) {
            return;
        }
        if (state == State.STOPPING) {
            state = State.STOPPED;
        }
        if (!restartAfterDrain || applicationStopping) {
            return;
        }

        restartAfterDrain = false;
        drainRequested = false;
        drainedWorkers.clear();
        start();
    }

    /**
     * 隔离单次Worker启动周期异常，避免调度线程因一次异常永久取消后续扫描。
     */
    private void runWorkerStartCycleSafely() {
        try {
            runWorkerStartCycle();
        } catch (RuntimeException error) {
            lastError = "Polling worker start cycle failed: " + errorMessage(error);
            log.error("===> ES-Polling worker start cycle failed. error={}",
                    error.getMessage(), error);
        }
    }

    /**
     * 提取适合状态和日志展示的异常摘要。
     */
    private String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /**
     * 协调器生命周期状态；与checkpoint的RUNNING/PAUSED业务状态相互独立。
     */
    public enum State {

        /**
         * 协调器Bean已经创建，但尚未执行启动及持久checkpoint恢复。
         */
        NOT_STARTED,

        /**
         * 因query模式、Polling总开关关闭或没有启用表而不启动。
         */
        DISABLED,

        /**
         * 正在检查内部索引、读取或创建各表checkpoint，并初始化调度资源。
         */
        STARTING,

        /**
         * 初始化完成，正在调度并允许运行持久状态为RUNNING的单表Worker。
         */
        RUNNING,

        /**
         * checkpoint索引缺失，或启动及checkpoint恢复过程发生异常。
         */
        INITIALIZATION_FAILED,

        /**
         * 已停止启动新Worker，正在等待现有Worker完成当前操作、保存checkpoint并退出。
         */
        STOPPING,

        /**
         * 已完成停止；再次启动时需要重新读取持久checkpoint恢复允许运行的表。
         */
        STOPPED
    }

    /**
     * 状态接口和统一drain使用的协调器只读快照。
     */
    public record Snapshot(
            State state,
            boolean drainRequested,
            String lastError,
            Map<String, PollingTableWorker.Snapshot> workers,
            Map<String, PollingTableWorker.Snapshot> drainedWorkers
    ) {
    }

    /**
     * 统一drain使用的Polling最终结果；只包含Worker和checkpoint保存边界。
     */
    public record DrainStatus(
            boolean requested,
            boolean stopped,
            boolean safe,
            Map<String, String> failedTables,
            Map<String, PollingTableWorker.Snapshot> stoppedWorkers
    ) {
    }

    /**
     * 单表Worker的进程内运行态。
     */
    private static final class WorkerRuntime {

        private final PollingTableWorker worker;

        private WorkerRuntime(PollingTableWorker worker) {
            this.worker = worker;
        }
    }
}
