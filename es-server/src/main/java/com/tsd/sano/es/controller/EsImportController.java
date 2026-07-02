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
 * 提供数据库连接、元数据查询、数据导入ES等接口
 * <p>
 * 主要功能：
 * 1. 获取数据库连接列表
 * 2. 查询数据库、表、字段元数据
 * 3. 查询表数据预览
 * 4. 异步导入数据到ES
 * 5. 查询导入任务进度
 *
 * @author lxw
 * @version V1.1
 * @date 2024-7-18
 * @updated 2025-10-31 新增任务进度查询接口
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
            boolean submitted = importTask.importAppointDay(importDate);
            return ResultVO.resultMsg(submitted, "指定日期导入任务提交");
        } catch (DateTimeParseException e) {
            throw new ServiceException("导入日期格式错误，请使用yyyyMMdd，例如：20260701");
        }
    }

}
