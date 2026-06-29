package com.tsd.sano.es.importer.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 导入统计
 *
 * 全局共享
 *
 * @author lxw
 */
public class ImportStatistics {

    /**
     * 总数量
     */
    private final AtomicLong total = new AtomicLong();

    /**
     * 已读取
     */
    private final AtomicLong read = new AtomicLong();

    /**
     * 已导入
     */
    private final AtomicLong success = new AtomicLong();

    /**
     * 导入失败
     */
    private final AtomicLong failed = new AtomicLong();

    /**
     * 当前Bulk次数
     */
    private final AtomicLong bulkCount = new AtomicLong();

    /**
     * 当前最后ID
     */
    private volatile long lastId;

    /**
     * 开始时间
     */
    private volatile long startTime;

    /**
     * 结束时间
     */
    private volatile long endTime;

    public AtomicLong getTotal() {
        return total;
    }

    public AtomicLong getRead() {
        return read;
    }

    public AtomicLong getSuccess() {
        return success;
    }

    public AtomicLong getFailed() {
        return failed;
    }

    public AtomicLong getBulkCount() {
        return bulkCount;
    }

    public long getLastId() {
        return lastId;
    }

    public void setLastId(long lastId) {
        this.lastId = lastId;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }
}