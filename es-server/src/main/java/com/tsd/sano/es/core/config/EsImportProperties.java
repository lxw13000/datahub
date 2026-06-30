package com.tsd.sano.es.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ES导入参数配置
 * <p>
 * 所有参数均支持application.yml配置
 *
 * @author lxw
 */
@Component
@ConfigurationProperties(prefix = "sano.es.import")
public class EsImportProperties {

    /**
     * 每批读取数据库数量
     */
    private int readBatchSize = 3000;

    /**
     * Bulk线程数
     */
    private int workerCount = 8;

    /**
     * BlockingQueue容量
     */
    private int queueCapacity = 50;

    /**
     * Bulk最大文档数
     */
    private int bulkActions = 2000;

    /**
     * Bulk最大大小(MB)
     */
    private int bulkSizeMb = 10;

    /**
     * 最大重试次数
     */
    private int retryTimes = 3;

    /**
     * Retry等待(ms)
     */
    private long retryInterval = 1000;

    /**
     * 是否开启导入监控
     */
    private boolean enableMonitor = true;

    /**
     * 导入完成是否删除历史索引
     */
    private boolean deleteHistoryIndex = false;

    /**
     * 保留历史索引天数
     */
    private int reserveDays = 30;

    /**
     * 导入期间是否关闭Refresh
     */
    private boolean disableRefresh = true;

    /**
     * 导入期间是否关闭副本
     */
    private boolean disableReplica = true;

    /**
     * 是否启用定时导入任务，默认关闭，避免服务启动后误执行
     */
    private boolean taskEnabled = false;

    /**
     * 定时导入cron表达式，默认每天02:30执行
     */
    private String cron = "0 30 2 * * ?";

    /**
     * 定时任务需要导入的表配置
     */
    private List<TableConfig> tables = new ArrayList<>();

    //============== getter/setter ===================

    public int getReadBatchSize() {
        return readBatchSize;
    }

    public void setReadBatchSize(int readBatchSize) {
        this.readBatchSize = readBatchSize;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public void setWorkerCount(int workerCount) {
        this.workerCount = workerCount;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBulkActions() {
        return bulkActions;
    }

    public void setBulkActions(int bulkActions) {
        this.bulkActions = bulkActions;
    }

    public int getBulkSizeMb() {
        return bulkSizeMb;
    }

    public void setBulkSizeMb(int bulkSizeMb) {
        this.bulkSizeMb = bulkSizeMb;
    }

    public int getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(int retryTimes) {
        this.retryTimes = retryTimes;
    }

    public long getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(long retryInterval) {
        this.retryInterval = retryInterval;
    }

    public boolean isEnableMonitor() {
        return enableMonitor;
    }

    public void setEnableMonitor(boolean enableMonitor) {
        this.enableMonitor = enableMonitor;
    }

    public boolean isDeleteHistoryIndex() {
        return deleteHistoryIndex;
    }

    public void setDeleteHistoryIndex(boolean deleteHistoryIndex) {
        this.deleteHistoryIndex = deleteHistoryIndex;
    }

    public int getReserveDays() {
        return reserveDays;
    }

    public void setReserveDays(int reserveDays) {
        this.reserveDays = reserveDays;
    }

    public boolean isDisableRefresh() {
        return disableRefresh;
    }

    public void setDisableRefresh(boolean disableRefresh) {
        this.disableRefresh = disableRefresh;
    }

    public boolean isDisableReplica() {
        return disableReplica;
    }

    public void setDisableReplica(boolean disableReplica) {
        this.disableReplica = disableReplica;
    }

    public boolean isTaskEnabled() {
        return taskEnabled;
    }

    public void setTaskEnabled(boolean taskEnabled) {
        this.taskEnabled = taskEnabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public List<TableConfig> getTables() {
        return tables;
    }

    public void setTables(List<TableConfig> tables) {
        this.tables = tables;
    }

    /**
     * 单张业务表的导入配置
     */
    public static class TableConfig {

        /**
         * 是否启用该表
         */
        private boolean enabled = true;

        /**
         * ES业务别名
         */
        private String indexAlias;

        /**
         * 数据库表名，未配置时默认等于indexAlias
         */
        private String tableName;

        /**
         * resources/esmapping目录下的mapping文件名
         */
        private String mappingFile;

        /**
         * 可选SQL条件；为空时按dtColumn = importDate过滤
         */
        private String whereSql;

        /**
         * 主键字段
         */
        private String idColumn = "id";

        /**
         * 分区日期字段
         */
        private String dtColumn = "dt";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIndexAlias() {
            return indexAlias;
        }

        public void setIndexAlias(String indexAlias) {
            this.indexAlias = indexAlias;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getMappingFile() {
            return mappingFile;
        }

        public void setMappingFile(String mappingFile) {
            this.mappingFile = mappingFile;
        }

        public String getWhereSql() {
            return whereSql;
        }

        public void setWhereSql(String whereSql) {
            this.whereSql = whereSql;
        }

        public String getIdColumn() {
            return idColumn;
        }

        public void setIdColumn(String idColumn) {
            this.idColumn = idColumn;
        }

        public String getDtColumn() {
            return dtColumn;
        }

        public void setDtColumn(String dtColumn) {
            this.dtColumn = dtColumn;
        }
    }
}
