package com.tsd.sano.es.controller;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.config.EsServiceModeManager;
import com.tsd.sano.es.modules.config.SyncTableConfig;
import com.tsd.sano.es.modules.index.EsIndexManager;
import com.tsd.sano.es.modules.polling.service.PollingIndexService;
import com.tsd.sano.es.modules.reconcile.service.ReconcileStatisticsService;
import com.tsd.sano.es.modules.tplusone.model.SanoImportTask;
import com.tsd.sano.es.modules.tplusone.service.SanoImportTaskService;
import com.tsd.sano.es.modules.tplusone.service.TPlusOneImportTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;

/**
 * ES数据导入控制器
 *
 * @author lxw
 * @version V1.1
 * @date 2024-7-18
 */
@RestController
@RequestMapping("/import")
@RequiredArgsConstructor
@Slf4j
public class EsImportController {

    /**
     * 手动导入接口日期格式
     */
    private static final DateTimeFormatter IMPORT_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;


    /**
     * T+1及Polling历史修复共用的持久任务服务
     */
    private final SanoImportTaskService importTaskService;

    /**
     * T+1任务编排及Polling历史修复入口
     */
    private final TPlusOneImportTask importTask;

    /**
     * 启用表及其同步模式配置
     */
    private final EsImportProperties importProperties;

    /**
     * 物理索引存在性检查组件
     */
    private final EsIndexManager indexManager;

    /**
     * 独立异步统计对账入口
     */
    private final ReconcileStatisticsService reconcileStatisticsService;

    /**
     * all/query实例角色门禁
     */
    private final EsServiceModeManager serviceModeManager;

    /**
     * Polling日期索引和checkpoint管理服务，控制器仅调用其人工初始化能力。
     */
    private final PollingIndexService pollingIndexService;

    /**
     * 创建T+1任务索引；query模式或T+1总开关关闭时拒绝执行
     */
    @GetMapping("/createImportTaskIndex")
    public ResultVO<String> createImportTaskIndex() {
        importTask.requireEnabled();
        importTaskService.createIndex();

        return ResultVO.successMessage("创建导入任务索引");
    }

    /**
     * 手动补指定日期的数据，日期格式为yyyyMMdd
     */
    @GetMapping("/importAppointDay")
    public ResultVO<String> importAppointDay(String date) {
        if (StringUtils.isBlank(date)) {
            throw new ServiceException("导入日期不能为空，请使用yyyyMMdd，例如：20260701");
        }
        try {
            LocalDate importDate = LocalDate.parse(date, IMPORT_DATE_FORMATTER);
            boolean submitted = importTask.importDateRange(importDate, importDate);
            return ResultVO.resultMsg(submitted, "指定日期导入任务提交");
        } catch (DateTimeParseException e) {
            throw new ServiceException("导入日期格式错误，请使用yyyyMMdd，例如：20260701");
        }
    }

    /**
     * 手动补指定日期段的数据，日期格式为yyyyMMdd，起止日期均包含
     */
    @GetMapping("/importDateRange")
    public ResultVO<String> importDateRange(String startDate, String endDate) {
        if (StringUtils.isBlank(startDate) || StringUtils.isBlank(endDate)) {
            throw new ServiceException("导入日期段不能为空，请使用yyyyMMdd，例如：startDate=20260601&endDate=20260605");
        }
        try {
            LocalDate start = LocalDate.parse(startDate, IMPORT_DATE_FORMATTER);
            LocalDate end = LocalDate.parse(endDate, IMPORT_DATE_FORMATTER);
            if (start.isAfter(end)) {
                throw new ServiceException("开始日期不能大于结束日期");
            }
            if (end.isAfter(LocalDate.now())) {
                throw new ServiceException("结束日期不能是未来时间");
            }
            boolean submitted = importTask.importDateRange(start, end);
            return ResultVO.resultMsg(submitted, "指定日期段导入任务提交");
        } catch (DateTimeParseException e) {
            throw new ServiceException("导入日期格式错误，请使用yyyyMMdd，例如：startDate=20260601&endDate=20260605");
        }
    }

    /**
     * 手动补指定单表的完整日期段数据
     *
     * <p>调用方确认日期段未同步后，接口直接创建该表对应的PENDING任务并触发待任务队列扫描</p>
     */
    @GetMapping("/importTableDateRange")
    public ResultVO<String> importTableDateRange(String tableName, String startDate, String endDate) {
        if (StringUtils.isBlank(tableName)) {
            throw new ServiceException("导入表tableName不能为空，例如：sano_game_record");
        }
        if (StringUtils.isBlank(startDate) || StringUtils.isBlank(endDate)) {
            throw new ServiceException("导入日期段不能为空，请使用yyyyMMdd，例如：startDate=20260601&endDate=20260605");
        }

        try {
            LocalDate start = LocalDate.parse(startDate, IMPORT_DATE_FORMATTER);
            LocalDate end = LocalDate.parse(endDate, IMPORT_DATE_FORMATTER);
            if (start.isAfter(end)) {
                throw new ServiceException("开始日期不能大于结束日期");
            }
            if (end.isAfter(LocalDate.now())) {
                throw new ServiceException("结束日期不能是未来时间");
            }

            String result = importTask.importTableDateRange(tableName.trim(), start, end);
            return ResultVO.successMessage(result);
        } catch (DateTimeParseException e) {
            throw new ServiceException("导入日期格式错误，请使用yyyyMMdd，例如：startDate=20260601&endDate=20260605");
        }
    }

