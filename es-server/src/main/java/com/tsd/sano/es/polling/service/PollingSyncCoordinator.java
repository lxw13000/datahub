package com.tsd.sano.es.polling.service;

import com.tsd.sano.es.importer.notify.ImportNotifyService;
import com.tsd.sano.es.importer.pipeline.EsIndexManager;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.polling.model.SyncCheckpoint;
import com.tsd.sano.es.reconcile.service.ReconcileStatisticsService;
import com.tsd.sano.es.sync.config.EsServiceModeManager;
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
public class PollingSyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PollingSyncCoordinator.class);

    /**
     * 物理索引名称中的业务日期格式。
     */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final EsServiceModeManager serviceModeManager;
    private final EsImportProperties properties;
    private final PollingJdbcReader jdbcReader;
    private final PollingBulkWriter bulkWriter;
    private final SyncCheckpointService checkpointService;
    private final EsIndexManager indexManager;
    private final ReconcileStatisticsService reconcileStatisticsService;
    private final ImportNotifyService notifyService;

    /**
     * 当前进程正在运行或正在退出的表；tableName在同一实例中只能出现一次。
     */
    private final ConcurrentMap<String, WorkerRuntime> activeWorkers = new ConcurrentHashMap<>();

    /**
     * 人工暂停过程中禁止重新启动的表，避免Worker退出与checkpoint置为PAUSED之间发生重启竞态。
     */
    private final Set<String> blockedTables = ConcurrentHashMap.newKeySet();

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
     * 启停及调度门禁。置为false后不再启动新Worker。
     */
    private volatile boolean running;

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
    public PollingSyncCoordinator(
            EsServiceModeManager serviceModeManager,
            EsImportProperties properties,
            PollingJdbcReader jdbcReader,
            PollingBulkWriter bulkWriter,
            SyncCheckpointService checkpointService,
            EsIndexManager indexManager,
            ReconcileStatisticsService reconcileStatisticsService,
            ImportNotifyService notifyService
    ) {
        this.serviceModeManager = serviceModeManager;
        this.properties = properties;
        this.jdbcReader = jdbcReader;
        this.bulkWriter = bulkWriter;
        this.checkpointService = checkpointService;
        this.indexManager = indexManager;
        this.reconcileStatisticsService = reconcileStatisticsService;
        this.notifyService = notifyService;
    }

    /**
     * Spring应用就绪后初始化Polling，不阻止query模式注册相同Bean。
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running || state == State.STARTING || state == State.RUNNING) {
            return;
        }

        List<EsImportProperties.TableConfig> pollingTables = properties.getPollingTables();
        if (!serviceModeManager.isSyncEnabled()) {
            state = State.DISABLED;
            lastError = null;
            log.info("===> ES-Polling coordinator disabled by server mode. mode={}",
                    serviceModeManager.currentMode());
            return;
        }
        if (!properties.getPolling().isEnabled() || pollingTables.isEmpty()) {
            state = State.DISABLED;
            lastError = null;
            log.info("===> ES-Polling coordinator disabled. enabled={}, tableCount={}",
                    properties.getPolling().isEnabled(), pollingTables.size());
            return;
        }

        state = State.STARTING;
        try {
            if (!checkpointService.exists()) {
                state = State.INITIALIZATION_FAILED;
                lastError = "Polling checkpoint index is missing: "
                        + SyncCheckpointService.CHECKPOINT_INDEX;
                log.error("===> ES-Polling coordinator initialization failed. error={}", lastError);
                return;
            }

            Instant now = Instant.now();
            for (EsImportProperties.TableConfig tableConfig : pollingTables) {
                checkpointService.initialize(tableConfig, now);
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

            running = true;
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
            running = false;
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
        if (!running || state != State.RUNNING) {
            return;
        }

        int availableSlots = properties.getPolling().getMaxActiveTables() - activeWorkers.size();
        if (availableSlots <= 0) {
            return;
        }

        for (EsImportProperties.TableConfig tableConfig : properties.getPollingTables()) {
            if (availableSlots <= 0 || !running || state != State.RUNNING) {
                return;
            }
            String tableName = tableConfig.getTableName();
            if (blockedTables.contains(tableName) || activeWorkers.containsKey(tableName)) {
                continue;
            }

            Optional<SyncCheckpoint> started = checkpointService.start(tableName, Instant.now());
            if (started.isEmpty()) {
                continue;
            }

            SyncCheckpoint checkpoint = started.get();
            try {
                // 当前日期物理索引必须在Worker读取前就绪；失败时持久暂停该表，避免反复启动。
                indexManager.preparePollingIndex(tableConfig, checkpoint.getSyncDate());
            } catch (RuntimeException error) {
                String message = errorMessage(error);
                try {
                    boolean paused = checkpointService.pauseOnError(
                            tableName,
                            checkpoint.getSyncDate(),
                            checkpoint.getLastId(),
                            message,
                            Instant.now()
                    );
                    if (paused) {
                        String indexName = tableConfig.getIndexAlias() + "_" + INDEX_DATE_FORMATTER.format(checkpoint.getSyncDate());
                        notifyService.notifyPollingStopped(
                                tableName,
                                indexName,
                                checkpoint.getSyncDate(),
                                checkpoint.getLastId(),
                                error
                        );
                    }
                } catch (RuntimeException persistenceError) {
                    log.error("===> ES-Polling index preparation failed and checkpoint pause failed. "
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
                    checkpointService,
                    indexManager,
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
                running,
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
        running = false;
        if (state == State.RUNNING || state == State.STARTING) {
            state = State.STOPPING;
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
                && (!drainRequested || state == State.STOPPED || state == State.DISABLED
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
        return checkpointService.pauseManually(tableName, Instant.now());
    }

    /**
     * 人工恢复一张表，并允许协调器重新启动对应Worker。
     */
    public synchronized boolean resumeTable(String tableName) {
        boolean resumed = checkpointService.resume(tableName, Instant.now());
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

        running = false;
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
                    log.warn("===> ES-Polling workers did not stop within drain timeout. activeTables={}",
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
        state = State.NOT_STARTED;
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
        NOT_STARTED,
        DISABLED,
        STARTING,
        RUNNING,
        INITIALIZATION_FAILED,
        STOPPING,
        STOPPED
    }

    /**
     * 状态接口和统一drain使用的协调器只读快照。
     */
    public record Snapshot(
            State state,
            boolean running,
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
