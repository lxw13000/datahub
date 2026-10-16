package com.tsd.sano.es.modules.tplusone.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 人工历史导入的最终运行汇总，仅用于日志和通知，不写入任务索引，也不承担恢复职责。
 */
@Getter
@Setter
public class HistoryImportResult {

    /** 本次人工调用的唯一标识，用于关联提交响应、运行日志和通知。 */
    private String operationId;

    /** MySQL源表名。 */
    private String tableName;

    /** 请求开始日期，包含。 */
    private LocalDate startDate;

    /** 请求结束日期，包含。 */
    private LocalDate endDate;

    /** 当前准备或正在处理的日期；停止或失败时应从该日期重新提交。 */
    private LocalDate currentDate;

    /** 最近一个完整返回成功的日期；为空表示尚未完成任何一天。 */
    private LocalDate lastCompletedDate;

    /** 完整执行成功的日期数，包含源数据为空的日期。 */
    private long completedDays;

    /** 已正常返回的单日导入成功处理数，不包含抛异常时未返回的部分统计。 */
    private long successCount;

    /** 已正常返回的单日导入失败处理数，不代表最终缺失文档数。 */
    private long failedCount;

    /** 最终结果，执行过程中遇到停止或异常时覆盖默认成功值。 */
    private Status status = Status.SUCCESS;

    /** 本次后台运行总耗时，单位毫秒。 */
    private long costMs;

    /** 停止或失败原因；成功时为空。 */
    private String error;

    /** 人工历史导入的最终结果，不对应持久任务状态。 */
    public enum Status {
        /** 请求范围内所有日期均已完成原有导入成功收尾。 */
        SUCCESS,
        /** 因drain或线程中断停止，剩余日期需人工重新提交。 */
        STOPPED,
        /** 当前日期导入失败，后续日期不再执行，需人工复核并重新提交。 */
        FAILED
    }
}
