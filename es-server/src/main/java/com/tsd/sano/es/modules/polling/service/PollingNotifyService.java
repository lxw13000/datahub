package com.tsd.sano.es.modules.polling.service;

import com.tsd.sano.es.modules.notify.model.NotifyMessage;
import com.tsd.sano.es.modules.notify.service.NotifyService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Polling业务通知服务。
 *
 * <p>负责组织Polling停止和失败批次消息；公共NotifyService只负责通道分发。
 * 通知失败不能改变checkpoint、内存游标或后续批次执行。</p>
 */
@Service
public class PollingNotifyService {

    private static final Logger log = LoggerFactory.getLogger(PollingNotifyService.class);

    /** Polling消息标题前缀。 */
    private static final String SUBJECT_PREFIX = "[SANO-ES]";

    /** 公共通知通道服务。 */
    private final NotifyService notifyService;

    /**
     * 注入公共通知服务。
     */
    public PollingNotifyService(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    /**
     * Polling表因系统性错误持久暂停后发送停止通知。
     */
    public void notifyStopped(String tableName, String indexName,
                              LocalDate syncDate, long lastId, Throwable error) {
        try {
            String title = SUBJECT_PREFIX + " ES Polling同步已停止 " + indexName;
            String content = new StringBuilder(384)
                    .append(title).append('\n')
                    .append("表名：").append(tableName).append('\n')
                    .append("索引：").append(indexName).append('\n')
                    .append("业务日期：").append(syncDate).append('\n')
                    .append("安全断点ID：").append(lastId).append('\n')
                    .append("状态：PAUSED").append('\n')
                    .append("错误：").append(StringUtils.left(
                            error == null ? "Unknown polling synchronization error" : error.getMessage(),
                            800
                    ))
                    .toString();
            notifyService.send(new NotifyMessage(
                    "POLLING_STOPPED", tableName, title, content));
        } catch (Exception notifyError) {
            log.error("===> ES-Polling notify stopped failed before send. table={}, error={}",
                    tableName, notifyError.getMessage(), notifyError);
        }
    }

    /**
     * 异步通知Polling整批重试耗尽，但不暂停当前表。
     *
     * <p>失败批次已经按最大ID推进，后续由统计对账发现差异，并由运维提交指定日期
     * T+1全量修复。</p>
     */
    @Async("esReconcileExecutor")
    public void notifyBulkFailed(String tableName, String indexName,
                                 LocalDate syncDate, long firstId, long lastId,
                                 int attempts, String error) {
        try {
            String title = SUBJECT_PREFIX + " ES Polling批次写入失败 " + indexName;
            String content = new StringBuilder(480)
                    .append(title).append('\n')
                    .append("表名：").append(tableName).append('\n')
                    .append("索引：").append(indexName).append('\n')
                    .append("业务日期：").append(syncDate).append('\n')
                    .append("失败批次ID：").append(firstId).append(" - ").append(lastId).append('\n')
                    .append("Bulk尝试次数：").append(attempts).append('\n')
                    .append("处理：已跳过该批并继续Polling，等待对账后人工T+1修复。").append('\n')
                    .append("错误：").append(StringUtils.left(error, 800))
                    .toString();
            notifyService.send(new NotifyMessage(
                    "POLLING_BULK_FAILED_CONTINUED", tableName, title, content));
        } catch (Exception notifyError) {
            log.error("===> ES-Polling notify bulk failure failed before send. "
                            + "table={}, date={}, firstId={}, lastId={}, error={}",
                    tableName, syncDate, firstId, lastId,
                    notifyError.getMessage(), notifyError);
        }
    }
}
