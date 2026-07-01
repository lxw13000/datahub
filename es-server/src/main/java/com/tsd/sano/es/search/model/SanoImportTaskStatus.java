package com.tsd.sano.es.search.model;

/**
 * ES导入任务状态。
 *
 * <p>状态值会直接写入 sano_import_task 索引，变更时需要兼容历史任务数据。</p>
 *
 * @author lxw
 */
public enum SanoImportTaskStatus {

    /**
     * 待执行。
     */
    PENDING,

    /**
     * 执行中。
     */
    RUNNING,

    /**
     * 超时暂停，可继续从断点续跑。
     */
    TIMEOUT_PARTIAL,

    /**
     * 完整完成，已切换业务alias。
     */
    SUCCESS,

    /**
     * 执行失败。
     */
    FAILED,

    /**
     * 人工取消。
     */
    CANCELLED
}
