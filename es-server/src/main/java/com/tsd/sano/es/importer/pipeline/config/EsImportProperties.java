package com.tsd.sano.es.importer.pipeline.config;

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
     * 允许的最大失败文档数，超过后不绑定alias
     */
    private long maxFailedDocuments = 1000;

    /**
     * 允许的最大失败率，0.001表示0.1%
     */
    private double maxFailureRate = 0.001D;

    /**
     * 是否开启导入监控
     */
    private boolean enableMonitor = true;

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
     * 每轮调度最大运行分钟数，超过后不再启动下一条任务
     */
    private int maxRunMinutes = 480;

    /**
     * 每轮调度最多拉取的待执行任务数量
     */
    private int taskFetchLimit = 100;

    /**
     * 定时任务需要导入的表配置
     */
    private List<TableConfig> tables = new ArrayList<>();

    /**
     * 导入任务结束通知配置，支持按任务结果分别开关。
     */
    private NotifyConfig notify = new NotifyConfig();

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

    public long getMaxFailedDocuments() {
        return maxFailedDocuments;
    }

    public void setMaxFailedDocuments(long maxFailedDocuments) {
        this.maxFailedDocuments = maxFailedDocuments;
    }

    public double getMaxFailureRate() {
        return maxFailureRate;
    }

    public void setMaxFailureRate(double maxFailureRate) {
        this.maxFailureRate = maxFailureRate;
    }

    public boolean isEnableMonitor() {
        return enableMonitor;
    }

    public void setEnableMonitor(boolean enableMonitor) {
        this.enableMonitor = enableMonitor;
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

    public int getMaxRunMinutes() {
        return maxRunMinutes;
    }

    public void setMaxRunMinutes(int maxRunMinutes) {
        this.maxRunMinutes = maxRunMinutes;
    }

    public int getTaskFetchLimit() {
        return taskFetchLimit;
    }

    public void setTaskFetchLimit(int taskFetchLimit) {
        this.taskFetchLimit = taskFetchLimit;
    }

    public List<TableConfig> getTables() {
        return tables;
    }

    public void setTables(List<TableConfig> tables) {
        this.tables = tables;
    }

    public NotifyConfig getNotify() {
        return notify;
    }

    public void setNotify(NotifyConfig notify) {
        this.notify = notify;
    }

    /**
     * 导入任务通知配置。
     */
    public static class NotifyConfig {

        /**
         * 通知总开关，关闭后所有渠道都不发送。
         */
        private boolean enabled = false;

        /**
         * 是否发送成功任务通知。
         */
        private boolean successEnabled = true;

        /**
         * 是否发送失败任务通知。
         */
        private boolean failureEnabled = true;

        /**
         * 是否发送超时暂停任务通知。
         */
        private boolean timeoutEnabled = true;

        /**
         * 通知标题前缀，便于在IM中识别来源。
         */
        private String subjectPrefix = "[SANO-ES]";

        /**
         * 通知渠道配置。
         */
        private NotifyChannels channels = new NotifyChannels();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isSuccessEnabled() {
            return successEnabled;
        }

        public void setSuccessEnabled(boolean successEnabled) {
            this.successEnabled = successEnabled;
        }

        public boolean isFailureEnabled() {
            return failureEnabled;
        }

        public void setFailureEnabled(boolean failureEnabled) {
            this.failureEnabled = failureEnabled;
        }

        public boolean isTimeoutEnabled() {
            return timeoutEnabled;
        }

        public void setTimeoutEnabled(boolean timeoutEnabled) {
            this.timeoutEnabled = timeoutEnabled;
        }

        public String getSubjectPrefix() {
            return subjectPrefix;
        }

        public void setSubjectPrefix(String subjectPrefix) {
            this.subjectPrefix = subjectPrefix;
        }

        public NotifyChannels getChannels() {
            return channels;
        }

        public void setChannels(NotifyChannels channels) {
            this.channels = channels;
        }
    }

    /**
     * webhook通知渠道集合。
     */
    public static class NotifyChannels {

        /**
         * 飞书/Lark机器人配置。
         */
        private NotifyChannelConfig lark = new NotifyChannelConfig();

        /**
         * 钉钉机器人配置。
         */
        private NotifyChannelConfig dingtalk = new NotifyChannelConfig();

        public NotifyChannelConfig getLark() {
            return lark;
        }

        public void setLark(NotifyChannelConfig lark) {
            this.lark = lark;
        }

        public NotifyChannelConfig getDingtalk() {
            return dingtalk;
        }

        public void setDingtalk(NotifyChannelConfig dingtalk) {
            this.dingtalk = dingtalk;
        }
    }

    /**
     * 单个webhook渠道配置。
     */
    public static class NotifyChannelConfig {

        /**
         * 当前渠道开关。
         */
        private boolean enabled = false;

        /**
         * 机器人webhook地址。
         */
        private String webhookUrl;

        /**
         * 机器人签名密钥，未配置时按无签名机器人发送。
         */
        private String secret;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
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

        /**
         * 导入完成是否删除该表历史索引
         */
        private boolean deleteHistoryIndex = false;

        /**
         * 该表历史索引保留天数
         */
        private int reserveDays = 30;

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
    }
}
