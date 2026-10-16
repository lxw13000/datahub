package com.tsd.sano.es.modules.tplusone.model;

import com.tsd.sano.es.modules.config.IndexMode;
import com.tsd.sano.es.modules.config.DateColumnType;
import com.tsd.sano.es.modules.config.ReadMode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

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

    /** ES文档ID字段，按顺序以“_”连接；未配置或集合为空时使用idColumn。 */
    private List<String> documentIdColumns = List.of();

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

    /** 每日读取方式，默认游标分页；FULL_DAY不使用ID断点，SINGLE Bootstrap仍按游标分页。 */
    private ReadMode readMode = ReadMode.CURSOR;

    /**
     * CURSOR的MySQL数值游标字段，同时用于对账最小/最大ID；未配置documentIdColumns时也作为ES文档ID。
     */
    private String idColumn = "id";

    /**
     * 每日任务的日期过滤字段；Bootstrap不按该字段过滤。
     */
    private String dtColumn = "dt";

    /**
     * 每日日期条件类型，支持DATE、DATETIME及yyyyMMdd整数形式的INTEGER_DATE。
     */
    private DateColumnType dtColumnType = DateColumnType.DATE;

    /**
     * CURSOR起始游标ID，续跑时从连续安全断点之后读取；FULL_DAY每日任务始终归零并整日重读。
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