    /**
     * 创建Polling checkpoint内部索引；保留版本B既有接口路径供部署初始化使用
     */
    @GetMapping("/createSyncInternalIndices")
    public ResultVO<String> createSyncInternalIndices() {
        serviceModeManager.requireSyncEnabled();
        boolean created = pollingIndexService.createCheckpointIndex();
        return ResultVO.resultMsg(created, "Polling 创建同步内部索引");
    }

    /**
     * 人工提交指定启用表、指定日期的异步统计对账
     *
     * <p>接口只校验配置和物理索引，不创建持久任务；是否真正执行由单表reconcile配置决定</p>
     */
    @GetMapping("/reconcile")
    public ResultVO<String> reconcile(String tableName, String date) {
        serviceModeManager.requireSyncEnabled();
        if (StringUtils.isBlank(tableName)) {
            throw new ServiceException("对账表tableName不能为空，例如：sano_wallet_coin_record");
        }
        if (StringUtils.isBlank(date)) {
            throw new ServiceException("对账日期不能为空，请使用yyyyMMdd，例如：20260701");
        }

        try {
            LocalDate reconcileDate = LocalDate.parse(date, IMPORT_DATE_FORMATTER);
            String normalizedTableName = tableName.trim();
            SyncTableConfig table = Stream.concat(
                            importProperties.getTPlusOneTables().stream(),
                            importProperties.getPollingTables().stream())
                    .filter(config -> StringUtils.equals(config.getTableName(), normalizedTableName))
                    .findFirst()
                    .orElseThrow(() -> new ServiceException(
                            "ES sync table is disabled or does not exist, tableName=" + normalizedTableName));
            String indexName = table.getIndexAlias() + "_" + IMPORT_DATE_FORMATTER.format(reconcileDate);
            if (!indexManager.exists(indexName)) {
                throw new ServiceException("Reconcile physical index does not exist, index=" + indexName);
            }

            reconcileStatisticsService.reconcile(table, reconcileDate);
            return ResultVO.successMessage("对账请求已提交；是否执行由该表reconcile配置决定index=" + indexName);
        } catch (DateTimeParseException error) {
            throw new ServiceException("对账日期格式错误，请使用yyyyMMdd，例如：20260701");
        }
    }

    /**
     * 为Polling表已经关闭的历史日期提交T+1全量覆盖修复
     */
    @GetMapping("/repairPollingDate")
    public ResultVO<String> repairPollingDate(String tableName, String date) {
        if (StringUtils.isBlank(tableName)) {
            throw new ServiceException("修复表tableName不能为空，例如：sano_wallet_coin_record");
        }
        if (StringUtils.isBlank(date)) {
            throw new ServiceException("修复日期不能为空，请使用yyyyMMdd，例如：20260701");
        }

        try {
            LocalDate repairDate = LocalDate.parse(date, IMPORT_DATE_FORMATTER);
            String result = importTask.repairPollingDate(tableName.trim(), repairDate);
            return ResultVO.successMessage(result);
        } catch (DateTimeParseException error) {
            throw new ServiceException("修复日期格式错误，请使用yyyyMMdd，例如：20260701");
        }
    }

    /**
     * 查询指定Polling表、指定日期复用的持久导入任务
     */
    @GetMapping("/pollingRepairTask")
    public ResultVO<SanoImportTask> pollingRepairTask(String tableName, String date) {
        if (StringUtils.isBlank(tableName)) {
            throw new ServiceException("修复表tableName不能为空，例如：sano_wallet_coin_record");
        }
        if (StringUtils.isBlank(date)) {
            throw new ServiceException("修复日期不能为空，请使用yyyyMMdd，例如：20260701");
        }

        try {
            String normalizedTableName = tableName.trim();
            LocalDate repairDate = LocalDate.parse(date, IMPORT_DATE_FORMATTER);
            boolean pollingTable = importProperties.getPollingTables().stream()
                    .anyMatch(config -> StringUtils.equals(config.getTableName(), normalizedTableName));
            if (!pollingTable) {
                throw new ServiceException(
                        "ES sync table is disabled or mode mismatch, tableName=" + normalizedTableName
                                + ", expectedMode=POLLING");
            }

            String taskId = normalizedTableName + "_" + IMPORT_DATE_FORMATTER.format(repairDate);
            SanoImportTask task = importTaskService.getTask(taskId)
                    .orElseThrow(() -> new ServiceException(
                            "Polling repair task does not exist, taskId=" + taskId));
            return ResultVO.success(task);
        } catch (DateTimeParseException error) {
            throw new ServiceException("修复日期格式错误，请使用yyyyMMdd，例如：20260701");
        }
    }

}
