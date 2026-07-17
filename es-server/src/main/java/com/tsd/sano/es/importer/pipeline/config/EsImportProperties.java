package com.tsd.sano.es.importer.pipeline.config;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.sync.config.TableSyncMode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ES导入参数配置。
 *
 * <p>所有字段均支持通过application.yml配置，配置前缀为 {@code sano.import}。</p>
 *
 * @author lxw
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sano.import")
public class EsImportProperties {

    /**
     * T+1、polling及未来MQ同步共用的资源协调参数。
     */
    private Common common = new Common();

    /**
     * 现有按业务日期建索引的T+1导入参数。
     */
    private TPlusOne tPlusOne = new TPlusOne();

    /**
     * 定时任务需要导入的表配置。
     */
    private List<TableConfig> tables = List.of();

    /**
     * 配置加载时生成的T+1启用表，只读且运行期间不再重复筛选。
     */
    @Setter(AccessLevel.NONE)
    private List<TableConfig> tPlusOneTables = List.of();

    /**
     * 配置加载时生成的polling启用表，只读且运行期间不再重复筛选。
     */
    @Setter(AccessLevel.NONE)
    private List<TableConfig> pollingTables = List.of();

    /**
     * 接收表配置时一次性完成默认值规范化、校验和模式分组。
     *
     * <p>Spring Boot在启动绑定 {@code sano.import.tables} 时调用该方法。后续同步任务直接读取
     * {@link #getTPlusOneTables()} 或 {@link #getPollingTables()}，不再按模式重复遍历配置。</p>
     */
    public void setTables(List<TableConfig> tables) {
        List<TableConfig> configuredTables = tables == null ? List.of() : List.copyOf(tables);
        List<TableConfig> tPlusOneConfigs = new ArrayList<>();
        List<TableConfig> pollingConfigs = new ArrayList<>();
        Set<String> enabledAliases = new HashSet<>();

        for (TableConfig tableConfig : configuredTables) {
            normalizeAndValidate(tableConfig);
            if (!tableConfig.isEnabled()) {
                continue;
            }
            if (!enabledAliases.add(tableConfig.getIndexAlias())) {
                throw new IllegalStateException("Duplicate enabled ES sync index-alias: "
                        + tableConfig.getIndexAlias());
            }
            // 这里故意使用无default的穷举switch；新增同步模式时，编译器会强制补充独立集合和分组规则。
            List<TableConfig> modeConfigs = switch (tableConfig.getSyncMode()) {
                case T_PLUS_ONE -> tPlusOneConfigs;
                case POLLING -> pollingConfigs;
            };
            modeConfigs.add(tableConfig);
        }

        this.tables = configuredTables;
        this.tPlusOneTables = List.copyOf(tPlusOneConfigs);
        this.pollingTables = List.copyOf(pollingConfigs);
    }

