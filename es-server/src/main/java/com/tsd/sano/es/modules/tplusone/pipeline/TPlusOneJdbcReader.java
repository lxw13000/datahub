package com.tsd.sano.es.modules.tplusone.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.tplusone.model.TPlusOneImportConfig;
import com.tsd.sano.es.modules.tplusone.model.ImportBatch;
import com.tsd.sano.es.modules.tplusone.model.ImportContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * JDBC数据读取器。
 *
 * <p>负责按主键游标分页读取数据库表数据，并写入导入队列。
 * 当前方案适合单表百万到千万级T+1同步，避免使用offset分页带来的深分页性能问题。</p>
 */
@Service
public class TPlusOneJdbcReader {

    private static final Logger log = LoggerFactory.getLogger(TPlusOneJdbcReader.class);

    /**
     * 数据库标识符校验。
     *
     * <p>表名允许 db.table 或 table；字段名只允许普通列名，避免动态SQL被注入。</p>
     */
    private static final Pattern TABLE_NAME_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    /**
     * 普通字段名校验，适用于idColumn、dtColumn等配置项。
     */
    private static final Pattern COLUMN_NAME_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * DATE类型分区字段的参数格式，对应现有dt字段的yyyy-MM-dd格式。
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * DATE类型分区字段配置值。
     */
    private static final String DT_COLUMN_TYPE_DATE = "DATE";

    /**
     * DATETIME类型分区字段配置值。
     */
    private static final String DT_COLUMN_TYPE_DATETIME = "DATETIME";

    /**
     * 队列入队等待时间。
     *
     * <p>使用超时offer而不是永久put，便于及时感知Bulk线程失败。</p>
     */
    private static final long QUEUE_OFFER_TIMEOUT_SECONDS = 1L;

    /**
     * 查询前按每行1KB预留内存，查询完成后再按首行序列化大小校准。
     */
    private static final int INITIAL_ESTIMATED_ROW_BYTES = 1024;

    /**
     * 实际批次估算安全系数，覆盖少量字段长度波动和容器对象开销。
     */
    private static final double BATCH_SIZE_SAFE_FACTOR = 1.1D;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TPlusOneMemoryLimiter memoryLimiter;

