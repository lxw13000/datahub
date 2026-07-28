package com.tsd.sano.es.modules.tplusone.service;

import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.notify.model.NotifyMessage;
import com.tsd.sano.es.modules.notify.service.NotifyService;
import com.tsd.sano.es.modules.tplusone.model.ImportStatistics;
import com.tsd.sano.es.modules.tplusone.model.SanoImportTask;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * T+1任务业务通知服务。
 *
 * <p>本服务决定T+1任务通知的标题和正文，公共NotifyService只负责通道分发。
 * 消息组装或发送异常只记录日志，不改变已经持久化的任务状态。</p>
 */
@Service
public class TPlusOneNotifyService {

    private static final Logger log = LoggerFactory.getLogger(TPlusOneNotifyService.class);

    /** T+1消息标题前缀。 */
    private static final String SUBJECT_PREFIX = "[SANO-ES]";

    /** 同步参数，用于超时通知展示任务运行上限。 */
    private final EsImportProperties properties;

    /** 公共通知通道服务。 */
    private final NotifyService notifyService;

    /**
     * 注入同步参数和公共通知服务。
     */
    public TPlusOneNotifyService(EsImportProperties properties, NotifyService notifyService) {
        this.properties = properties;
        this.notifyService = notifyService;
    }

    /**
     * 发送T+1任务成功通知。
     */
    public void notifySuccess(SanoImportTask task, ImportStatistics statistics) {
        try {
            String title = buildTitle("ES导入成功", task);
            long bulkCount = statistics == null ? 0L : statistics.getBulkCount().get();
            String content = new StringBuilder(384)
                    .append(title).append('\n')
                    .append("任务ID：").append(task.getTaskId()).append('\n')
                    .append("表名：").append(task.getTableName()).append('\n')
                    .append("索引：").append(task.getIndexName()).append('\n')
                    .append("业务日期：").append(task.getImportDate()).append('\n')
                    .append("结果：成功").append('\n')
                    .append("总数：").append(task.getTotalCount()).append('\n')
                    .append("成功：").append(task.getSuccessCount()).append('\n')
                    .append("失败：").append(task.getFailedCount()).append('\n')
                    .append("Bulk次数：").append(bulkCount).append('\n')
                    .append("安全断点ID：").append(task.getLastSuccessId()).append('\n')
                    .append("耗时ms：").append(costMs(task))
                    .toString();
            notifyService.send(new NotifyMessage("SUCCESS", taskId(task), title, content));
        } catch (Exception error) {
            log.error("===> ES-Import notify success failed before send. taskId={}, error={}",
                    taskId(task), error.getMessage(), error);
        }
    }

    /**
     * 发送T+1任务失败通知。
     */
    public void notifyFailed(SanoImportTask task, ImportStatistics statistics, Throwable taskError) {
        try {
            String title = buildTitle("ES导入失败", task);
            long currentRead = statistics == null ? 0L : statistics.getRead().get();
            long currentSuccess = statistics == null ? 0L : statistics.getSuccess().get();
            long currentFailed = statistics == null ? 0L : statistics.getFailed().get();
            String errorMessage = taskError == null ? task.getLastError() : taskError.getMessage();
            String content = new StringBuilder(512)
                    .append(title).append('\n')
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
                    .append("错误：").append(StringUtils.left(errorMessage, 800))
                    .toString();
            notifyService.send(new NotifyMessage("FAILED", taskId(task), title, content));
        } catch (Exception error) {
            log.error("===> ES-Import notify failure failed before send. taskId={}, error={}",
                    taskId(task), error.getMessage(), error);
        }
    }

    /**
     * 发送T+1任务达到运行上限后的暂停通知。
     */
    public void notifyTimeoutPartial(SanoImportTask task, ImportStatistics statistics) {
        try {
            String title = buildTitle("ES导入暂停", task);
            long total = task.getTotalCount();
            long success = task.getSuccessCount();
            long failed = task.getFailedCount();
            long remaining = Math.max(total - success - failed, 0L);
            long currentRead = statistics == null ? 0L : statistics.getRead().get();
            long currentSuccess = statistics == null ? 0L : statistics.getSuccess().get();
            long currentFailed = statistics == null ? 0L : statistics.getFailed().get();
            String content = new StringBuilder(512)
                    .append(title).append('\n')
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
                    .append(properties.getTPlusOne().getMaxRunMinutes()).append('\n')
                    .append("耗时ms：").append(costMs(task)).append('\n')
                    .append("说明：已停止继续读取MySQL，剩余数据下次任务继续同步。")
                    .toString();
            notifyService.send(new NotifyMessage(
                    "TIMEOUT_PARTIAL", taskId(task), title, content));
        } catch (Exception error) {
            log.error("===> ES-Import notify timeout partial failed before send. taskId={}, error={}",
                    taskId(task), error.getMessage(), error);
        }
    }

    /**
     * 构建T+1消息标题。
     */
    private String buildTitle(String eventTitle, SanoImportTask task) {
        return SUBJECT_PREFIX + " " + eventTitle + " " + task.getIndexName();
    }

    /**
     * 计算当前任务执行耗时。
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
