package com.tsd.sano.es.importer.notify;

import com.tsd.sano.es.core.config.NotifyProperties;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.pipeline.model.ImportStatistics;
import com.tsd.sano.es.importer.taskstore.model.SanoImportTask;
import com.tsd.sano.es.reconcile.model.ReconcileResult;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 导入任务通知服务。
 *
 * <p>T+1任务结束、Polling异常批次和对账结果按各自业务边界发送通知。
 * 通知失败只记录日志，不影响同步状态和游标推进。</p>
 *
 * @author lxw
 */
@Service
public class ImportNotifyService {

    private static final Logger log = LoggerFactory.getLogger(ImportNotifyService.class);

    /**
     * 导入消息标题前缀，由业务通知服务定义，不属于通用消息通道配置。
     */
    private static final String SUBJECT_PREFIX = "[SANO-ES]";

    private final EsImportProperties importProperties;
    private final NotifyProperties notifyProperties;
    private final List<ImportNotifier> notifiers;

    /**
     * 注入导入参数、消息配置和所有通知渠道实现。
     */
    public ImportNotifyService(EsImportProperties importProperties,
                               NotifyProperties notifyProperties,
                               List<ImportNotifier> notifiers) {
        this.importProperties = importProperties;
        this.notifyProperties = notifyProperties;
        this.notifiers = notifiers;
    }

    /**
     * 发送导入成功通知。
     */
    public void notifySuccess(SanoImportTask task, ImportStatistics statistics) {
        try {
            if (!notifyProperties.isEnabled()) {
                return;
            }

            String title = buildTitle("ES导入成功", task);
            long total = task.getTotalCount();
            long success = task.getSuccessCount();
            long failed = task.getFailedCount();
            long bulkCount = statistics == null ? 0L : statistics.getBulkCount().get();

            StringBuilder content = new StringBuilder(384);
            content.append(title).append('\n')
                    .append("任务ID：").append(task.getTaskId()).append('\n')
                    .append("表名：").append(task.getTableName()).append('\n')
                    .append("索引：").append(task.getIndexName()).append('\n')
                    .append("业务日期：").append(task.getImportDate()).append('\n')
                    .append("结果：成功").append('\n')
                    .append("总数：").append(total).append('\n')
                    .append("成功：").append(success).append('\n')
                    .append("失败：").append(failed).append('\n')
                    .append("Bulk次数：").append(bulkCount).append('\n')
                    .append("安全断点ID：").append(task.getLastSuccessId()).append('\n')
                    .append("耗时ms：").append(costMs(task));

            dispatch(new ImportNotifyMessage("SUCCESS", title, content.toString()), taskId(task));
        } catch (Exception e) {
            // 通知入口整体隔离，避免消息组装或配置异常影响导入主流程。
            log.error("===> ES-Import notify success failed before send. taskId={}, error={}",
                    taskId(task), e.getMessage(), e);
        }
    }

    /**
     * 发送导入失败通知。
     */
    public void notifyFailed(SanoImportTask task, ImportStatistics statistics, Throwable error) {
        try {
            if (!notifyProperties.isEnabled()) {
                return;
            }

            String title = buildTitle("ES导入失败", task);
            long currentRead = statistics == null ? 0L : statistics.getRead().get();
            long currentSuccess = statistics == null ? 0L : statistics.getSuccess().get();
            long currentFailed = statistics == null ? 0L : statistics.getFailed().get();
            String errorMessage = error == null ? task.getLastError() : error.getMessage();

            StringBuilder content = new StringBuilder(512);
            content.append(title).append('\n')
                    .append("任务ID：").append(task.getTaskId()).append('\n')
                    .append("表名：").append(task.getTableName()).append('\n')
                    .append("索引：").append(task.getIndexName()).append('\n')
                    .append("业务日期：").append(task.getImportDate()).append('\n')
                    .append("结果：失败").append('\n')
                    .append("总数：").append(task.getTotalCount()).append('\n')
                    .append("本次读取：").append(currentRead).append('\n')
                    .append("本次成功：").append(currentSuccess).append('\n')
                    .append("本次失败：").append(currentFailed).append('\n')
                    .append("累计成功：").append(task.getSuccessCount()).append('\n')
                    .append("累计失败：").append(task.getFailedCount()).append('\n')
                    .append("安全断点ID：").append(task.getLastSuccessId()).append('\n')
                    .append("执行次数：").append(task.getRunCount()).append('\n')
                    .append("耗时ms：").append(costMs(task)).append('\n')
                    .append("错误：").append(StringUtils.left(errorMessage, 800));

            dispatch(new ImportNotifyMessage("FAILED", title, content.toString()), taskId(task));
        } catch (Exception e) {
            // 通知入口整体隔离，避免失败告警异常反向覆盖原始导入异常。
            log.error("===> ES-Import notify failure failed before send. taskId={}, error={}",
                    taskId(task), e.getMessage(), e);
        }
    }

