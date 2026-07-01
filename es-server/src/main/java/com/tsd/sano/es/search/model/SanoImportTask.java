package com.tsd.sano.es.search.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * ES导入任务索引实体。
 *
 * <p>字段通过JsonProperty写入为下划线格式，保持ES文档字段与数据库字段命名规则一致。</p>
 *
 * @author lxw
 */
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
     * 已确认成功写入ES的最大MySQL ID。
     */
    @JsonProperty("last_success_id")
    private long lastSuccessId;

    /**
     * 本任务源端总数。
     */
    @JsonProperty("total_count")
    private long totalCount;

    /**
     * 已成功写入ES的数量。
     */
    @JsonProperty("success_count")
    private long successCount;

    /**
     * 写入失败数量。
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

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getIndexAlias() {
        return indexAlias;
    }

    public void setIndexAlias(String indexAlias) {
        this.indexAlias = indexAlias;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getImportDate() {
        return importDate;
    }

    public void setImportDate(String importDate) {
        this.importDate = importDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getLastSuccessId() {
        return lastSuccessId;
    }

    public void setLastSuccessId(long lastSuccessId) {
        this.lastSuccessId = lastSuccessId;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(long totalCount) {
        this.totalCount = totalCount;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(long successCount) {
        this.successCount = successCount;
    }

    public long getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(long failedCount) {
        this.failedCount = failedCount;
    }

    public int getRunCount() {
        return runCount;
    }

    public void setRunCount(int runCount) {
        this.runCount = runCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 构建任务文档ID。
     *
     * <p>同一张表同一天只有一条任务记录，ES写入时使用该值作为_id。</p>
     */
    public String buildTaskId() {
        return tableName + "_" + importDate;
    }
}
