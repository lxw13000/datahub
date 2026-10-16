package com.tsd.sano.es.modules.config;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 单张业务表的同步配置。
 */
@Getter
@Setter
public class SyncTableConfig {

    /**
     * 是否启用该表。
     */
    private boolean enabled = true;

    /**
     * 是否执行该表的异步统计对账；同步流程仍统一调用，由对账入口决定是否执行。
     */
    private boolean reconcile = true;

    /**
     * MySQL源表名，也是同步配置和任务的表标识。
     */
    private String tableName;

    /**
     * ES业务Alias，提供统一查询入口；未配置时默认等于tableName。
     */
    private String indexAlias;

    /**
     * resources/esmapping目录下的Mapping文件名。
     */
    private String mappingFile;

    /**
     * ES文档ID字段，按配置顺序以“_”连接；未配置或集合为空时使用idColumn。
     * 字段组合必须在目标物理索引内唯一，且各字段值不能为null或空白。
     */
    private List<String> documentIdColumns = List.of();

    /**
     * 物理索引组织方式；不配置时保持现有按日索引行为。
     */
    private IndexMode indexMode = IndexMode.DAY;

    /** SINGLE模式的固定物理索引名，不能与业务Alias同名。 */
    private String fixedIndexName;

    /**
     * 每日读取方式，默认CURSOR兼容已有表；FULL_DAY整日读取并在未完成时整日重放。
     * SINGLE人工Bootstrap始终保留原有游标分页，不受每日读取方式影响。
     */
    private ReadMode readMode = ReadMode.CURSOR;

    /**
     * CURSOR的MySQL数值游标字段，同时用于对账最小/最大ID；未配置documentIdColumns时也作为ES文档ID。
     */
    private String idColumn = "id";

    /**
     * 每日同步日期字段；需要发现修改时，应配置会随修改更新的时间字段。
     */
    private String dtColumn = "dt";

    /**
     * 日期字段类型；DATE/INTEGER_DATE按等值查询，DATETIME按当天左闭右开范围查询。
     */
    private DateColumnType dtColumnType = DateColumnType.DATE;

    /**
     * 是否自动删除到期日索引；仅DAY模式且保留天数大于0时生效。
     */
    private boolean deleteHistoryIndex = false;

    /**
     * 日索引保留天数；未配置或小于等于0时永久保留，非DAY模式始终不自动删除。
     */
    private Integer reserveDays;

    /**
     * 规范化并完整校验当前表的静态配置。
     *
     * <p>该方法在配置绑定阶段对启用表执行；运行时读取到的具体数据类型仍由Reader校验。</p>
     */
    void normalizeAndValidate() {
        String normalizedTableName = StringUtils.trimToEmpty(tableName);
        String normalizedIndexAlias =
                StringUtils.defaultIfBlank(indexAlias, normalizedTableName).trim();
        String normalizedMappingFile = StringUtils.trimToEmpty(mappingFile);
        String normalizedIdColumn = StringUtils.trimToEmpty(idColumn);
        String normalizedDtColumn = StringUtils.trimToEmpty(dtColumn);
        String normalizedFixedIndexName = StringUtils.trimToNull(fixedIndexName);
        IndexMode normalizedIndexMode = indexMode == null ? IndexMode.DAY : indexMode;

        validateText(normalizedTableName, "table-name");
        validateText(normalizedIndexAlias, "index-alias");
        validateText(normalizedMappingFile, "mapping-file");
        validateText(normalizedIdColumn, "id-column");
        validateText(normalizedDtColumn, "dt-column");

        // 保留空集合的默认语义，不把分页游标和复合文档ID绑定为同一种职责。
        List<String> normalizedDocumentIdColumns = documentIdColumns == null ? List.of()
                : documentIdColumns.stream().map(StringUtils::trimToEmpty).toList();
        for (String column : normalizedDocumentIdColumns) {
            validateText(column, "document-id-columns entry");
        }

        if (normalizedIndexMode == IndexMode.SINGLE) {
            validateText(normalizedFixedIndexName, "fixed-index-name");
            if (StringUtils.equals(normalizedFixedIndexName, normalizedIndexAlias)) {
                throw new IllegalStateException("ES SINGLE fixed-index-name cannot equal index-alias, tableName="
                        + normalizedTableName);
            }
        }

        tableName = normalizedTableName;
        indexAlias = normalizedIndexAlias;
        mappingFile = normalizedMappingFile;
        documentIdColumns = normalizedDocumentIdColumns;
        idColumn = normalizedIdColumn;
        dtColumn = normalizedDtColumn;
        // 枚举取值由Spring配置绑定校验，此处仅补全默认值，不重复转换或校验字符串。
        dtColumnType = dtColumnType == null ? DateColumnType.DATE : dtColumnType;
        indexMode = normalizedIndexMode;
        readMode = readMode == null ? ReadMode.CURSOR : readMode;
        fixedIndexName = normalizedFixedIndexName;
    }

    /**
     * 校验表配置中的必填文本。
     */
    private void validateText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("ES sync table " + fieldName + " cannot be blank");
        }
    }
}