    /**
     * 发送导入超时暂停通知。
     */
    public void notifyTimeoutPartial(SanoImportTask task, ImportStatistics statistics) {
        try {
            if (!notifyProperties.isEnabled()) {
                return;
            }

            String title = buildTitle("ES导入暂停", task);
            long total = task.getTotalCount();
            long success = task.getSuccessCount();
            long failed = task.getFailedCount();
            long remaining = Math.max(total - success - failed, 0L);
            long currentRead = statistics == null ? 0L : statistics.getRead().get();
            long currentSuccess = statistics == null ? 0L : statistics.getSuccess().get();
            long currentFailed = statistics == null ? 0L : statistics.getFailed().get();

            StringBuilder content = new StringBuilder(512);
            content.append(title).append('\n')
                    .append("任务ID：").append(task.getTaskId()).append('\n')
                    .append("表名：").append(task.getTableName()).append('\n')
                    .append("索引：").append(task.getIndexName()).append('\n')
                    .append("业务日期：").append(task.getImportDate()).append('\n')
                    .append("结果：达到运行时长，已暂停").append('\n')
                    .append("总数：").append(total).append('\n')
                    .append("累计成功：").append(success).append('\n')
                    .append("累计失败：").append(failed).append('\n')
                    .append("剩余：").append(remaining).append('\n')
                    .append("本次读取：").append(currentRead).append('\n')
                    .append("本次成功：").append(currentSuccess).append('\n')
                    .append("本次失败：").append(currentFailed).append('\n')
                    .append("安全断点ID：").append(task.getLastSuccessId()).append('\n')
                    .append("最大运行分钟：")
                    .append(importProperties.getTPlusOne().getMaxRunMinutes()).append('\n')
                    .append("耗时ms：").append(costMs(task)).append('\n')
                    .append("说明：已停止继续读取MySQL，剩余数据下次任务继续同步。");

            dispatch(new ImportNotifyMessage("TIMEOUT_PARTIAL", title, content.toString()), taskId(task));
        } catch (Exception e) {
            // 通知入口整体隔离，超时暂停状态以任务索引为准，通知失败只记录日志。
            log.error("===> ES-Import notify timeout partial failed before send. taskId={}, error={}",
                    taskId(task), e.getMessage(), e);
        }
    }

    /**
     * Polling表因系统性错误持久暂停后发送一次停止通知。
     *
     * <p>通知失败只记录日志，不能改变已经保存的PAUSED状态。</p>
     */
    public void notifyPollingStopped(String tableName, String indexName,
                                     LocalDate syncDate, long lastId, Throwable error) {
        try {
            if (!notifyProperties.isEnabled()) {
                return;
            }

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
            dispatch(new ImportNotifyMessage("POLLING_STOPPED", title, content), tableName);
        } catch (Exception notifyError) {
            log.error("===> ES-Polling notify stopped failed before send. table={}, error={}",
                    tableName, notifyError.getMessage(), notifyError);
        }
    }

