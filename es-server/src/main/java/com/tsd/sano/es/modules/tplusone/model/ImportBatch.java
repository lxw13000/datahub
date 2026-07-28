package com.tsd.sano.es.modules.tplusone.model;

import java.util.List;
import java.util.Map;

/**
 * Reader 投递给 Bulk Worker 的有序导入批次。
 *
 * <p>sequence 按 Reader 读取顺序单调递增，lastId 是该读取批次的最后一条
 * MySQL 主键。Bulk 可以乱序完成，但任务安全断点只能按 sequence 连续推进。</p>
 *
 * @param sequence  批次序号；结束信号固定为0
 * @param lastId    批次最后一条MySQL ID
 * @param rows      当前批次数据
 * @param endSignal 是否为Worker结束信号
 */
public record ImportBatch(long sequence,
                          long lastId,
                          List<Map<String, Object>> rows,
                          boolean endSignal) {

    private static final ImportBatch END_SIGNAL = new ImportBatch(0L, 0L, List.of(), true);

    /**
     * 创建普通数据批次。
     */
    public static ImportBatch data(long sequence, long lastId, List<Map<String, Object>> rows) {
        if (sequence <= 0L) {
            throw new IllegalArgumentException("ES import batch sequence must be positive");
        }
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("ES import data batch rows cannot be empty");
        }
        return new ImportBatch(sequence, lastId, rows, false);
    }

    /**
     * 返回共享的Worker结束信号，结束信号不参与checkpoint计算。
     */
    public static ImportBatch workerEndSignal() {
        return END_SIGNAL;
    }

    /**
     * 判断当前批次是否为Worker结束信号。
     */
    public boolean isEndSignal() {
        return endSignal;
    }
}
