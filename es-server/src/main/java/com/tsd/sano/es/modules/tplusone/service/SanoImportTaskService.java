package com.tsd.sano.es.modules.tplusone.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.index.EsIndexManager;
import com.tsd.sano.es.modules.tplusone.model.SanoImportTask;
import com.tsd.sano.es.modules.tplusone.model.SanoImportTaskStatus;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.client.ResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ES导入任务索引服务。
 *
 * <p>任务索引由运维或初始化接口主动创建一次，日常任务读写不自动创建索引。</p>
 *
 * @author lxw
 */
@Service
public class SanoImportTaskService {

    private static final Logger log = LoggerFactory.getLogger(SanoImportTaskService.class);

    /**
     * 导入任务索引名称。
     */
    public static final String TASK_INDEX = "sano_import_task";

    /**
     * 任务索引Mapping文件。
     */
    private static final String TASK_MAPPING_FILE = "sano_import_task.json";

    /**
     * 原子创建或重置Polling历史修复任务。
     *
     * <p>PENDING、RUNNING和TIMEOUT_PARTIAL都表示任务仍可能被执行，不允许重复提交；
     * 已结束任务可以重置为PENDING，以便运维再次全量覆盖同一历史日期。</p>
     */
    private static final String RESET_REPAIR_TASK_SCRIPT = """
            if (ctx._source.status == params.pending
                    || ctx._source.status == params.running
                    || ctx._source.status == params.timeout_partial) {
                ctx.op = 'noop';
                return;
            }
            ctx._source.table_name = params.table_name;
            ctx._source.index_alias = params.index_alias;
            ctx._source.index_name = params.index_name;
            ctx._source.import_date = params.import_date;
            ctx._source.status = params.pending;
            ctx._source.last_success_id = 0L;
            ctx._source.total_count = 0L;
            ctx._source.success_count = 0L;
            ctx._source.failed_count = 0L;
            ctx._source.run_count = 0;
            ctx._source.last_error = null;
            ctx._source.started_at = null;
            ctx._source.finished_at = null;
            ctx._source.created_at = params.now;
            ctx._source.updated_at = params.now;
            """;

    private final ElasticsearchClient client;
    private final EsIndexManager indexManager;

    /**
     * 注入ES文档客户端和通用索引操作服务。
     */
    public SanoImportTaskService(ElasticsearchClient client, EsIndexManager indexManager) {
        this.client = client;
        this.indexManager = indexManager;
    }

    /**
     * 主动创建任务索引。
     *
     * <p>该方法应由初始化接口或部署后人工调用，不在新增、更新、查询任务时自动触发。</p>
     *
     */
    public void createIndex() {
        if (exists()) {
            log.info("===> ES-TPlusOne task index already exists. index={}", TASK_INDEX);
            throw new ServiceException("ES-Import task index already exists.");
        }
        indexManager.createIndex(TASK_INDEX, TASK_MAPPING_FILE);
        log.info("===> ES-TPlusOne task index created. index={}", TASK_INDEX);
    }

    /**
     * 判断任务索引是否存在。
     *
     * @return true表示已存在，false表示不存在
     */
    public boolean exists() {
        // 仅供初始化接口或人工检查使用，普通任务读写不依赖该方法。
        return indexManager.exists(TASK_INDEX);
    }

