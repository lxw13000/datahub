package com.tsd.sano.es.polling.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.util.MappingLoader;
import com.tsd.sano.es.polling.model.SyncCheckpoint;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.client.ResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Polling单表checkpoint及其内部索引服务
 *
 * <p>每张源表固定使用tableName作为文档ID，只保存可恢复业务进度和运行状态
 * 当前部署只允许一个all实例，同一进程再由协调器保证一表一个Worker，因此checkpoint
 * 只承担业务恢复职责内部索引仅通过人工初始化接口创建，普通读写和应用启动不会
 * 隐式修改ES索引结构</p>
 */
@Service
public class SyncCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(SyncCheckpointService.class);

    // Polling checkpoint内部索引名称
    public static final String CHECKPOINT_INDEX = "sano_sync_polling_checkpoint";

    // Polling checkpoint索引Mapping文件
    private static final String CHECKPOINT_MAPPING_FILE = "sano_sync_polling_checkpoint.json";

    private static final String RUNNING = SyncCheckpoint.Status.RUNNING.name();
    private static final String PAUSED = SyncCheckpoint.Status.PAUSED.name();

    private final ElasticsearchClient client;
    private final MappingLoader mappingLoader;

    /**
     * 注入ES客户端和Mapping加载器
     */
    public SyncCheckpointService(ElasticsearchClient client, MappingLoader mappingLoader) {
        this.client = client;
        this.mappingLoader = mappingLoader;
    }

    /**
     * 主动创建Polling checkpoint内部索引
     *
     * <p>该方法只供初始化接口人工调用；索引已存在时明确报错，避免误以为重新应用了Mapping</p>
     *
     * @return true表示创建请求已确认
     */
    public boolean createIndex() {
        if (exists()) {
            log.info("===> ES-Polling checkpoint index already exists. index={}", CHECKPOINT_INDEX);
            throw new ServiceException("ES polling checkpoint index already exists.");
        }

        try (InputStream mapping = mappingLoader.load(CHECKPOINT_MAPPING_FILE)) {
            CreateIndexRequest request = new CreateIndexRequest.Builder()
                    .index(CHECKPOINT_INDEX)
                    .withJson(mapping)
                    .build();
            CreateIndexResponse response = client.indices().create(request);
            if (!response.acknowledged()) {
                throw new ServiceException(
                        "ES polling checkpoint index creation was not acknowledged.");
            }
            log.info("===> ES-Polling checkpoint index created. index={}", CHECKPOINT_INDEX);
            return true;
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES polling checkpoint index creation failed, index="
                    + CHECKPOINT_INDEX + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 判断Polling checkpoint内部索引是否已经由初始化接口创建
     *
     * <p>普通checkpoint读写不会隐式创建索引；协调器启动前通过该方法显式确认部署前置条件</p>
     *
     * @return true表示内部索引存在
     */
    public boolean exists() {
        try {
            ExistsRequest request = new ExistsRequest.Builder().index(CHECKPOINT_INDEX).build();
            BooleanResponse response = client.indices().exists(request);
            return response.value();
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES polling checkpoint index exists check failed, index="
                    + CHECKPOINT_INDEX + ", error=" + error.getMessage(), error);
        }
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
     * checkpoint缺失时按表配置幂等创建
     *
     * <p>create语义可以防止启动重试意外产生第二份文档；409时读取tableName对应的唯一文档
     * 该方法不会自动创建内部索引，索引缺失必须通过部署初始化接口处理</p>
     *
     * @param tableConfig Polling表配置
     * @param now         当前时间
     * @return 新建或已存在的唯一checkpoint
     */
    public SyncCheckpoint initialize(EsImportProperties.TableConfig tableConfig, Instant now) {
        String tableName = tableConfig.getTableName();
        String indexAlias = tableConfig.getIndexAlias();
        requireNow(now);

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
                    .refresh(Refresh.WaitFor)
                    .document(checkpoint)
                    .build();
            client.index(request);
            log.info("===> ES-Polling checkpoint initialized. table={}, date={}",
                    tableName, checkpoint.getSyncDate());
            return checkpoint;
        } catch (ElasticsearchException error) {
            if (error.status() == 409) {
                return requireExistingCheckpoint(tableName, indexAlias);
            }
            throw new ServiceException("ES polling checkpoint initialization failed, tableName=" + tableName
                    + ", error=" + error.getMessage(), error);
        } catch (IOException error) {
            if (error instanceof ResponseException responseException
                    && responseException.getResponse().getStatusLine().getStatusCode() == 409) {
                return requireExistingCheckpoint(tableName, indexAlias);
            }
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
     * 初始化冲突后读取唯一文档，并检查不可漂移的Alias配置
     */
    private SyncCheckpoint requireExistingCheckpoint(String tableName, String expectedAlias) {
        SyncCheckpoint checkpoint = find(tableName)
                .orElseThrow(() -> new ServiceException(
                        "ES polling checkpoint conflicted but existing document was not found, tableName="
                                + tableName));
        if (!StringUtils.equals(checkpoint.getIndexAlias(), expectedAlias)) {
            throw new ServiceException("ES polling checkpoint indexAlias mismatch, tableName=" + tableName
                    + ", checkpointAlias=" + checkpoint.getIndexAlias()
                    + ", configuredAlias=" + expectedAlias);
        }
        return checkpoint;
    }

    private String requireText(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("ES polling " + field + " cannot be blank");
        }
        return value.trim();
    }

    private void requireNow(Instant now) {
        if (now == null) {
            throw new ServiceException("ES polling current time cannot be null");
        }
    }

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
