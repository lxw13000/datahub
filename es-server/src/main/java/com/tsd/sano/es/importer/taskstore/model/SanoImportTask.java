package com.tsd.sano.es.importer.taskstore.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * ES导入任务索引实体。
 *
 * <p>字段通过JsonProperty写入为下划线格式，保持ES文档字段与数据库字段命名规则一致。</p>
 *
 * @author lxw
 */
@Getter
@Setter
public class SanoImportTask {

    /**
     * 任务文档ID，不写入ES文档体，规则为 table_name + "_" + import_date。
     */
    @JsonIgnore
    private String taskId;

    /**
     * MySQL源表名。
     */
    @JsonProperty("table_name")
    private String tableName;

    /**
     * 业务查询使用的ES alias。
     */
    @JsonProperty("index_alias")
    private String indexAlias;

    /**
     * 本次任务写入的真实ES索引。
     */
    @JsonProperty("index_name")
    private String indexName;

    /**
     * 导入业务日期，格式yyyyMMdd。
     */
    @JsonProperty("import_date")
    private String importDate;

    /**
     * 当前任务状态。
     */
    @JsonProperty("status")
    private String status;

    /**
     * 最后连续完成批次的安全断点ID。
     *
     * <p>保留last_success_id字段名兼容已有任务索引，实际语义不是任意成功item的最大ID。</p>
     */
    @JsonProperty("last_success_id")
    private long lastSuccessId;

    /**
     * 本任务源端总数。
     */
    @JsonProperty("total_count")
    private long totalCount;

    /**
     * 已成功写入ES的数据量。
     */
    @JsonProperty("success_count")
    private long successCount;

    /**
     * 写入失败数据量。
     */
    @JsonProperty("failed_count")
    private long failedCount;

    /**
     * 任务执行次数。
     */
    @JsonProperty("run_count")
    private int runCount;

    /**
     * 最近一次失败原因。
     */
    @JsonProperty("last_error")
    private String lastError;

    /**
     * 最近一次开始时间。
     */
    @JsonProperty("started_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startedAt;

    /**
     * 完成时间。
     */
    @JsonProperty("finished_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime finishedAt;

    /**
     * 创建时间。
     */
    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 更新时间。
     */
    @JsonProperty("updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * 构建任务文档ID。
     *
     * <p>同一张表同一天只有一条任务记录，ES写入时使用该值作为_id。</p>
     */
    public String buildTaskId() {
        return tableName + "_" + importDate;
    }
}
