package com.tsd.sano.es.modules.polling.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 单表Polling循环的低频汇总日志。
 *
 * <p>每个Worker独立持有一个实例，按五分钟窗口累计MySQL读取、ES写入、游标推进和耗时。
 * 该类不参与任何同步业务状态判断；日志输出或内部统计异常时直接重置当前窗口，
 * 不能阻断MySQL查询、ES写入、日期推进或优雅停止。</p>
 */
public final class PollingLogSummary {

    private static final Logger log = LoggerFactory.getLogger(PollingLogSummary.class);

    /** 正常运行时输出一条汇总日志的固定时间窗口。 */
    private static final long SUMMARY_INTERVAL_NANOS = Duration.ofMinutes(5).toNanos();

    /** 当前Worker对应的MySQL源表名。 */
    private final String tableName;

    /** 当前汇总窗口的业务日期。 */
    private LocalDate summaryDate;

    /** 使用单调时钟记录窗口起点，避免系统时间调整影响五分钟判断。 */
    private long windowStartedNanos;

    /** 当前窗口第一轮查询前的游标。 */
    private long startLastId;

    /** 当前窗口最后一轮完成后的游标。 */
    private long endLastId;

    /** 当前窗口总循环次数，包含空查询。 */
    private long cycles;

    /** ES整批写入成功的非空批次数。 */
    private long successBatches;

    /** MySQL查询结果为空的循环次数。 */
    private long emptyCycles;

    /** MySQL查询返回的记录总数。 */
    private long mysqlRows;

    /** ES整批写入成功的记录总数。 */
    private long esSuccessRows;

    /** ES重试耗尽后继续推进游标的记录总数。 */
    private long esFailedRows;

    /** ES重试耗尽的批次数。 */
    private long bulkFailedBatches;

    /** 执行过ES Bulk的非空批次数，用于计算ES平均耗时。 */
    private long bulkBatches;

    /** 当前窗口MySQL查询累计耗时。 */
    private long mysqlTotalCostMs;

    /** 当前窗口最慢MySQL查询耗时。 */
    private long mysqlMaxCostMs;

    /** 当前窗口ES Bulk累计耗时，包含内部重试等待。 */
    private long esTotalCostMs;

    /** 当前窗口最慢ES Bulk耗时。 */
    private long esMaxCostMs;

    /** 当前窗口完整循环累计耗时。 */
    private long cycleTotalCostMs;

    /**
     * 创建单表日志汇总器。
     *
     * @param tableName     MySQL源表名
     * @param syncDate      Worker当前业务日期
     * @param initialLastId Worker当前内存游标
     */
    public PollingLogSummary(String tableName, LocalDate syncDate, long initialLastId) {
        this.tableName = tableName;
        reset(syncDate, initialLastId);
    }

    /**
     * 累计一轮Polling结果，并在跨日期或达到五分钟窗口时输出汇总。
     *
     * <p>同步日期由Worker每轮传入。日期变化时先尝试输出旧日期剩余窗口，无论日志是否
     * 成功都切换到新日期，避免日志故障造成跨日统计混合或反复打印。</p>
     *
     * @param syncDate      本轮业务日期
     * @param rowCount      MySQL本轮返回数量
     * @param previousLastId 本轮查询前游标
     * @param nextLastId    本轮完成后游标
     * @param mysqlCostMs   MySQL查询耗时
     * @param esCostMs      ES写入及重试耗时，空批次为0
     * @param totalCostMs   本轮完整耗时
     * @param bulkSuccessful 非空批次是否完整写入ES
     */
    public void recordCycle(LocalDate syncDate, int rowCount,
                            long previousLastId, long nextLastId,
                            long mysqlCostMs, long esCostMs, long totalCostMs,
                            boolean bulkSuccessful) {
        try {
            if (!Objects.equals(summaryDate, syncDate)) {
                flushAndReset("DATE_CHANGED", syncDate, previousLastId);
            }

            int safeRowCount = Math.max(0, rowCount);
            long safeMysqlCostMs = Math.max(0L, mysqlCostMs);
            long safeEsCostMs = Math.max(0L, esCostMs);
            long safeTotalCostMs = Math.max(0L, totalCostMs);

            cycles++;
            endLastId = nextLastId;
            mysqlRows += safeRowCount;
            mysqlTotalCostMs += safeMysqlCostMs;
            mysqlMaxCostMs = Math.max(mysqlMaxCostMs, safeMysqlCostMs);
            cycleTotalCostMs += safeTotalCostMs;

            if (safeRowCount == 0) {
                emptyCycles++;
            } else {
                bulkBatches++;
                esTotalCostMs += safeEsCostMs;
                esMaxCostMs = Math.max(esMaxCostMs, safeEsCostMs);
                if (bulkSuccessful) {
                    successBatches++;
                    esSuccessRows += safeRowCount;
                } else {
                    bulkFailedBatches++;
                    esFailedRows += safeRowCount;
                }
            }

            if (System.nanoTime() - windowStartedNanos >= SUMMARY_INTERVAL_NANOS) {
                flushAndReset("INTERVAL", syncDate, nextLastId);
            }
        } catch (RuntimeException ignored) {
            // 汇总日志属于旁路能力；任意统计或日志异常都丢弃当前窗口，绝不能影响同步主流程。
            reset(syncDate, nextLastId);
        }
    }

