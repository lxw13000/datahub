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

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

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
     * 按业务日期和递增ID持续同步的Polling参数。
     */
    private Polling polling = new Polling();

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
        if (tables == null) {
            tables = List.of();
        }
        List<TableConfig> tPlusOneConfigs = new ArrayList<>();
        List<TableConfig> pollingConfigs = new ArrayList<>();
        Set<String> enabledTableNames = new HashSet<>();

        for (TableConfig tableConfig : tables) {
            // 过滤掉未启用的表，避免重复校验和分组。
            if (!tableConfig.isEnabled()) {
                continue;
            }
            // 规范化单表配置，并校验启用表执行同步所需的必填字段。
            normalizeAndValidate(tableConfig);
            if (!enabledTableNames.add(tableConfig.getTableName())) {
                throw new IllegalStateException("Duplicate enabled ES sync table-name: "
                        + tableConfig.getTableName());
            }
            // 这里故意使用无default的穷举switch；新增同步模式时，编译器会强制补充独立集合和分组规则。
            switch (tableConfig.getSyncMode()) {
                case T_PLUS_ONE -> tPlusOneConfigs.add(tableConfig);
                case POLLING -> pollingConfigs.add(tableConfig);
            }
        }

        this.tables = tables;
        this.tPlusOneTables = List.copyOf(tPlusOneConfigs);
        this.pollingTables = List.copyOf(pollingConfigs);
    }

    /**
     * 获取指定源表名的已启用T+1表；配置不存在或模式不匹配时阻止任务继续执行。
     */
    public TableConfig requireTPlusOneTable(String tableName) {
        return tPlusOneTables.stream()
                .filter(table -> StringUtils.equals(table.getTableName(), tableName))
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                        "ES sync table is disabled or mode mismatch, tableName=" + tableName
                                + ", expectedMode=" + TableSyncMode.T_PLUS_ONE));
    }

    /**
     * 规范化单表配置，并校验启用表执行同步所需的必填字段。
     */
    private void normalizeAndValidate(TableConfig source) {
        TableSyncMode syncMode = source.getSyncMode() == null
                ? TableSyncMode.T_PLUS_ONE
                : source.getSyncMode();
        String tableName = StringUtils.trimToEmpty(source.getTableName());
        String indexAlias = StringUtils.defaultIfBlank(source.getIndexAlias(), tableName).trim();
        String mappingFile = StringUtils.trimToEmpty(source.getMappingFile());
        String idColumn = StringUtils.trimToEmpty(source.getIdColumn());
        String dtColumn = StringUtils.trimToEmpty(source.getDtColumn());
        String dtColumnType = StringUtils.trimToEmpty(source.getDtColumnType()).toUpperCase(Locale.ROOT);

        if (source.isEnabled()) {
            validateText(tableName, "table-name");
            validateText(indexAlias, "index-alias");
            validateText(mappingFile, "mapping-file");
            validateText(idColumn, "id-column");
            validateText(dtColumn, "dt-column");
            if (!StringUtils.equalsAny(dtColumnType, "DATE", "DATETIME")) {
                throw new IllegalStateException(
                        "ES sync table dt-column-type must be DATE or DATETIME, tableName="
                                + tableName + ", value=" + dtColumnType);
            }
            if (syncMode == TableSyncMode.POLLING && source.getBootstrapStartDate() == null) {
                throw new IllegalStateException(
                        "ES polling table bootstrap-start-date cannot be null, tableName=" + tableName);
            }
            if (source.getReserveDays() < 0) {
                throw new IllegalStateException(
                        "ES sync table reserve-days cannot be negative, tableName=" + tableName);
            }
        }

        source.setSyncMode(syncMode);
        source.setTableName(tableName);
        source.setIndexAlias(indexAlias);
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
         * 所有同步引擎共用的ES写入并发，以及T+1流水线内存预算。
         */
        private Write write = new Write();
    }

    /**
     * T+1按日建索引导入参数；polling和未来MQ不得复用这些吞吐及失败策略。
     */
    @Getter
    @Setter
    public static class TPlusOne {

        /**
         * 是否启用T+1定时及手工导入能力。
         */
        private boolean enabled = false;

        /**
         * T+1定时导入cron表达式，默认每天02:30执行。
         */
        private String cron = "0 30 2 * * ?";

        /**
         * 每轮调度最大运行分钟数，超过后不再启动下一条任务。
         */
        private int maxRunMinutes = 480;

        /**
         * 每轮调度最多拉取的待执行任务数。
         */
        private int taskFetchLimit = 100;

        /**
         * 每批读取MySQL数据量。
         */
        private int readBatchSize = 3000;

        /**
         * Bulk写入线程数。
         */
        private int workerCount = 8;

        /**
         * Reader到Bulk之间的队列容量。
         */
        private int queueCapacity = 50;

        /**
         * 单次Bulk最大文档数。
         */
        private int bulkActions = 2000;

        /**
         * 单次Bulk最大请求体大小，单位MB。
         */
        private int bulkSizeMb = 10;

        /**
         * Bulk写入最大重试次数。
         */
        private int retryTimes = 3;

        /**
         * Bulk写入重试等待时间，单位毫秒。
         */
        private long retryInterval = 1000;

        /**
         * 允许的最大失败文档数，超过后不绑定Alias。
         */
        private long maxFailedDocuments = 1000;

        /**
         * 允许的最大失败率，0.001表示0.1%。
         */
        private double maxFailureRate = 0.001D;

        /**
         * 是否开启T+1导入监控日志。
         */
        private boolean enableMonitor = true;

        /**
         * T+1建索引导入期间是否关闭ES refresh。
         */
        private boolean disableRefresh = true;

        /**
         * T+1建索引导入期间是否关闭ES副本。
         */
        private boolean disableReplica = true;

    }

    /**
     * Polling单表串行同步参数。
     *
     * <p>该配置只描述Polling主循环和整批重试，不包含对账线程及对账队列。</p>
     */
    @Getter
    @Setter
    public static class Polling {

        /**
         * 是否启用Polling调度和单表Worker。
         */
        private boolean enabled;

        /**
         * 当前实例允许同时持有并运行的Polling表数量。
         */
        private int maxActiveTables = 5;

        /**
         * 当前日期查询为空后再次轮询MySQL的等待时间。
         */
        private Duration pollInterval = Duration.ofSeconds(5);

        /**
         * 次日零点后继续接收旧日期晚到数据的等待时间。
         */
        private Duration dateCloseDelay = Duration.ofMinutes(10);

        /**
         * 单次按日期和递增ID读取MySQL的最大记录数。
         */
        private int readBatchSize = 3000;

        /**
         * ES整批Bulk失败后的重试次数，不包含首次请求。
         */
        private int bulkRetryTimes = 2;

        /**
         * 每次ES整批Bulk重试前的等待时间。
         */
        private Duration bulkRetryInterval = Duration.ofSeconds(1);

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
         * T+1排队、在途和重试批次的合计内存预算。
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
         * 是否执行该表的异步统计对账；同步流程仍统一发起调用，由对账入口按此配置决定是否执行。
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
