package com.tsd.sano.es.polling.service;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Polling单表串行MySQL读取器。
 *
 * <p>每次调用只按业务日期和递增ID读取一批数据，不创建线程、不持有队列，也不更新checkpoint。
 * 查询在当前调用线程同步等待；MySQL真正返回错误时直接交由上层表Worker持久暂停该表。</p>
 */
@Service
public class PollingJdbcReader {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 注入JDBC访问组件。
     */
    public PollingJdbcReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按日期和主键游标读取一批MySQL数据。
     *
     * @param tableConfig 单表同步配置
     * @param syncDate    当前业务日期
     * @param lastId      已处理查询批次的内存游标；Bulk重试耗尽后也会推进
     * @param batchSize   本次最大读取数量
     * @return 本批行数据以及本批最后一个ID；空批次的lastId保持传入值
     */
    public ReadBatch readBatch(EsImportProperties.TableConfig tableConfig,
                               LocalDate syncDate, long lastId, int batchSize) {
        // 去掉不必要的tableConfig验证，因为在EsImportProperties启动时已经做过了，避免重复抛异常。
        String tableName = tableConfig.getTableName();
        String idColumn = tableConfig.getIdColumn();
        List<Object> params = new ArrayList<>();
        String businessCondition;

        if (StringUtils.isNotBlank(tableConfig.getWhereSql())) {
            // whereSql只允许来自受信任的部署配置，并被视为完整业务日期条件。
            businessCondition = "(" + tableConfig.getWhereSql().trim() + ")";
        } else {
            String dtColumn = tableConfig.getDtColumn();
            if ("DATE".equals(tableConfig.getDtColumnType())) {
                businessCondition = dtColumn + " = ?";
                params.add(java.sql.Date.valueOf(syncDate));
            } else if ("DATETIME".equals(tableConfig.getDtColumnType())) {
                businessCondition = dtColumn + " >= ? AND " + dtColumn + " < ?";
                params.add(Timestamp.valueOf(syncDate.atStartOfDay()));
                params.add(Timestamp.valueOf(syncDate.plusDays(1).atStartOfDay()));
            } else {
                throw new ServiceException("ES polling dtColumnType must be DATE or DATETIME, tableName="
                        + tableName + ", value=" + tableConfig.getDtColumnType());
            }
        }

        params.add(lastId);
        params.add(batchSize);
        String sql = "SELECT * FROM " + tableName
                + " WHERE " + businessCondition
                + " AND " + idColumn + " > ?"
                + " ORDER BY " + idColumn + " ASC"
                + " LIMIT ?";

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
            long firstId = 0L;
            long nextLastId = lastId;

            if (!rows.isEmpty()) {
                Object idValue = rows.getFirst().get(idColumn);
                if (!(idValue instanceof Number number)) {
                    throw new ServiceException("ES polling idColumn must be numeric, tableName="
                            + tableName + ", idColumn=" + idColumn + ", value=" + idValue);
                }
                firstId = number.longValue();
                idValue = rows.getLast().get(idColumn);
                if (!(idValue instanceof Number number3)) {
                    throw new ServiceException("ES polling idColumn must be numeric, tableName="
                            + tableName + ", idColumn=" + idColumn + ", value=" + idValue);
                }
                nextLastId = number3.longValue();
            }

            return new ReadBatch(rows, firstId, nextLastId);
        } catch (DataAccessException error) {
            throw new ServiceException("ES polling mysql query failed, tableName=" + tableName
                    + ", syncDate=" + syncDate + ", lastId=" + lastId
                    + ", error=" + error.getMessage(), error);
        }
    }


    /**
     * 一次MySQL读取结果。
     *
     * @param rows   当前批次行数据
     * @param lastId 当前批次最后一个ID；空批次保持调用前游标
     */
    public record ReadBatch(List<Map<String, Object>> rows, long firstId, long lastId) {

        /**
         * 当前批次是否没有数据。
         */
        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }
}
