package com.tsd.sano.es.importer1;

import com.tsd.sano.es.importer.service.EsImportService;
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
    private final EsImportService importService;


    @GetMapping("/test1")
    public void getProgress() {
        esProcessService.toLeadEsData("sano_wallet_coin_record", "sano_wallet_coin_record.json");
    }


    @GetMapping("/test2")
    public void getProgress2(String date) {
        importService.importAppointDay("sano_wallet_coin_record", "sano_wallet_coin_record.json", date);
    }

}