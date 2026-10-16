package com.tsd.sano.es.modules.tplusone.model;

import com.tsd.sano.es.modules.config.IndexMode;
import com.tsd.sano.es.modules.config.DateColumnType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 单次ES导入任务配置。
 *
 * <p>用于单表每日导入或SINGLE人工全量及其续跑。</p>
 *
 * @author lxw
 */
@Getter
@Setter
public class TPlusOneImportConfig {

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
     * 导入业务日期；仅经过任务身份校验的SINGLE Bootstrap允许为空。
     */
    private LocalDate importDate;

    /**
     * 物理索引组织方式，默认DAY兼容旧任务。
     */
    private IndexMode indexMode = IndexMode.DAY;

    /** SINGLE使用的显式固定物理索引名。 */
    private String fixedIndexName;

    /**
     * 本次是否为人工全量或其续跑；编排器校验固定任务ID及空日期后设置，不持久化为任务类型。
     */
    private boolean bootstrap;

    /**
     * 主键字段，用于游标分页和ES文档ID。
     */
    private String idColumn = "id";

    /**
     * 每日任务的日期过滤字段；Bootstrap不按该字段过滤。
     */
    private String dtColumn = "dt";

    /**
     * 分区日期字段类型，只支持DATE或DATETIME。
     */
    private DateColumnType dtColumnType = DateColumnType.DATE;

    /**
     * 起始游标ID，续跑时从任务保存的连续安全断点last_success_id之后继续读取。
     */
    private long startId;

    /**
     * 导入完成是否删除该表历史索引。
     */
    private boolean deleteHistoryIndex;

    /**
     * 日索引保留天数；为空或小于等于0时不自动删除。
     */
    private Integer reserveDays;
}
