package com.tsd.sano.es.importer.pipeline.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ES导入参数配置。
 *
 * <p>所有字段均支持通过application.yml配置，配置前缀为 {@code sano.es.import}。</p>
 *
 * @author lxw
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sano.es.import")
public class EsImportProperties {

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
     * 允许的最大失败文档数，超过后不绑定alias。
     */
    private long maxFailedDocuments = 1000;

    /**
     * 允许的最大失败率，0.001表示0.1%。
     */
    private double maxFailureRate = 0.001D;

    /**
     * 是否开启导入监控日志。
     */
    private boolean enableMonitor = true;

    /**
     * 导入期间是否关闭ES refresh。
     */
    private boolean disableRefresh = true;

    /**
     * 导入期间是否关闭ES副本。
     */
    private boolean disableReplica = true;

    /**
     * 是否启用定时导入任务，默认关闭，避免服务启动后误执行。
     */
    private boolean taskEnabled = false;

    /**
     * 定时导入cron表达式，默认每天02:30执行。
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
     * 定时任务需要导入的表配置。
     */
    private List<TableConfig> tables = new ArrayList<>();

    /**
     * 导入任务结束通知配置，支持按任务结果分别开关。
     */
    private NotifyConfig notify = new NotifyConfig();

    /**
     * 导入任务通知配置。
     */
    @Getter
    @Setter
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
    }

    /**
     * webhook通知渠道集合。
     */
    @Getter
    @Setter
    public static class NotifyChannels {

        /**
         * 飞书/Lark机器人配置。
         */
        private NotifyChannelConfig lark = new NotifyChannelConfig();

        /**
         * 钉钉机器人配置。
         */
        private NotifyChannelConfig dingtalk = new NotifyChannelConfig();
    }

    /**
     * 单个webhook渠道配置。
     */
    @Getter
    @Setter
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
         * 可选SQL条件；为空时按dtColumn = importDate过滤。
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
