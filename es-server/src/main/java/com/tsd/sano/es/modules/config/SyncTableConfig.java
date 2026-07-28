package com.tsd.sano.es.modules.config;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 单张业务表的同步配置。
 */
@Getter
@Setter
public class SyncTableConfig {

    /**
     * 是否启用该表。
     */
    private boolean enabled = true;

    /**
     * 单表自动同步模式；未配置时保持原有T+1行为。
     */
    private TableSyncMode syncMode = TableSyncMode.T_PLUS_ONE;

    /**
     * Polling首次启动日期，T+1模式忽略该字段。
     */
    private LocalDate bootstrapStartDate;

    /**
     * 是否执行该表的异步统计对账；同步流程仍统一调用，由对账入口决定是否执行。
     */
    private boolean reconcile = true;

    /**
     * MySQL源表名，也是同步表配置和checkpoint的唯一标识。
     */
    private String tableName;

    /**
     * ES业务Alias，只用于聚合按日期创建的物理索引；未配置时默认等于tableName。
     */
    private String indexAlias;

    /**
     * resources/esmapping目录下的Mapping文件名。
     */
    private String mappingFile;

    /**
     * 可选SQL条件；为空时根据dtColumnType按业务日期生成查询条件。
     */
    private String whereSql;

    /**
     * 主键字段，用于游标分页和ES文档ID。
     */
    private String idColumn = "id";

    /**
     * 分区日期字段，whereSql为空时按该字段做日期过滤。
     */
    private String dtColumn = "dt";

    /**
     * 日期字段类型；DATE按等值查询，DATETIME按当天左闭右开范围查询。
     */
    private String dtColumnType = "DATE";

    /**
     * 同步完成是否删除该表超出保留期的历史索引。
     */
    private boolean deleteHistoryIndex = false;

    /**
     * 该表历史索引保留天数。
     */
    private int reserveDays = 30;

    /**
     * 规范化并完整校验当前表的静态配置。
     *
     * <p>该方法在配置绑定阶段对启用表执行；运行时读取到的具体数据类型仍由Reader校验。</p>
     */
    void normalizeAndValidate() {
        TableSyncMode normalizedSyncMode = syncMode == null
                ? TableSyncMode.T_PLUS_ONE
                : syncMode;
        String normalizedTableName = StringUtils.trimToEmpty(tableName);
        String normalizedIndexAlias =
                StringUtils.defaultIfBlank(indexAlias, normalizedTableName).trim();
        String normalizedMappingFile = StringUtils.trimToEmpty(mappingFile);
        String normalizedIdColumn = StringUtils.trimToEmpty(idColumn);
        String normalizedDtColumn = StringUtils.trimToEmpty(dtColumn);
        String normalizedDtColumnType =
                StringUtils.trimToEmpty(dtColumnType).toUpperCase(Locale.ROOT);
        String normalizedWhereSql = StringUtils.trimToNull(whereSql);

        validateText(normalizedTableName, "table-name");
        validateText(normalizedIndexAlias, "index-alias");
        validateText(normalizedMappingFile, "mapping-file");
        validateText(normalizedIdColumn, "id-column");
        validateText(normalizedDtColumn, "dt-column");

        if (!StringUtils.equalsAny(normalizedDtColumnType, "DATE", "DATETIME")) {
            throw new IllegalStateException(
                    "ES sync table dt-column-type must be DATE or DATETIME, tableName="
                            + normalizedTableName + ", value=" + normalizedDtColumnType);
        }
        if (deleteHistoryIndex && reserveDays <= 0) {
            throw new IllegalStateException(
                    "ES sync table reserve-days cannot be negative, tableName=" + normalizedTableName);
        }

        if (normalizedSyncMode == TableSyncMode.POLLING) {
            if (bootstrapStartDate == null) {
                throw new IllegalStateException(
                        "ES polling table bootstrap-start-date cannot be null, tableName="
                                + normalizedTableName);
            }
            if (bootstrapStartDate.isAfter(LocalDate.now())) {
                throw new IllegalStateException(
                        "ES polling table bootstrap-start-date cannot be in the future, tableName="
                                + normalizedTableName + ", value=" + bootstrapStartDate);
            }
        }

        syncMode = normalizedSyncMode;
        tableName = normalizedTableName;
        indexAlias = normalizedIndexAlias;
        mappingFile = normalizedMappingFile;
        idColumn = normalizedIdColumn;
        dtColumn = normalizedDtColumn;
        dtColumnType = normalizedDtColumnType;
        whereSql = normalizedWhereSql;
    }

    /**
     * 校验表配置中的必填文本。
     */
    private void validateText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("ES sync table " + fieldName + " cannot be blank");
        }
    }
}
