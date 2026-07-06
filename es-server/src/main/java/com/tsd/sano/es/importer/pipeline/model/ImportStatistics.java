package com.tsd.sano.es.importer.pipeline.model;

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
     * 已确认成功写入ES的最大MySQL ID。
     */
    private final AtomicLong lastSuccessId = new AtomicLong();

    /**
     * 导入开始时间戳。
     */
    private volatile long startTime;

    /**
     * 导入结束时间戳。
     */
    private volatile long endTime;

    /**
     * 是否因为到达deadline而暂停。
     */
    private volatile boolean timeoutPartial;

    /**
     * 获取已确认成功写入ES的最大MySQL ID。
     */
    public long getLastSuccessId() {
        return lastSuccessId.get();
    }

    /**
     * 设置已确认成功写入ES的最大MySQL ID。
     */
    public void setLastSuccessId(long value) {
        lastSuccessId.set(value);
    }

    /**
     * 只向前推进成功写入断点，避免并发Bulk完成顺序不同导致回退。
     */
    public void updateLastSuccessId(long value) {
        lastSuccessId.accumulateAndGet(value, Math::max);
    }
}