    /**
     * 获取指定Alias的已启用T+1表；配置不存在或模式不匹配时阻止任务继续执行。
     */
    public TableConfig requireTPlusOneTable(String indexAlias) {
        return tPlusOneTables.stream()
                .filter(table -> StringUtils.equals(table.getIndexAlias(), indexAlias))
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                        "ES sync table is disabled or mode mismatch, indexAlias=" + indexAlias
                                + ", expectedMode=" + TableSyncMode.T_PLUS_ONE));
    }

    /**
     * 规范化单表配置，并校验启用表执行同步所需的必填字段。
     */
    private void normalizeAndValidate(TableConfig source) {
        TableSyncMode syncMode = source.getSyncMode() == null
                ? TableSyncMode.T_PLUS_ONE
                : source.getSyncMode();
        String indexAlias = StringUtils.trimToEmpty(source.getIndexAlias());
        String tableName = StringUtils.defaultIfBlank(source.getTableName(), indexAlias).trim();
        String mappingFile = StringUtils.trimToEmpty(source.getMappingFile());
        String idColumn = StringUtils.trimToEmpty(source.getIdColumn());
        String dtColumn = StringUtils.trimToEmpty(source.getDtColumn());
        String dtColumnType = StringUtils.trimToEmpty(source.getDtColumnType()).toUpperCase(Locale.ROOT);

        if (source.isEnabled()) {
            validateText(indexAlias, "index-alias");
            validateText(tableName, "table-name");
            validateText(mappingFile, "mapping-file");
            validateText(idColumn, "id-column");
            validateText(dtColumn, "dt-column");
            if (!StringUtils.equalsAny(dtColumnType, "DATE", "DATETIME")) {
                throw new IllegalStateException(
                        "ES sync table dt-column-type must be DATE or DATETIME, indexAlias="
                                + indexAlias + ", value=" + dtColumnType);
            }
            if (source.getReserveDays() < 0) {
                throw new IllegalStateException(
                        "ES sync table reserve-days cannot be negative, indexAlias=" + indexAlias);
            }
        }

        source.setSyncMode(syncMode);
        source.setIndexAlias(indexAlias);
        source.setTableName(tableName);
        source.setMappingFile(mappingFile);
        source.setIdColumn(idColumn);
        source.setDtColumn(dtColumn);
        source.setDtColumnType(dtColumnType);
    }

    /**
     * 校验表配置中的必填文本。
     */
    private void validateText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("ES sync table " + fieldName + " cannot be blank");
        }
    }

    /**
     * 各同步引擎共用的资源协调参数；不包含任何引擎自己的读取、队列或重试策略。
     */
    @Getter
    @Setter
    public static class Common {

        /**
         * drain等待Reader、队列、Bulk和持久任务状态全部到达安全边界的最长秒数。
         */
        private int drainTimeoutSeconds = 600;

        /**
         * 所有同步引擎共用的ES写入并发和内存预算。
         */
        private Write write = new Write();
    }

    /**
     * T+1按日建索引导入参数；polling和未来MQ不得复用这些吞吐及失败策略。
     */
    @Getter
    @Setter
    public static class TPlusOne {

        /** 是否启用T+1定时及手工导入能力。 */
        private boolean enabled = false;

        /** T+1定时导入cron表达式，默认每天02:30执行。 */
        private String cron = "0 30 2 * * ?";

        /** 每轮调度最大运行分钟数，超过后不再启动下一条任务。 */
        private int maxRunMinutes = 480;

        /** 每轮调度最多拉取的待执行任务数。 */
        private int taskFetchLimit = 100;

        /** 每批读取MySQL数据量。 */
        private int readBatchSize = 3000;

        /** Bulk写入线程数。 */
        private int workerCount = 8;

        /** Reader到Bulk之间的队列容量。 */
        private int queueCapacity = 50;

        /** 单次Bulk最大文档数。 */
        private int bulkActions = 2000;

        /** 单次Bulk最大请求体大小，单位MB。 */
        private int bulkSizeMb = 10;

        /** Bulk写入最大重试次数。 */
        private int retryTimes = 3;

        /** Bulk写入重试等待时间，单位毫秒。 */
        private long retryInterval = 1000;

        /** 允许的最大失败文档数，超过后不绑定Alias。 */
        private long maxFailedDocuments = 1000;

        /** 允许的最大失败率，0.001表示0.1%。 */
        private double maxFailureRate = 0.001D;

        /** 是否开启T+1导入监控日志。 */
        private boolean enableMonitor = true;

        /** T+1建索引导入期间是否关闭ES refresh。 */
        private boolean disableRefresh = true;

        /** T+1建索引导入期间是否关闭ES副本。 */
        private boolean disableReplica = true;

    }

    /**
     * 所有同步引擎共享的写入资源参数。
     */
    @Getter
    @Setter
    public static class Write {

        /**
         * 所有同步引擎合计允许的在途ES Bulk请求数。
         */
        private int globalBulkConcurrency = 3;

        /**
         * polling存在等待请求时为其保留的并发数。
         */
        private int pollingReservedConcurrency = 2;

        /**
         * polling空闲时T+1最多可使用的并发数。
         */
        private int tPlusOneMaxConcurrency = 3;

        /**
         * 所有同步引擎排队、在途和重试批次的合计内存预算。
         */
        private DataSize globalQueueMaxBytes = DataSize.ofMegabytes(128);
    }

    /**
     * 单张业务表的导入配置。
     */
    @Getter
    @Setter
    public static class TableConfig {

        /**
         * 是否启用该表。
         */
        private boolean enabled = true;

        /**
         * 单表自动同步模式；未配置时保持原有T+1行为。
         */
        private TableSyncMode syncMode = TableSyncMode.T_PLUS_ONE;

        /**
         * polling首次启动日期，T+1模式忽略该字段。
         */
        private LocalDate bootstrapStartDate;

        /**
         * ES业务alias。
         */
        private String indexAlias;

        /**
         * MySQL源表名，未配置时默认等于indexAlias。
         */
        private String tableName;

        /**
         * resources/esmapping目录下的mapping文件名。
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
         * 分区日期字段，whereSql为空时按该字段做T+1过滤。
         */
        private String dtColumn = "dt";

        /**
         * 分区日期字段类型，只支持DATE或DATETIME；DATE按等值查询，DATETIME按当天时间范围查询。
         */
        private String dtColumnType = "DATE";

        /**
         * 导入完成是否删除该表历史索引。
         */
        private boolean deleteHistoryIndex = false;

        /**
         * 该表历史索引保留天数。
         */
        private int reserveDays = 30;
    }
}
