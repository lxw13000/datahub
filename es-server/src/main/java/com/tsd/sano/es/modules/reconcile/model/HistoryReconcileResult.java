package com.tsd.sano.es.modules.reconcile.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 人工历史范围对账结果，仅用于独立日志和通知，不保存对账任务或反向修改导入结果。
 *
 * @param operationId 对应人工历史导入的操作ID
 * @param tableName MySQL源表名
 * @param startDate 起始业务日期，包含
 * @param endDate 结束业务日期，包含
 * @param indexNames 请求范围覆盖的物理月/年索引，可能包含源数据为空而未创建的索引
 * @param status 复用既有统计对账结果状态
 * @param mysqlCount MySQL整个日期范围的总数；null表示尚未取得
 * @param elasticsearchCount ES相同日期范围的总数；null表示尚未取得
 * @param error 执行失败原因，成功或不匹配时为空
 */
public record HistoryReconcileResult(
        String operationId,
        String tableName,
        LocalDate startDate,
        LocalDate endDate,
        List<String> indexNames,
        ReconcileResult.Status status,
        Long mysqlCount,
        Long elasticsearchCount,
        String error
) {
}
