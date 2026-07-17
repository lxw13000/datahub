package com.tsd.sano.es.importer.pipeline.model;

/**
 * T+1 Reader 在完成当前批次后停止继续分页的原因。
 *
 * <p>持久任务状态仍统一使用 TIMEOUT_PARTIAL；该枚举只区分运行窗口截止和部署排空，
 * 以便 drain/cancel 精确关联本次操作产生的可续跑任务。</p>
 */
public enum ImportStopReason {

    /** Reader未被提前停止。 */
    NONE,

    /** 本轮任务达到最大运行时长，等待后续调度续跑。 */
    DEADLINE,

    /** 部署排空要求Reader在安全批次边界停止。 */
    DRAIN
}
