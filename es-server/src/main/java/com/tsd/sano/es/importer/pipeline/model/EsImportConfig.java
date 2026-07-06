package com.tsd.sano.es.importer.pipeline.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 单次ES导入任务配置。
 *
 * <p>一个对象代表一张业务表在某一天的导入参数。</p>
 *
 * @author lxw
 */
@Getter
@Setter
public class EsImportConfig {

    /**
     * ES业务alias。
     */
    private String indexAlias;

    /**
     * 本次写入的真实ES索引名。
     */
    private String indexName;

    /**
     * resources/esmapping目录下的mapping文件名。
     */
    private String mappingFile;

    /**
     * MySQL源表名。
     */
    private String tableName;

    /**
     * 导入业务日期。
     */
    private LocalDate importDate;

    /**
     * 可选SQL条件；为空时按dtColumn = importDate过滤。
     */
    private String whereSql;

    /**
     * 主键字段，用于游标分页和ES文档ID。
     */
    private String idColumn = "id";

    /**
     * 分区日期字段，whereSql为空时默认按该字段做T+1过滤。
     */
    private String dtColumn = "dt";

    /**
     * 起始游标ID，续跑时从last_success_id之后继续读取。
     */
    private long startId;

    /**
     * 导入完成是否删除该表历史索引。
     */
    private boolean deleteHistoryIndex;

    /**
     * 该表历史索引保留天数。
     */
    private int reserveDays = 30;
}
