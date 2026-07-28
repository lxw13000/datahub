package com.tsd.sano.es.modules.polling.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.json.JsonData;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.SyncTableConfig;
import com.tsd.sano.es.modules.index.EsIndexManager;
import com.tsd.sano.es.modules.polling.model.SyncCheckpoint;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Polling索引和checkpoint持久化服务。
 *
 * <p>该服务完整管理Polling每日业务索引、历史索引清理和checkpoint文档。每张源表固定使用
 * tableName作为checkpoint文档ID；内部索引仍只允许通过人工初始化接口创建。</p>
 */
@Service
public class PollingIndexService {

    private static final Logger log = LoggerFactory.getLogger(PollingIndexService.class);

    /**
     * Polling checkpoint内部索引名称。
     */
    public static final String CHECKPOINT_INDEX = "sano_sync_polling_checkpoint";

    /**
     * Polling checkpoint索引Mapping文件。
     */
    private static final String CHECKPOINT_MAPPING_FILE = "sano_sync_polling_checkpoint.json";

    /**
     * Polling每日物理索引名称中的日期格式。
     */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * checkpoint允许运行的持久状态值。
     */
    private static final String RUNNING = SyncCheckpoint.Status.RUNNING.name();

    /**
     * checkpoint人工或异常暂停的持久状态值。
     */
    private static final String PAUSED = SyncCheckpoint.Status.PAUSED.name();

    /**
     * checkpoint文档读写使用的Elasticsearch客户端。
     */
    private final ElasticsearchClient client;

    /**
     * 每日业务索引和内部索引使用的通用远程操作。
     */
    private final EsIndexManager indexManager;

    /**
     * 注入ES客户端和通用索引操作服务。
     */
    public PollingIndexService(ElasticsearchClient client, EsIndexManager indexManager) {
        this.client = client;
        this.indexManager = indexManager;
    }

    /**
     * 主动创建Polling checkpoint内部索引
     *
     * <p>该方法只供初始化接口人工调用；索引已存在时明确报错，避免误以为重新应用了Mapping</p>
     *
     * @return true表示创建请求已确认
     */
    public boolean createCheckpointIndex() {
        if (checkpointIndexExists()) {
            log.info("===> ES-Polling checkpoint index already exists. index={}", CHECKPOINT_INDEX);
            throw new ServiceException("ES polling checkpoint index already exists.");
        }
        // 创建失败会抛出异常
        indexManager.createIndex(CHECKPOINT_INDEX, CHECKPOINT_MAPPING_FILE);

        log.info("===> ES-Polling checkpoint index created. index={}", CHECKPOINT_INDEX);
        return true;
    }

    /**
     * 判断Polling checkpoint内部索引是否已经由初始化接口创建
     *
     * <p>普通checkpoint读写不会隐式创建索引；协调器启动前通过该方法显式确认部署前置条件</p>
     *
     * @return true表示内部索引存在
     */
    public boolean checkpointIndexExists() {
        return indexManager.exists(CHECKPOINT_INDEX);
    }

