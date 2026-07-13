package com.tsd.sano.es.controller;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.importer.task.EsImportTask;
import com.tsd.sano.es.importer.taskstore.SanoImportTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

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
     * 手动导入接口日期格式。
     */
    private static final DateTimeFormatter IMPORT_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;


    private final SanoImportTaskService importTaskService;

    private final EsImportTask importTask;

    @GetMapping("/createImportTaskIndex")
    public ResultVO<String> createImportTaskIndex() {
        boolean index = importTaskService.createIndex();

        return ResultVO.resultMsg(index, "创建导入任务索引");
    }

    /**
     * 手动补指定日期的数据，日期格式为yyyyMMdd。
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
     * 手动补指定日期段的数据，日期格式为yyyyMMdd，起止日期均包含。
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
     * 手动补指定单表的完整日期段数据。
     *
     * <p>调用方确认日期段未同步后，接口直接创建该表对应的PENDING任务并触发待任务队列扫描。</p>
     */
    @GetMapping("/importTableDateRange")
    public ResultVO<String> importTableDateRange(String indexAlias, String startDate, String endDate) {
        if (StringUtils.isBlank(indexAlias)) {
            throw new ServiceException("导入表indexAlias不能为空，例如：sano_game_record");
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

            String result = importTask.importTableDateRange(indexAlias.trim(), start, end);
            return ResultVO.successMessage(result);
        } catch (DateTimeParseException e) {
            throw new ServiceException("导入日期格式错误，请使用yyyyMMdd，例如：startDate=20260601&endDate=20260605");
        }
    }

}