    /**
     * 异步通知Polling整批重试耗尽，但不暂停该表。
     *
     * <p>失败批次已经按最大ID推进，后续由统计对账发现差异，并由运维提交指定日期
     * T+1全量修复。通知提交或发送失败均不能阻塞Polling主循环。</p>
     */
    @Async("esReconcileExecutor")
    public void notifyPollingBulkFailed(String tableName, String indexName,
                                        LocalDate syncDate, long firstId, long lastId,
                                        int attempts, String error) {
        try {
            if (!notifyProperties.isEnabled()) {
                return;
            }

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
            dispatch(new ImportNotifyMessage(
                    "POLLING_BULK_FAILED_CONTINUED", title, content), tableName);
        } catch (Exception notifyError) {
            log.error("===> ES-Polling notify bulk failure failed before send. "
                            + "table={}, date={}, firstId={}, lastId={}, error={}",
                    tableName, syncDate, firstId, lastId,
                    notifyError.getMessage(), notifyError);
        }
    }

    /**
     * 发送单表单日统计对账结果。
     *
     * <p>匹配、不匹配和执行失败都发送消息；通知通道失败不能改变同步及对账结果。</p>
     */
    public void notifyReconcileResult(ReconcileResult result) {
        try {
            if (!notifyProperties.isEnabled()) {
                return;
            }

            String title = SUBJECT_PREFIX + " ES数据对账 " + result.status() + " " + result.indexName();
            StringBuilder content = new StringBuilder(480)
                    .append(title).append('\n')
                    .append("表名：").append(result.tableName()).append('\n')
                    .append("索引：").append(result.indexName()).append('\n')
                    .append("业务日期：").append(result.reconcileDate()).append('\n')
                    .append("结果：").append(result.status()).append('\n')
                    .append("MySQL：").append(formatStatistics(result.mysql())).append('\n')
                    .append("Elasticsearch：").append(formatStatistics(result.elasticsearch()));
            if (StringUtils.isNotBlank(result.error())) {
                content.append('\n').append("错误：").append(StringUtils.left(result.error(), 800));
            }
            dispatch(new ImportNotifyMessage(
                    "RECONCILE_" + result.status().name(), title, content.toString()), result.indexName());
        } catch (Exception notifyError) {
            log.error("===> ES-Reconcile notify failed before send. table={}, date={}, error={}",
                    result == null ? null : result.tableName(),
                    result == null ? null : result.reconcileDate(),
                    notifyError.getMessage(),
                    notifyError);
        }
    }

    /**
     * 分发通知到所有启用的通知渠道。
     */
    private void dispatch(ImportNotifyMessage message, String businessId) {
        if (notifiers == null || notifiers.isEmpty()) {
            log.warn("===> ES-Import notify skipped because no notifier bean exists. eventType={}, businessId={}",
                    message.getEventType(), businessId);
            return;
        }

        for (ImportNotifier notifier : notifiers) {
            try {
                notifier.send(message);
            } catch (Exception e) {
                // 通知失败不能影响导入任务状态，避免告警链路反向拖垮同步链路。
                log.error("===> ES-Import notify failed. eventType={}, businessId={}, notifier={}, error={}",
                        message.getEventType(), businessId, notifier.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 格式化单侧对账统计，未成功取得时明确显示不可用。
     */
    private String formatStatistics(ReconcileResult.Statistics statistics) {
        if (statistics == null) {
            return "N/A";
        }
        return "count=" + statistics.count()
                + ", minId=" + statistics.minId()
                + ", maxId=" + statistics.maxId();
    }

    /**
     * 构建通知标题。
     */
    private String buildTitle(String eventTitle, SanoImportTask task) {
        return SUBJECT_PREFIX + " " + eventTitle + " " + task.getIndexName();
    }

    /**
     * 计算本次任务执行耗时。
     */
    private long costMs(SanoImportTask task) {
        if (task == null || task.getStartedAt() == null || task.getFinishedAt() == null) {
            return 0L;
        }
        return Duration.between(task.getStartedAt(), task.getFinishedAt()).toMillis();
    }

    /**
     * 安全获取任务ID，避免通知异常日志再次触发空指针。
     */
    private String taskId(SanoImportTask task) {
        return task == null ? "null" : task.getTaskId();
    }
}