    /**
     * 新增导入任务。
     *
     * <p>使用ES create语义写入，同一 table_name + import_date 已存在时返回false。</p>
     *
     * @param task 导入任务
     * @return true表示新增成功，false表示任务已存在
     */
    public boolean addTask(SanoImportTask task) {
        requireTaskNotNull(task);
        LocalDateTime now = LocalDateTime.now();
        task.setTaskId(task.buildTaskId());
        if (StringUtils.isBlank(task.getStatus())) {
            // 新任务默认进入PENDING，等待调度器选择执行。
            task.setStatus(SanoImportTaskStatus.PENDING.name());
        }
        if (task.getCreatedAt() == null) {
            task.setCreatedAt(now);
        }
        task.setUpdatedAt(now);

        try {
            // create模式可以天然防止同一天同一张表重复生成任务。
            IndexRequest<SanoImportTask> request = new IndexRequest.Builder<SanoImportTask>()
                    .index(TASK_INDEX)
                    .id(task.getTaskId())
                    .opType(OpType.Create)
                    .refresh(Refresh.WaitFor)
                    .document(task)
                    .build();
            IndexResponse response = client.index(request);

            log.info("===> ES-TPlusOne task created. taskId={}, result={}", task.getTaskId(), response.result());
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 409) {
                log.info("===> ES-TPlusOne task already exists. taskId={}", task.getTaskId());
                return false;
            }
            throw new ServiceException("ES import add task failed, taskId=" + task.getTaskId()
                    + ", error=" + e.getMessage(), e);
        } catch (IOException e) {
            // Elasticsearch Java Client可能把HTTP 409作为低层ResponseException抛出。
            if (e instanceof ResponseException responseException
                    && responseException.getResponse().getStatusLine().getStatusCode() == 409) {
                log.info("===> ES-TPlusOne task already exists. taskId={}", task.getTaskId());
                return false;
            }
            throw new ServiceException("ES import add task failed, taskId=" + task.getTaskId()
                    + ", error=" + e.getMessage(), e);
        }
    }

    /**
     * 新增或重新提交一条Polling历史修复任务。
     *
     * <p>文档ID仍使用 {@code tableName_importDate}，不增加任务类型字段。ES Update在主分片上
     * 原子判断现有状态，避免两个接口请求同时把同表同日任务重置为可执行状态。</p>
     *
     * @param task 已通过Polling历史日期门禁的任务
     * @return true表示任务已创建或由终态重置，false表示已有未结束任务
     */
    public boolean addOrResetPollingRepairTask(SanoImportTask task) {
        requireTaskNotNull(task);
        LocalDateTime now = LocalDateTime.now();
        task.setTaskId(task.buildTaskId());
        task.setStatus(SanoImportTaskStatus.PENDING.name());
        task.setLastSuccessId(0L);
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailedCount(0L);
        task.setRunCount(0);
        task.setLastError(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        Script script = new Script.Builder()
                .lang("painless")
                .source(RESET_REPAIR_TASK_SCRIPT)
                .params("pending", JsonData.of(SanoImportTaskStatus.PENDING.name()))
                .params("running", JsonData.of(SanoImportTaskStatus.RUNNING.name()))
                .params("timeout_partial", JsonData.of(SanoImportTaskStatus.TIMEOUT_PARTIAL.name()))
                .params("table_name", JsonData.of(task.getTableName()))
                .params("index_alias", JsonData.of(task.getIndexAlias()))
                .params("index_name", JsonData.of(task.getIndexName()))
                .params("import_date", JsonData.of(task.getImportDate()))
                .params("now", JsonData.of(now.toString()))
                .build();
        try {
            UpdateRequest<SanoImportTask, SanoImportTask> request =
                    new UpdateRequest.Builder<SanoImportTask, SanoImportTask>()
                            .index(TASK_INDEX)
                            .id(task.getTaskId())
                            .refresh(Refresh.WaitFor)
                            .retryOnConflict(3)
                            .script(script)
                            .upsert(task)
                            .build();
            UpdateResponse<SanoImportTask> response = client.update(request, SanoImportTask.class);
            boolean accepted = response.result() != Result.NoOp;
            log.info("===> ES-TPlusOne polling repair task submit result. taskId={}, result={}",
                    task.getTaskId(), response.result());
            return accepted;
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES import submit polling repair task failed, taskId="
                    + task.getTaskId() + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 更新导入任务。
     *
     * <p>只更新已有任务，不做upsert，避免错误任务ID静默创建新文档。</p>
     *
     * @param task 导入任务
     */
    public void updateTask(SanoImportTask task) {
        requireTaskNotNull(task);
        if (StringUtils.isBlank(task.getTaskId())) {
            // 未显式传入taskId时，用tableName和importDate重新计算，便于调用方只维护业务字段。
            task.setTaskId(task.buildTaskId());
        }
        task.setUpdatedAt(LocalDateTime.now());

        try {
            UpdateResponse<SanoImportTask> response = client.update(request -> request
                    .index(TASK_INDEX)
                    .id(task.getTaskId())
                    .refresh(Refresh.WaitFor)
                    .doc(task), SanoImportTask.class);

            log.info("===> ES-TPlusOne task updated. taskId={}, result={}", task.getTaskId(), response.result());
        } catch (IOException | ElasticsearchException e) {
            throw new ServiceException("ES import update task failed, taskId=" + task.getTaskId()
                    + ", error=" + e.getMessage(), e);
        }
    }

    /**
     * 根据任务ID查询任务。
     *
     * @param taskId 任务文档ID
     * @return 任务存在时返回Optional，否则返回Optional.empty()
     */
    public Optional<SanoImportTask> getTask(String taskId) {
        try {
            GetResponse<SanoImportTask> response = client.get(request -> request
                    .index(TASK_INDEX)
                    .id(taskId), SanoImportTask.class);

            if (!response.found() || response.source() == null) {
                return Optional.empty();
            }

            SanoImportTask task = response.source();
            task.setTaskId(taskId);
            return Optional.of(task);
        } catch (IOException | ElasticsearchException e) {
            throw new ServiceException("ES import get task failed, taskId=" + taskId
                    + ", error=" + e.getMessage(), e);
        }
    }

    /**
     * 查询指定数量的待执行任务。
     *
     * @param limit 查询数量
     * @return 待执行任务列表
     */
    public List<SanoImportTask> listPendingTasks(int limit) {
        int size = Math.max(1, limit);
        List<FieldValue> statuses = List.of(
                FieldValue.of(SanoImportTaskStatus.PENDING.name()),
                FieldValue.of(SanoImportTaskStatus.TIMEOUT_PARTIAL.name())
        );

        try {
            SearchResponse<SanoImportTask> response = client.search(request -> request
                            .index(TASK_INDEX)
                            .size(size)
                            .query(query -> query
                                    .terms(terms -> terms
                                            .field("status")
                                            .terms(value -> value.value(statuses))))
                            .sort(sort -> sort.field(field -> field.field("import_date").order(SortOrder.Asc)))
                            .sort(sort -> sort.field(field -> field.field("created_at").order(SortOrder.Asc))),
                    SanoImportTask.class);

            return response.hits().hits().stream()
                    .map(this::toTask)
                    .toList();
        } catch (IOException | ElasticsearchException e) {
            throw new ServiceException("ES import list task failed, error=" + e.getMessage(), e);
        }
    }

    /**
     * 查询执行中的任务，用于调度前修复异常残留RUNNING状态。
     *
     * @param limit 查询数量
     * @return 执行中的任务列表
     */
    public List<SanoImportTask> listRunningTasks(int limit) {
        int size = Math.max(1, limit);
        try {
            SearchResponse<SanoImportTask> response = client.search(request -> request
                            .index(TASK_INDEX)
                            .size(size)
                            .query(query -> query
                                    .term(term -> term
                                            .field("status")
                                            .value(SanoImportTaskStatus.RUNNING.name())))
                            .sort(sort -> sort.field(field -> field.field("updated_at").order(SortOrder.Asc))),
                    SanoImportTask.class);

            return response.hits().hits().stream()
                    .map(this::toTask)
                    .toList();
        } catch (IOException | ElasticsearchException e) {
            throw new ServiceException("ES import list running task failed, error=" + e.getMessage(), e);
        }
    }

    /**
     * 将搜索命中转换为任务实体，并补全文档ID。
     */
    private SanoImportTask toTask(Hit<SanoImportTask> hit) {
        SanoImportTask task = hit.source();
        if (task == null) {
            throw new ServiceException("ES import task source cannot be null, taskId=" + hit.id());
        }
        task.setTaskId(hit.id());
        return task;
    }

    /**
     * 校验任务对象不为空。
     */
    private void requireTaskNotNull(SanoImportTask task) {
        if (task == null) {
            throw new ServiceException("ES import task cannot be null");
        }
    }

}