    /**
     * 注入JDBC访问、批次体积估算和T+1内存预算组件。
     */
    public TPlusOneJdbcReader(JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper,
                              TPlusOneMemoryLimiter memoryLimiter) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.memoryLimiter = memoryLimiter;
    }

    /**
     * 统计本次导入总数，并写入全局统计对象。
     */
    public long count(ImportContext context) {
        TPlusOneImportConfig config = requireConfig(context);
        QueryCondition condition = buildCondition(config);
        String tableName = requireTableName(config.getTableName());

        String drainOperationId = context.tryBeginReadBatch();
        if (drainOperationId != null) {
            context.markDrainPartial(drainOperationId);
            log.warn("===> ES-TPlusOne count skipped for drain. operationId={}, table={}",
                    drainOperationId, tableName);
            return 0L;
        }

        // 表名已做白名单校验，查询条件参数仍通过JDBC占位符传入。
        String sql = "SELECT COUNT(1) FROM " + tableName + " WHERE " + condition.whereSql();
        log.info("===> ES-TPlusOne count sql summary. table={}, sql={}, params={}",
                tableName, sql, condition.params());

        Long total;
        try {
            total = jdbcTemplate.queryForObject(sql, Long.class, condition.params().toArray());
        } catch (Exception e) {
            log.error("===> ES-TPlusOne count sql failed. table={}, sql={}, params={}, error={}",
                    tableName, sql, condition.params(), e.getMessage(), e);
            throw e;
        } finally {
            context.endReadBatch();
        }
        long totalCount = total == null ? 0L : total;

        context.getStatistics().getTotal().set(totalCount);
        drainOperationId = context.currentDrainOperationId();
        if (drainOperationId != null) {
            // COUNT先取得查询边界时允许当前SQL完成，但不得继续创建索引或读取数据页。
            context.markDrainPartial(drainOperationId);
            log.warn("===> ES-TPlusOne count finished at drain boundary. operationId={}, table={}, total={}",
                    drainOperationId, tableName, totalCount);
        }
        log.info("===> ES-TPlusOne count datasource. table={}, total={}", tableName, totalCount);
        return totalCount;
    }

    /**
     * 按主键游标分页读取数据，并写入Reader -> Bulk队列。
     */
    public void readToQueue(ImportContext context) {
        TPlusOneImportConfig config = requireConfig(context);
        String idColumn = requireColumnName(config.getIdColumn(), "idColumn");
        int pageSize = context.getProperties().getTPlusOne().getReadBatchSize();
        long lastId = context.getStatistics().getLastId();

        try {
            while (true) {
                // 每轮读取前检查Bulk侧是否已失败，避免继续压入数据。
                checkAbort(context);
                String drainOperationId = context.currentDrainOperationId();
                if (drainOperationId != null) {
                    context.markDrainPartial(drainOperationId);
                    offerEndSignals(context);
                    log.warn("===> ES-TPlusOne reader stopped for drain. operationId={}, table={}, read={}, lastId={}",
                            drainOperationId, config.getTableName(), context.getStatistics().getRead().get(), lastId);
                    return;
                }
                if (context.isDeadlineReached()) {
                    // 到达deadline后不再发起新的MySQL查询，已入队数据交给Bulk继续写完。
                    context.markTimeoutPartial();
                    offerEndSignals(context);
                    log.warn("===> ES-TPlusOne reader reach deadline, stop mysql query. table={}, read={}, lastId={}",
                            config.getTableName(), context.getStatistics().getRead().get(), lastId);
                    return;
                }

                TPlusOneMemoryLimiter.Reservation reservation = memoryLimiter.reserve(
                        (long) pageSize * INITIAL_ESTIMATED_ROW_BYTES,
                        context::isAborted
                );
                boolean reservationTransferred = false;
                try {
                    // reserve可能因T+1内存预算不足而等待；拿到额度后必须重新检查，防止drain期间发起新SQL。
                    drainOperationId = context.tryBeginReadBatch();
                    if (drainOperationId != null) {
                        context.markDrainPartial(drainOperationId);
                        offerEndSignals(context);
                        log.warn("===> ES-TPlusOne reader stopped for drain after memory wait. operationId={}, table={}, read={}, lastId={}",
                                drainOperationId, config.getTableName(), context.getStatistics().getRead().get(), lastId);
                        return;
                    }
                    if (context.isDeadlineReached()) {
                        context.endReadBatch();
                        context.markTimeoutPartial();
                        offerEndSignals(context);
                        return;
                    }

                    try {
                        List<Map<String, Object>> rows = fetchPage(context, lastId, pageSize);
                        if (rows.isEmpty()) {
                            // 没有更多数据时通知所有Bulk工作线程退出，查询预留额度由finally归还。
                            offerEndSignals(context);
                            log.info("===> ES-TPlusOne read finished. table={}, read={}",
                                    config.getTableName(), context.getStatistics().getRead().get());
                            return;
                        }

                        // Bulk失败时及时退出额度扩容等待，当前Reservation由finally归还。
                        reservation.resize(estimateBatchBytes(rows), context::isAborted);

                        // 使用当前页最后一条ID作为下一页游标，避免offset深分页。
                        lastId = extractLastId(rows, idColumn);
                        context.getStatistics().setLastId(lastId);
                        context.getStatistics().getRead().addAndGet(rows.size());

                        ImportBatch batch = context.createBatch(rows, lastId, reservation);
                        reservationTransferred = true;
                        offerBatch(context, batch);
                        log.info("===> ES-TPlusOne read batch. table={}, sequence={}, size={}, reservedBytes={}, lastId={}, read={}/{}",
                                config.getTableName(),
                                batch.sequence(),
                                rows.size(),
                                reservation.bytes(),
                                lastId,
                                context.getStatistics().getRead().get(),
                                context.getStatistics().getTotal().get());

                        if (rows.size() < pageSize) {
                            // 当前页不足一整页，说明已读到最后一页，避免再次查询空页。
                            offerEndSignals(context);
                            log.info("===> ES-TPlusOne read finished. table={}, read={}",
                                    config.getTableName(), context.getStatistics().getRead().get());
                            return;
                        }
                    } finally {
                        context.endReadBatch();
                    }
                } finally {
                    if (!reservationTransferred) {
                        reservation.close();
                    }
                }
            }
        } finally {
            context.markReaderStopped();
        }
    }

    /**
     * 按首行JSON体积估算当前批次占用，避免逐行序列化增加读取开销。
     */
    private long estimateBatchBytes(List<Map<String, Object>> rows) {
        int firstRowBytes;
        try {
            firstRowBytes = objectMapper.writeValueAsBytes(rows.getFirst()).length;
        } catch (Exception e) {
            // 估算失败不影响读取，继续使用查询前的保守默认行大小。
            firstRowBytes = INITIAL_ESTIMATED_ROW_BYTES;
        }
        double estimated = (double) Math.max(1, firstRowBytes) * rows.size() * BATCH_SIZE_SAFE_FACTOR;
        return Math.max(1L, (long) Math.ceil(estimated));
    }

    /**
     * 读取一页数据，供调试或后续服务编排复用。
     */
    public List<Map<String, Object>> fetchPage(ImportContext context, long lastId, int pageSize) {
        TPlusOneImportConfig config = requireConfig(context);
        QueryCondition condition = buildCondition(config);
        String tableName = requireTableName(config.getTableName());
        String idColumn = requireColumnName(config.getIdColumn(), "idColumn");

        List<Object> params = new ArrayList<>(condition.params());
        params.add(lastId);
        params.add(pageSize);

        // idColumn和tableName已校验，只拼接标识符；值全部使用参数绑定。
        String sql = "SELECT * FROM " + tableName
                + " WHERE " + condition.whereSql()
                + " AND " + idColumn + " > ?"
                + " ORDER BY " + idColumn + " ASC"
                + " LIMIT ?";

        try {
            long startTime = System.currentTimeMillis();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
            long costMs = System.currentTimeMillis() - startTime;

            log.info("===> ES-TPlusOne mysql page query. table={}, size={}, lastId={}, pageSize={}, costMs={}",
                    tableName, rows.size(), lastId, pageSize, costMs);
            return rows;
        } catch (Exception e) {
            log.error("===> ES-TPlusOne read sql failed. table={}, sql={}, params={}, lastId={}, pageSize={}, error={}",
                    tableName, sql, params, lastId, pageSize, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 生成查询条件。
     *
     * <p>whereSql有值时认为它来自可信配置，直接作为WHERE片段；
     * whereSql为空时，DATE类型按日期等值过滤，DATETIME类型按当天左闭右开时间范围过滤。</p>
     */
    private QueryCondition buildCondition(TPlusOneImportConfig config) {
        if (StringUtils.isNotBlank(config.getWhereSql())) {
            // whereSql用于复杂场景，需由可信配置提供，不接收外部请求参数。
            return new QueryCondition("(" + config.getWhereSql().trim() + ")", List.of());
        }

        String dtColumn = requireColumnName(config.getDtColumn(), "dtColumn");
        LocalDate importDate = config.getImportDate();
        if (importDate == null) {
            throw new ServiceException("ES import importDate cannot be null when whereSql is blank");
        }

        String dtColumnType = StringUtils.defaultIfBlank(config.getDtColumnType(), DT_COLUMN_TYPE_DATE)
                .trim()
                .toUpperCase(Locale.ROOT);
        if (DT_COLUMN_TYPE_DATE.equals(dtColumnType)) {
            // DATE字段按业务日期等值过滤，兼容已有dt字段和(dt, id)联合索引。
            return new QueryCondition(dtColumn + " = ?", List.of(DATE_FORMATTER.format(importDate)));
        }
        if (DT_COLUMN_TYPE_DATETIME.equals(dtColumnType)) {
            // DATETIME字段使用左闭右开区间，避免23:59:59遗漏带毫秒或微秒的记录。
            Timestamp startTime = Timestamp.valueOf(importDate.atStartOfDay());
            Timestamp endTime = Timestamp.valueOf(importDate.plusDays(1).atStartOfDay());
            return new QueryCondition(dtColumn + " >= ? AND " + dtColumn + " < ?", List.of(startTime, endTime));
        }

        throw new ServiceException("ES import dtColumnType must be DATE or DATETIME, dtColumnType="
                + config.getDtColumnType());
    }

    /**
     * 将当前批次放入阻塞队列；队列满时自然阻塞，形成读写背压。
     */
    private void offerBatch(ImportContext context, ImportBatch batch) {
        try {
            while (!context.getQueue().offer(batch, QUEUE_OFFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 队列满时持续检查中止标记，避免Bulk已失败但Reader仍永久等待。
                checkAbort(context);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("ES import reader interrupted while waiting queue", e);
        }
    }

    /**
     * 检查导入是否已被Bulk线程标记为中止。
     */
    private void checkAbort(ImportContext context) {
        if (!context.isAborted()) {
            return;
        }

        Throwable reason = context.getAbortReason();
        String message = reason == null ? "unknown" : reason.getMessage();
        throw new ServiceException("ES import reader stopped because bulk importer failed, error=" + message, reason);
    }

    /**
     * 为每个Bulk工作线程投递一个结束标记。
     */
    private void offerEndSignals(ImportContext context) {
        int workerCount = context.getProperties().getTPlusOne().getWorkerCount();
        for (int i = 0; i < workerCount; i++) {
            // 每个Bulk工作线程需要一个独立结束信号。
            offerBatch(context, ImportBatch.workerEndSignal());
        }
    }

    /**
     * 提取当前页最后一条数据的游标ID。
     *
     * <p>该ID决定下一页读取位置，缺失或非数字会导致分页无法继续，
     * 因此这里属于流程级错误，需要中断并提示修正配置或源表数据。</p>
     */
    private long extractLastId(List<Map<String, Object>> rows, String idColumn) {
        Object value = rows.getLast().get(idColumn);
        if (value == null) {
            throw new ServiceException("ES import idColumn value cannot be null, idColumn=" + idColumn);
        }
        if (value instanceof Number number) {
            // 数据库数字类型可直接转换为long游标。
            return number.longValue();
        }
        try {
            // 兼容JDBC驱动将数字ID返回为字符串的情况。
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            throw new ServiceException("ES import idColumn must be numeric, idColumn=" + idColumn
                    + ", value=" + value);
        }
    }

    /**
     * 校验导入上下文。
     *
     * <p>上下文缺失属于编排错误，不是单条数据问题，直接中断。</p>
     */
    private TPlusOneImportConfig requireConfig(ImportContext context) {
        if (context == null || context.getConfig() == null) {
            throw new ServiceException("ES import context config cannot be null");
        }
        if (context.getStatistics() == null) {
            throw new ServiceException("ES import context statistics cannot be null");
        }
        if (context.getProperties() == null) {
            throw new ServiceException("ES import properties cannot be null");
        }
        return context.getConfig();
    }

    /**
     * 校验表名，避免动态拼接SQL时引入注入风险。
     */
    private String requireTableName(String tableName) {
        String value = requireText(tableName, "tableName");
        if (!TABLE_NAME_PATTERN.matcher(value).matches()) {
            throw new ServiceException("ES import tableName is invalid: " + tableName);
        }
        return value;
    }

    /**
     * 校验字段名，字段名不能使用JDBC占位符，只能通过白名单规则限制。
     */
    private String requireColumnName(String columnName, String fieldName) {
        String value = requireText(columnName, fieldName);
        if (!COLUMN_NAME_PATTERN.matcher(value).matches()) {
            throw new ServiceException("ES import " + fieldName + " is invalid: " + columnName);
        }
        return value;
    }

    /**
     * 校验必填字符串参数。
     */
    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("ES import " + fieldName + " cannot be blank");
        }
        return value.trim();
    }

    /**
     * 查询条件及其参数。
     */
    private record QueryCondition(String whereSql, List<Object> params) {
    }
}