    /**
     * 查询指定源表的checkpoint
     *
     * @param tableName MySQL源表名
     * @return checkpoint存在时返回持久业务状态和恢复进度
     */
    public Optional<SyncCheckpoint> find(String tableName) {
        String documentId = requireText(tableName, "tableName");
        try {
            GetRequest request = new GetRequest.Builder()
                    .index(CHECKPOINT_INDEX)
                    .id(documentId)
                    .build();
            GetResponse<SyncCheckpoint> response = client.get(request, SyncCheckpoint.class);
            if (!response.found() || response.source() == null) {
                return Optional.empty();
            }
            return Optional.of(response.source());
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES polling checkpoint query failed, tableName=" + documentId
                    + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * checkpoint缺失时按表配置创建，已存在时校验不可漂移的Alias配置。
     *
     * <p>该方法只确保单表初始持久状态存在，不向后续流程传递可能过期的checkpoint。
     * 最新运行进度由Worker启动前的原子更新获取。内部索引缺失必须通过部署初始化接口处理。</p>
     *
     * @param tableConfig Polling表配置
     * @param now         当前时间
     */
    public void initialize(SyncTableConfig tableConfig, Instant now) {
        String tableName = tableConfig.getTableName();
        String indexAlias = tableConfig.getIndexAlias();
        requireNow(now);

        Optional<SyncCheckpoint> existing = find(tableName);
        if (existing.isPresent()) {
            SyncCheckpoint existingCheckpoint = existing.get();
            if (!StringUtils.equals(existingCheckpoint.getIndexAlias(), indexAlias)) {
                throw new ServiceException("ES polling checkpoint indexAlias mismatch, tableName=" + tableName
                        + ", checkpointAlias=" + existingCheckpoint.getIndexAlias()
                        + ", configuredAlias=" + indexAlias);
            }
            return;
        }

        SyncCheckpoint checkpoint = new SyncCheckpoint();
        checkpoint.setTableName(tableName);
        checkpoint.setIndexAlias(indexAlias);
        checkpoint.setStatus(SyncCheckpoint.Status.RUNNING);
        checkpoint.setSyncDate(tableConfig.getBootstrapStartDate());
        checkpoint.setLastId(0L);
        checkpoint.setUpdatedAt(now);

        try {
            IndexRequest<SyncCheckpoint> request = new IndexRequest.Builder<SyncCheckpoint>()
                    .index(CHECKPOINT_INDEX)
                    .id(tableName)
                    .opType(OpType.Create)
                    .document(checkpoint)
                    .build();
            client.index(request);
            log.info("===> ES-Polling checkpoint initialized. table={}, date={}",
                    tableName, checkpoint.getSyncDate());
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES polling checkpoint initialization failed, tableName=" + tableName
                    + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 标记一张RUNNING表开始执行并返回最新checkpoint
     *
     * @return 表仍允许运行时返回最新checkpoint，PAUSED时返回empty
     */
    public Optional<SyncCheckpoint> start(String tableName, Instant now) {
        String documentId = requireText(tableName, "tableName");
        requireNow(now);
        UpdateResponse<SyncCheckpoint> response = executeUpdate(documentId, script(START_SCRIPT, Map.of(
                "running", RUNNING,
                "now", now.toString()
        )));
        if (response.result() == Result.NoOp) {
            return Optional.empty();
        }
        SyncCheckpoint checkpoint = response.get() == null ? null : response.get().source();
        if (checkpoint == null) {
            throw new ServiceException("ES polling started without checkpoint source, tableName=" + documentId);
        }
        return Optional.of(checkpoint);
    }

    /**
     * 原子把预期日期推进到下一日并将持久游标重置为0
     *
     * @return 更新成功时返回新checkpoint；状态或预期日期不匹配时返回empty
     */
    public Optional<SyncCheckpoint> advanceDate(
            String tableName,
            LocalDate expectedDate,
            LocalDate nextDate,
            Instant now
    ) {
        String documentId = requireText(tableName, "tableName");
        requireNow(now);
        if (expectedDate == null || nextDate == null || !nextDate.equals(expectedDate.plusDays(1))) {
            throw new ServiceException("ES polling nextDate must equal expectedDate plus one day, tableName="
                    + documentId);
        }

        UpdateResponse<SyncCheckpoint> response = executeUpdate(documentId, script(
                ADVANCE_DATE_SCRIPT,
                Map.of(
                        "running", RUNNING,
                        "expected_date", expectedDate.toString(),
                        "next_date", nextDate.toString(),
                        "now", now.toString()
                )
        ));
        if (response.result() == Result.NoOp) {
            return Optional.empty();
        }
        SyncCheckpoint checkpoint = response.get() == null ? null : response.get().source();
        if (checkpoint == null) {
            throw new ServiceException("ES polling date advanced without checkpoint source, tableName="
                    + documentId);
        }
        return Optional.of(checkpoint);
    }

    /**
     * 保存当前查询进度并将表持久暂停
     *
     * @return true表示暂停成功，false表示表已经不是RUNNING
     */
    public boolean pauseOnError(String tableName, LocalDate syncDate, long lastId,
                                String errorMessage, Instant now) {
        String documentId = requireText(tableName, "tableName");
        requireProgress(syncDate, lastId);
        requireNow(now);
        String lastError = StringUtils.abbreviate(
                StringUtils.defaultIfBlank(errorMessage, "Unknown polling synchronization error"),
                1000
        );

        return executeUpdate(documentId, script(PAUSE_SCRIPT, Map.of(
                "running", RUNNING,
                "paused", PAUSED,
                "sync_date", syncDate.toString(),
                "last_id", lastId,
                "last_error", lastError,
                "now", now.toString()
        ))).result() != Result.NoOp;
    }

    /**
     * 优雅停止时保存当前查询进度，持久状态保持RUNNING
     *
     * @return true表示保存成功，false表示表已经不是RUNNING
     */
    public boolean stopGracefully(String tableName, LocalDate syncDate, long lastId, Instant now) {
        String documentId = requireText(tableName, "tableName");
        requireProgress(syncDate, lastId);
        requireNow(now);

        return executeUpdate(documentId, script(STOP_SCRIPT, Map.of(
                "running", RUNNING,
                "sync_date", syncDate.toString(),
                "last_id", lastId,
                "now", now.toString()
        ))).result() != Result.NoOp;
    }

    /**
     * 人工恢复一张PAUSED表
     *
     * <p>恢复只清除暂停状态和错误，协调器在后续扫描中重新启动该表Worker</p>
     *
     * @return true表示恢复成功，false表示该表当前不是PAUSED
     */
    public boolean resume(String tableName, Instant now) {
        String documentId = requireText(tableName, "tableName");
        requireNow(now);
        return executeUpdate(documentId, script(RESUME_SCRIPT, Map.of(
                "paused", PAUSED,
                "running", RUNNING,
                "now", now.toString()
        ))).result() != Result.NoOp;
    }

    /**
     * 人工暂停一张RUNNING表
     *
     * <p>接口应先等待本机Worker保存查询进度并退出，再更新持久暂停状态</p>
     *
     * @return true表示暂停成功，false表示该表当前不是RUNNING
     */
    public boolean pauseManually(String tableName, Instant now) {
        String documentId = requireText(tableName, "tableName");
        requireNow(now);
        return executeUpdate(documentId, script(MANUAL_PAUSE_SCRIPT, Map.of(
                "running", RUNNING,
                "paused", PAUSED,
                "reason", "Paused manually",
                "now", now.toString()
        ))).result() != Result.NoOp;
    }

    /**
     * 幂等准备指定业务日期的Polling物理索引。
     *
     * <p>Polling索引创建时一次性绑定表级Alias；索引已存在说明此前已经完成创建和绑定，
     * 直接返回即可。</p>
     *
     * @return 准备完成的物理索引名
     */
    public String prepareIndex(SyncTableConfig tableConfig, LocalDate syncDate) {
        if (tableConfig == null) {
            throw new ServiceException("ES polling table config cannot be null");
        }
        if (syncDate == null) {
            throw new ServiceException("ES polling syncDate cannot be null");
        }

        String alias = requireText(tableConfig.getIndexAlias(), "indexAlias");
        String mappingFile = requireText(tableConfig.getMappingFile(), "mappingFile");
        String indexName = alias + "_" + INDEX_DATE_FORMATTER.format(syncDate);
        if (indexManager.exists(indexName)) {
            return indexName;
        }
        // 创建失败会抛出异常
        indexManager.createIndex(indexName, mappingFile, alias);
        log.info("===> ES-Polling index created. index={}, alias={}", indexName, alias);
        return indexName;
    }

    /**
     * 按已完成日期异步删除一个刚超过保留期的Polling历史物理索引。
     *
     * <p>删除是最佳努力任务；索引不存在或删除失败都不影响checkpoint推进和后续日期同步。</p>
     */
    @Async("esReconcileExecutor")
    public void deleteHistoryIndex(SyncTableConfig tableConfig, LocalDate completedDate) {
        if (tableConfig == null || completedDate == null || !tableConfig.isDeleteHistoryIndex()) {
            return;
        }

        try {
            String alias = requireText(tableConfig.getIndexAlias(), "indexAlias");
            int reserveDays = Math.max(1, tableConfig.getReserveDays());
            String expiredIndex = alias + "_"
                    + INDEX_DATE_FORMATTER.format(completedDate.minusDays(reserveDays));
            if (indexManager.exists(expiredIndex)) {
                indexManager.deleteIndex(expiredIndex);
            }
        } catch (RuntimeException error) {
            log.warn("===> ES-Polling delete history index failed. alias={}, date={}, error={}",
                    tableConfig.getIndexAlias(), completedDate, error.getMessage(), error);
        }
    }

    /**
     * 执行需要返回最新_source的原子Update
     */
    private UpdateResponse<SyncCheckpoint> executeUpdate(String documentId, Script script) {
        try {
            UpdateRequest<SyncCheckpoint, SyncCheckpoint> request =
                    new UpdateRequest.Builder<SyncCheckpoint, SyncCheckpoint>()
                            .index(CHECKPOINT_INDEX)
                            .id(documentId)
                            .script(script)
                            .source(source -> source.fetch(true))
                            .retryOnConflict(3)
                            .build();
            return client.update(request, SyncCheckpoint.class);
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES polling checkpoint atomic update failed, tableName=" + documentId
                    + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 构造Painless脚本并统一转换参数
     */
    private Script script(String source, Map<String, Object> parameters) {
        Script.Builder builder = new Script.Builder()
                .lang("painless")
                .source(source);
        parameters.forEach((name, value) -> builder.params(name, JsonData.of(value)));
        return builder.build();
    }

    /**
     * 校验并规范化Polling索引和checkpoint使用的必填文本。
     */
    private String requireText(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("ES polling " + field + " cannot be blank");
        }
        return value.trim();
    }

    /**
     * 校验持久状态更新时间。
     */
    private void requireNow(Instant now) {
        if (now == null) {
            throw new ServiceException("ES polling current time cannot be null");
        }
    }

    /**
     * 校验准备持久化的业务日期和恢复游标。
     */
    private void requireProgress(LocalDate syncDate, long lastId) {
        if (syncDate == null) {
            throw new ServiceException("ES polling syncDate cannot be null");
        }
        if (lastId < 0L) {
            throw new ServiceException("ES polling lastId cannot be negative");
        }
    }


    /**
     * Worker启动时确认表仍允许运行，并记录本次启动时间
     */
    private static final String START_SCRIPT = """
            if (ctx._source.status != params.running) {
                ctx.op = 'noop';
                return;
            }
            ctx._source.last_started_at = params.now;
            ctx._source.updated_at = params.now;
            """;

    /**
     * 仅在状态为RUNNING且仍是预期日期时原子推进到下一日
     */
    private static final String ADVANCE_DATE_SCRIPT = """
            if (ctx._source.status != params.running
                    || ctx._source.sync_date != params.expected_date) {
                ctx.op = 'noop';
                return;
            }
            ctx._source.sync_date = params.next_date;
            ctx._source.last_id = 0L;
            ctx._source.updated_at = params.now;
            """;

    /**
     * 系统性错误在一个原子更新中保存当前查询进度并持久暂停
     */
    private static final String PAUSE_SCRIPT = """
            if (ctx._source.status != params.running) {
                ctx.op = 'noop';
                return;
            }
            ctx._source.sync_date = params.sync_date;
            ctx._source.last_id = params.last_id;
            ctx._source.status = params.paused;
            ctx._source.last_error = params.last_error;
            ctx._source.last_stopped_at = params.now;
            ctx._source.updated_at = params.now;
            """;

    /**
     * 优雅停止时保存当前查询进度，业务状态保持RUNNING
     */
    private static final String STOP_SCRIPT = """
            if (ctx._source.status != params.running) {
                ctx.op = 'noop';
                return;
            }
            ctx._source.sync_date = params.sync_date;
            ctx._source.last_id = params.last_id;
            ctx._source.last_stopped_at = params.now;
            ctx._source.updated_at = params.now;
            """;

    /**
     * 人工恢复只改变持久业务状态，由协调器后续重新启动Worker
     */
    private static final String RESUME_SCRIPT = """
            if (ctx._source.status != params.paused) {
                ctx.op = 'noop';
                return;
            }
            ctx._source.status = params.running;
            ctx._source.last_error = null;
            ctx._source.last_started_at = params.now;
            ctx._source.updated_at = params.now;
            """;

    /**
     * 人工暂停只改变持久业务状态；接口调用前应先停止本机Worker
     */
    private static final String MANUAL_PAUSE_SCRIPT = """
            if (ctx._source.status != params.running) {
                ctx.op = 'noop';
                return;
            }
            ctx._source.status = params.paused;
            ctx._source.last_error = params.reason;
            ctx._source.last_stopped_at = params.now;
            ctx._source.updated_at = params.now;
            """;

}
