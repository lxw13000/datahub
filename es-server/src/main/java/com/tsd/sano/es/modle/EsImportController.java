package com.tsd.sano.es.modle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final EsProcessServiceImpl esProcessService;


    @GetMapping("/test1")
    public void getProgress() {
        esProcessService.initEsData("sano_wallet_coin_record", "sano_wallet_coin_record.json");
    }

}