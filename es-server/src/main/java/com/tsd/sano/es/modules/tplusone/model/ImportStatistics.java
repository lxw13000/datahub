package com.tsd.sano.es.modules.tplusone.model;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ES导入统计信息。
 *
 * <p>多个Reader/Bulk阶段共享该对象，计数字段使用AtomicLong保证并发更新安全。</p>
 *
 * @author lxw
 */
@Getter
@Setter
public class ImportStatistics {

    /**
     * 源端总数据量。
     */
    private final AtomicLong total = new AtomicLong();

    /**
     * 已读取数据量。
     */
    private final AtomicLong read = new AtomicLong();

    /**
     * 已成功写入ES的数据量。
     */
    private final AtomicLong success = new AtomicLong();

    /**
     * 写入ES失败的数据量。
     */
    private final AtomicLong failed = new AtomicLong();

    /**
     * 当前Bulk执行次数。
     */
    private final AtomicLong bulkCount = new AtomicLong();

    /**
     * 当前读取到的最后一条MySQL ID。
     */
    private volatile long lastId;

    /**
     * 最后连续完成批次的安全断点ID。
     *
     * <p>字段名为兼容现有任务索引保留为lastSuccessId，但它不能再按任意成功item的
     * 最大ID推进，只能由有序批次提交逻辑更新。</p>
     */
    private final AtomicLong lastSuccessId = new AtomicLong();

    /**
     * 首个无法安全提交的批次序号，0表示当前未发现阻塞批次。
     */
    private volatile long checkpointBlockedSequence;

    /**
     * 导入开始时间戳。
     */
    private volatile long startTime;

    /**
     * 导入结束时间戳。
     */
    private volatile long endTime;

    /**
     * 是否在完成已读取批次后暂停；deadline和部署drain都会设置该标记。
     */
    private volatile boolean timeoutPartial;

    /**
     * 本次任务停止继续分页的原因；与持久任务状态分开保存。
     */
    private volatile ImportStopReason stopReason = ImportStopReason.NONE;

    /**
     * stopReason为DRAIN时对应的排空操作ID，其他场景为空。
     */
    private volatile String stopOperationId;

    /**
     * 获取最后连续完成批次的安全断点ID。
     */
    public long getLastSuccessId() {
        return lastSuccessId.get();
    }

    /**
     * 设置最后连续完成批次的安全断点ID。
     */
    public void setLastSuccessId(long value) {
        lastSuccessId.set(value);
    }

}
