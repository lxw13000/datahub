package com.tsd.sano.es.importer.notify;

import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.pipeline.model.ImportStatistics;
import com.tsd.sano.es.importer.taskstore.model.SanoImportTask;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 导入任务通知服务。
 *
 * <p>每个任务结束后按结果发送一条通知，通知失败只记录日志，不影响任务状态。</p>
 *
 * @author lxw
 */
@Service
public class ImportNotifyService {

    private static final Logger log = LoggerFactory.getLogger(ImportNotifyService.class);

    private final EsImportProperties properties;
    private final List<ImportNotifier> notifiers;

    /**
     * 注入通知配置和所有通知渠道实现。
     */
    public ImportNotifyService(EsImportProperties properties, List<ImportNotifier> notifiers) {
        this.properties = properties;
        this.notifiers = notifiers;
    }

    /**
     * 发送导入成功通知。
     */
    public void notifySuccess(SanoImportTask task, ImportStatistics statistics) {
        try {
            EsImportProperties.NotifyConfig notify = properties.getNotify();
            if (notify == null || !notify.isEnabled() || !notify.isSuccessEnabled()) {
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
                    .append("最后成功ID：").append(task.getLastSuccessId()).append('\n')
                    .append("耗时ms：").append(costMs(task));

            dispatch(new ImportNotifyMessage("SUCCESS", title, content.toString()), task);
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
            EsImportProperties.NotifyConfig notify = properties.getNotify();
            if (notify == null || !notify.isEnabled() || !notify.isFailureEnabled()) {
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
                    .append("最后成功ID：").append(task.getLastSuccessId()).append('\n')
                    .append("执行次数：").append(task.getRunCount()).append('\n')
                    .append("耗时ms：").append(costMs(task)).append('\n')
                    .append("错误：").append(StringUtils.left(errorMessage, 800));

            dispatch(new ImportNotifyMessage("FAILED", title, content.toString()), task);
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
            EsImportProperties.NotifyConfig notify = properties.getNotify();
            if (notify == null || !notify.isEnabled() || !notify.isTimeoutEnabled()) {
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
                    .append("最后成功ID：").append(task.getLastSuccessId()).append('\n')
                    .append("最大运行分钟：").append(properties.getMaxRunMinutes()).append('\n')
                    .append("耗时ms：").append(costMs(task)).append('\n')
                    .append("说明：已停止继续读取MySQL，剩余数据下次任务继续同步。");

            dispatch(new ImportNotifyMessage("TIMEOUT_PARTIAL", title, content.toString()), task);
        } catch (Exception e) {
            // 通知入口整体隔离，超时暂停状态以任务索引为准，通知失败只记录日志。
            log.error("===> ES-Import notify timeout partial failed before send. taskId={}, error={}",
                    taskId(task), e.getMessage(), e);
        }
    }

    /**
     * 分发通知到所有启用的通知渠道。
     */
    private void dispatch(ImportNotifyMessage message, SanoImportTask task) {
        if (notifiers == null || notifiers.isEmpty()) {
            log.warn("===> ES-Import notify skipped because no notifier bean exists. eventType={}, taskId={}",
                    message.getEventType(), taskId(task));
            return;
        }

        for (ImportNotifier notifier : notifiers) {
            try {
                notifier.send(message);
            } catch (Exception e) {
                // 通知失败不能影响导入任务状态，避免告警链路反向拖垮同步链路。
                log.error("===> ES-Import notify failed. eventType={}, taskId={}, notifier={}, error={}",
                        message.getEventType(), taskId(task), notifier.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 构建通知标题。
     */
    private String buildTitle(String eventTitle, SanoImportTask task) {
        return StringUtils.defaultString(properties.getNotify().getSubjectPrefix()) + " "
                + eventTitle + " " + task.getIndexName();
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
