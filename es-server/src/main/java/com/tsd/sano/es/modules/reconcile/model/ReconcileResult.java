package com.tsd.sano.es.modules.reconcile.model;

import java.time.LocalDate;

/**
 * 单表单日统计对账结果。
 *
 * <p>对账只比较总量、最小ID和最大ID，不保存任务状态，也不反向修改任何同步checkpoint。</p>
 */
public record ReconcileResult(
        String tableName,
        String indexName,
        LocalDate reconcileDate,
        Status status,
        Statistics mysql,
        Statistics elasticsearch,
        String error
) {

    /**
     * 创建成功完成的匹配或不匹配结果。
     */
    public static ReconcileResult completed(
            String tableName,
            String indexName,
            LocalDate reconcileDate,
            Statistics mysql,
            Statistics elasticsearch
    ) {
        Status status = mysql.equals(elasticsearch) ? Status.MATCHED : Status.MIS_MATCHED;
        return new ReconcileResult(
                tableName, indexName, reconcileDate, status, mysql, elasticsearch, null);
    }

    /**
     * 创建MySQL当日无数据、无需继续查询ES的结果。
     */
    public static ReconcileResult mysqlEmpty(
            String tableName,
            String indexName,
            LocalDate reconcileDate,
            Statistics mysql
    ) {
        return new ReconcileResult(
                tableName, indexName, reconcileDate, Status.MYSQL_EMPTY, mysql, null, null);
    }

    /**
     * 创建执行失败结果；已取得的单侧统计允许保留用于排查。
     */
    public static ReconcileResult failed(
            String tableName,
            String indexName,
            LocalDate reconcileDate,
            Statistics mysql,
            Statistics elasticsearch,
            String error
    ) {
        return new ReconcileResult(
                tableName, indexName, reconcileDate, Status.FAILED, mysql, elasticsearch, error);
    }

    /**
     * 对账结果状态。
     */
    public enum Status {

        /** MySQL与ES的总量、最小ID和最大ID全部一致。 */
        MATCHED,

        /** MySQL与ES至少有一项统计结果不一致。 */
        MIS_MATCHED,

        /** MySQL当日无源数据，已通知并跳过ES统计。 */
        MYSQL_EMPTY,

        /** MySQL或ES统计执行失败。 */
        FAILED
    }

    /**
     * 单侧总量、最小ID和最大ID统计。
     */
    public record Statistics(long count, Long minId, Long maxId) {
    }
}
