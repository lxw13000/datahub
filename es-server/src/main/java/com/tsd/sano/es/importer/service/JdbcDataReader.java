package com.tsd.sano.es.importer.service;

import com.tsd.sano.es.core.exception.BusinessException;
import com.tsd.sano.es.importer.model.EsImportConfig;
import com.tsd.sano.es.importer.model.ImportContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JDBC数据读取器。
 *
 * <p>负责按主键游标分页读取数据库表数据，并写入导入队列。
 * 当前方案适合单表百万到千万级T+1同步，避免使用offset分页带来的深分页性能问题。</p>
 */
@Service
public class JdbcDataReader {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataReader.class);

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
     * 默认日期格式，对应现有dt字段的yyyy-MM-dd格式。
     */
    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDataReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 统计本次导入总数，并写入全局统计对象。
     */
    public long count(ImportContext context) {
        EsImportConfig config = requireConfig(context);
        QueryCondition condition = buildCondition(config);
        String tableName = requireTableName(config.getTableName());

        String sql = "SELECT COUNT(1) FROM " + tableName + " WHERE " + condition.whereSql();
        Long total = jdbcTemplate.queryForObject(sql, Long.class, condition.params().toArray());
        long totalCount = total == null ? 0L : total;

        context.getStatistics().getTotal().set(totalCount);
        log.info("===> ES-Import count datasource. table={}, total={}", tableName, totalCount);
        return totalCount;
    }

    /**
     * 按主键游标分页读取数据，并写入Reader -> Bulk队列。
     */
    public void readToQueue(ImportContext context) {
        EsImportConfig config = requireConfig(context);
        String idColumn = requireColumnName(config.getIdColumn(), "idColumn");
        int pageSize = context.getProperties().getPageSize();
        long lastId = context.getStatistics().getLastId();

        while (true) {
            List<Map<String, Object>> rows = fetchPage(context, lastId, pageSize);
            if (rows.isEmpty()) {
                offerEndSignals(context);
                log.info("===> ES-Import read finished. table={}, read={}",
                        config.getTableName(), context.getStatistics().getRead().get());
                return;
            }

            lastId = extractLastId(rows, idColumn);
            context.getStatistics().setLastId(lastId);
            context.getStatistics().getRead().addAndGet(rows.size());

            offerBatch(context, rows);
            log.info("===> ES-Import read batch. table={}, size={}, lastId={}, read={}/{}",
                    config.getTableName(),
                    rows.size(),
                    lastId,
                    context.getStatistics().getRead().get(),
                    context.getStatistics().getTotal().get());
        }
    }

    /**
     * 读取一页数据，供调试或后续服务编排复用。
     */
    public List<Map<String, Object>> fetchPage(ImportContext context, long lastId, int pageSize) {
        EsImportConfig config = requireConfig(context);
        QueryCondition condition = buildCondition(config);
        String tableName = requireTableName(config.getTableName());
        String idColumn = requireColumnName(config.getIdColumn(), "idColumn");

        List<Object> params = new ArrayList<>(condition.params());
        params.add(lastId);
        params.add(pageSize);

        String sql = "SELECT * FROM " + tableName
                + " WHERE " + condition.whereSql()
                + " AND " + idColumn + " > ?"
                + " ORDER BY " + idColumn + " ASC"
                + " LIMIT ?";

        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    /**
     * 生成查询条件。
     *
     * <p>whereSql有值时认为它来自可信配置，直接作为WHERE片段；
     * whereSql为空时默认按dtColumn = importDate过滤。</p>
     */
    private QueryCondition buildCondition(EsImportConfig config) {
        if (StringUtils.isNotBlank(config.getWhereSql())) {
            return new QueryCondition("(" + config.getWhereSql().trim() + ")", List.of());
        }

        String dtColumn = requireColumnName(config.getDtColumn(), "dtColumn");
        LocalDate importDate = config.getImportDate();
        if (importDate == null) {
            throw new BusinessException("ES import importDate cannot be null when whereSql is blank");
        }

        return new QueryCondition(dtColumn + " = ?", List.of(DT_FORMATTER.format(importDate)));
    }

    /**
     * 将当前批次放入阻塞队列；队列满时自然阻塞，形成读写背压。
     */
    private void offerBatch(ImportContext context, List<Map<String, Object>> rows) {
        try {
            context.getQueue().put(rows);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("ES import reader interrupted while waiting queue", e);
        }
    }

    /**
     * 为每个Bulk工作线程投递一个结束标记。
     */
    private void offerEndSignals(ImportContext context) {
        int workerCount = context.getProperties().getWorkerCount();
        for (int i = 0; i < workerCount; i++) {
            offerBatch(context, List.of());
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
            throw new BusinessException("ES import idColumn value cannot be null, idColumn=" + idColumn);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            throw new BusinessException("ES import idColumn must be numeric, idColumn=" + idColumn
                    + ", value=" + value);
        }
    }

    /**
     * 校验导入上下文。
     *
     * <p>上下文缺失属于编排错误，不是单条数据问题，直接中断。</p>
     */
    private EsImportConfig requireConfig(ImportContext context) {
        if (context == null || context.getConfig() == null) {
            throw new BusinessException("ES import context config cannot be null");
        }
        if (context.getStatistics() == null) {
            throw new BusinessException("ES import context statistics cannot be null");
        }
        if (context.getProperties() == null) {
            throw new BusinessException("ES import properties cannot be null");
        }
        return context.getConfig();
    }

    /**
     * 校验表名，避免动态拼接SQL时引入注入风险。
     */
    private String requireTableName(String tableName) {
        String value = requireText(tableName, "tableName");
        if (!TABLE_NAME_PATTERN.matcher(value).matches()) {
            throw new BusinessException("ES import tableName is invalid: " + tableName);
        }
        return value;
    }

    /**
     * 校验字段名，字段名不能使用JDBC占位符，只能通过白名单规则限制。
     */
    private String requireColumnName(String columnName, String fieldName) {
        String value = requireText(columnName, fieldName);
        if (!COLUMN_NAME_PATTERN.matcher(value).matches()) {
            throw new BusinessException("ES import " + fieldName + " is invalid: " + columnName);
        }
        return value;
    }

    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new BusinessException("ES import " + fieldName + " cannot be blank");
        }
        return value.trim();
    }

    private record QueryCondition(String whereSql, List<Object> params) {
    }
}
