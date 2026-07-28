package com.tsd.sano.es.modules.polling.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Polling单表持久化恢复点。
 *
 * <p>每张MySQL源表固定保存一条文档，文档ID使用tableName，只记录业务进度、
 * 运行状态和生命周期时间。</p>
 */
@Getter
@Setter
public class SyncCheckpoint {

    /** MySQL源表名和同步表唯一标识。 */
    @JsonProperty("table_name")
    private String tableName;

    /** 聚合该表每日物理索引的ES业务Alias。 */
    @JsonProperty("index_alias")
    private String indexAlias;

    /** 当前持久恢复状态；PAUSED表不能自动启动Worker。 */
    @JsonProperty("status")
    private Status status = Status.RUNNING;

    /** 最近一次持久化时正在读取的业务日期。 */
    @JsonProperty("sync_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate syncDate;

    /** 最近一次持久化的MySQL查询进度游标。 */
    @JsonProperty("last_id")
    private long lastId;

    /** 最近一次导致表暂停的错误摘要。 */
    @JsonProperty("last_error")
    private String lastError;

    /** 最近一次启动或人工恢复时间。 */
    @JsonProperty("last_started_at")
    private Instant lastStartedAt;

    /** 最近一次优雅停止或错误暂停时间。 */
    @JsonProperty("last_stopped_at")
    private Instant lastStoppedAt;

    /** checkpoint文档最近更新时间。 */
    @JsonProperty("updated_at")
    private Instant updatedAt;

    /** Polling表是否允许协调器启动Worker并继续同步。 */
    public enum Status {
        RUNNING,
        PAUSED
    }
}
