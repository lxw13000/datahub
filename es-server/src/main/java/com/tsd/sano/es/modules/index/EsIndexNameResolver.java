package com.tsd.sano.es.modules.index;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.IndexMode;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 统一任务、导入及对账的物理索引命名，不执行ES远程操作或数据迁移。
 */
public final class EsIndexNameResolver {

    /** 日索引后缀，例如20261015。 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 月索引后缀，例如202610。 */
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 年索引后缀，例如2026。 */
    private static final DateTimeFormatter YEAR_FORMAT = DateTimeFormatter.ofPattern("yyyy");

    private EsIndexNameResolver() {
    }

    /**
     * 按配置计算目标名称；SINGLE无需业务日期，其余模式必须提供日期。
     *
     * @param mode           索引组织方式，未指定时兼容旧配置使用DAY
     * @param alias          业务Alias，也是日期索引的名称前缀
     * @param fixedIndexName SINGLE的显式物理索引名
     * @param businessDate   本次任务或对账的业务日期
     */
    public static String resolve(IndexMode mode, String alias, String fixedIndexName, LocalDate businessDate) {
        IndexMode indexMode = mode == null ? IndexMode.DAY : mode;
        if (indexMode != IndexMode.SINGLE && (businessDate == null || StringUtils.isBlank(alias))) {
            throw new ServiceException("ES date index requires indexAlias and businessDate");
        }
        return switch (indexMode) {
            case DAY -> alias.trim() + "_" + DAY_FORMAT.format(businessDate);
            case MONTH -> alias.trim() + "_" + MONTH_FORMAT.format(businessDate);
            case YEAR -> alias.trim() + "_" + YEAR_FORMAT.format(businessDate);
            case SINGLE -> {
                if (StringUtils.isBlank(fixedIndexName)) {
                    throw new ServiceException("ES SINGLE fixed-index-name cannot be blank");
                }
                yield fixedIndexName.trim();
            }
        };
    }
}
