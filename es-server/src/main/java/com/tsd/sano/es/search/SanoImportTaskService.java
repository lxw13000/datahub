package com.tsd.sano.es.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.importer.util.MappingLoader;
import com.tsd.sano.es.search.model.SanoImportTask;
import com.tsd.sano.es.search.model.SanoImportTaskStatus;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
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

    private final ElasticsearchClient client;
    private final MappingLoader mappingLoader;

    /**
     * 注入ES客户端和Mapping加载器。
     */
    public SanoImportTaskService(ElasticsearchClient client, MappingLoader mappingLoader) {
        this.client = client;
        this.mappingLoader = mappingLoader;
    }

    /**
     * 主动创建任务索引。
     *
     * <p>该方法应由初始化接口或部署后人工调用，不在新增、更新、查询任务时自动触发。</p>
     *
     * @return true表示索引已存在或创建成功
     */
    public boolean createIndex() {
        try {
            if (exists()) {
                log.info("===> ES-Import task index already exists. index={}", TASK_INDEX);
                throw new ServiceException("ES-Import task index already exists.");
            }

            try (InputStream mapping = mappingLoader.load(TASK_MAPPING_FILE)) {
                // Mapping文件同时包含settings和mappings，直接作为create index请求体。
                CreateIndexRequest request = new CreateIndexRequest.Builder()
                        .index(TASK_INDEX)
                        .withJson(mapping)
                        .build();

                CreateIndexResponse response = client.indices().create(request);
                boolean acknowledged = response.acknowledged();
                log.info("===> ES-Import create task index result. index={}, acknowledged={}", TASK_INDEX, acknowledged);
                return acknowledged;
            }
        } catch (IOException | ElasticsearchException e) {
            throw new ServiceException("ES import create task index failed, index=" + TASK_INDEX
                    + ", error=" + e.getMessage(), e);
        }
    }

    /**
     * 判断任务索引是否存在。
     *
     * @return true表示已存在，false表示不存在
     */
    public boolean exists() {
        try {
            // 仅供初始化接口或人工检查使用，普通任务读写不依赖该方法。
            BooleanResponse response = client.indices().exists(request -> request.index(TASK_INDEX));
            return response.value();
        } catch (IOException | ElasticsearchException e) {
            throw new ServiceException("ES import task index exists check failed, index=" + TASK_INDEX
                    + ", error=" + e.getMessage(), e);
        }
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
            IndexResponse response = client.index(request -> request
                    .index(TASK_INDEX)
                    .id(task.getTaskId())
                    .opType(OpType.Create)
                    .refresh(Refresh.WaitFor)
                    .document(task));

            log.info("===> ES-Import task created. taskId={}, result={}", task.getTaskId(), response.result());
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 409) {
                log.info("===> ES-Import task already exists. taskId={}", task.getTaskId());
                return false;
            }
            throw new ServiceException("ES import add task failed, taskId=" + task.getTaskId()
                    + ", error=" + e.getMessage(), e);
        } catch (IOException e) {
            throw new ServiceException("ES import add task failed, taskId=" + task.getTaskId()
                    + ", error=" + e.getMessage(), e);
        }
    }

    /**
     * 更新导入任务。
     *
     * <p>只更新已有任务，不做upsert，避免错误任务ID静默创建新文档。</p>
     *
     * @param task 导入任务
     * @return true表示更新成功
     */
    public boolean updateTask(SanoImportTask task) {
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

            log.info("===> ES-Import task updated. taskId={}, result={}", task.getTaskId(), response.result());
            return true;
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