    /**
     * 在跨天、暂停、drain或Worker退出前强制输出剩余窗口。
     *
     * @param reason 输出原因
     */
    public void flush(String reason) {
        try {
            flushAndReset(reason, summaryDate, endLastId);
        } catch (RuntimeException ignored) {
            // 防御未来内部实现变化；即使汇总器异常，对调用方仍保持无返回值和无异常语义。
            reset(summaryDate, endLastId);
        }
    }

    /**
     * 尝试输出当前窗口，并无条件切换到指定的新窗口。
     */
    private void flushAndReset(String reason, LocalDate nextDate, long nextStartLastId) {
        try {
            if (cycles == 0L) {
                return;
            }

            long windowSeconds = Math.max(1L,
                    (System.nanoTime() - windowStartedNanos) / 1_000_000_000L);
            long mysqlAvgMs = mysqlTotalCostMs / cycles;
            long esAvgMs = bulkBatches == 0L ? 0L : esTotalCostMs / bulkBatches;
            long totalAvgMs = cycleTotalCostMs / cycles;
            long successRowsPerSecond = esSuccessRows / windowSeconds;
            String normalizedReason = reason == null || reason.isBlank()
                    ? "UNKNOWN"
                    : reason;

            String message = "===> ES-Polling summary. reason={}, table={}, date={}, "
                    + "windowSeconds={}, cycles={}, successBatches={}, emptyCycles={}, "
                    + "mysqlRows={}, esSuccessRows={}, esFailedRows={}, bulkFailedBatches={}, "
                    + "startLastId={}, endLastId={}, mysqlAvgMs={}, mysqlMaxMs={}, "
                    + "esAvgMs={}, esMaxMs={}, totalAvgMs={}, successRowsPerSecond={}";
            Object[] arguments = {
                    normalizedReason, tableName, summaryDate,
                    windowSeconds, cycles, successBatches, emptyCycles,
                    mysqlRows, esSuccessRows, esFailedRows, bulkFailedBatches,
                    startLastId, endLastId, mysqlAvgMs, mysqlMaxCostMs,
                    esAvgMs, esMaxCostMs, totalAvgMs, successRowsPerSecond
            };
            if (esFailedRows > 0L) {
                log.warn(message, arguments);
            } else {
                log.info(message, arguments);
            }
        } catch (RuntimeException ignored) {
            // Logger或Appender异常允许丢失本窗口日志，不能向Worker传播。
        } finally {
            // 打印成功、失败或没有数据都必须推进窗口，避免旧日期和旧统计反复累积。
            reset(nextDate, nextStartLastId);
        }
    }

    /**
     * 清空累计值并初始化下一统计窗口。
     */
    private void reset(LocalDate syncDate, long initialLastId) {
        summaryDate = syncDate;
        windowStartedNanos = System.nanoTime();
        startLastId = initialLastId;
        endLastId = initialLastId;
        cycles = 0L;
        successBatches = 0L;
        emptyCycles = 0L;
        mysqlRows = 0L;
        esSuccessRows = 0L;
        esFailedRows = 0L;
        bulkFailedBatches = 0L;
        bulkBatches = 0L;
        mysqlTotalCostMs = 0L;
        mysqlMaxCostMs = 0L;
        esTotalCostMs = 0L;
        esMaxCostMs = 0L;
        cycleTotalCostMs = 0L;
    }
}
