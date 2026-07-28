package com.tsd.sano.es.modules.tplusone.service;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.index.EsIndexManager;
import com.tsd.sano.es.modules.tplusone.model.TPlusOneImportConfig;
import com.tsd.sano.es.modules.tplusone.model.ImportContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * T+1物理索引完整生命周期服务。
 *
 * <p>负责目标索引创建、导入前写入优化、导入后恢复、Alias绑定和历史索引清理。
 * 通用ES远程操作统一委托给EsIndexManager，T+1调用顺序和失败策略保留在本服务中。</p>
 */
@Service
public class TPlusOneIndexService {

    private static final Logger log = LoggerFactory.getLogger(TPlusOneIndexService.class);

    /** 每日物理索引名称中的日期格式。 */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /** 导入完成后恢复的默认刷新间隔。 */
    private static final String DEFAULT_REFRESH_INTERVAL = "1s";

    /** 当前单机ES部署恢复使用的默认副本数。 */
    private static final String DEFAULT_REPLICAS = "0";

    /** ES通用索引远程操作。 */
    private final EsIndexManager indexManager;

    /**
     * 注入通用索引操作服务。
     */
    public TPlusOneIndexService(EsIndexManager indexManager) {
        this.indexManager = indexManager;
    }

    /**
     * 判断T+1目标物理索引是否存在。
     */
    public boolean exists(String indexName) {
        return indexManager.exists(indexName);
    }

    /**
     * 创建本次T+1任务使用的物理索引。
     *
     * <p>创建阶段不绑定业务Alias，避免未完成导入的数据提前进入查询范围。</p>
     */
    public void createIndex(ImportContext context) {
        TPlusOneImportConfig config = requireConfig(context);
        String indexName = requireText(config.getIndexName(), "indexName");
        String mappingFile = requireText(config.getMappingFile(), "mappingFile");
        indexManager.createIndex(indexName, mappingFile);
        log.info("===> ES import create index success, index={}, mappingFile={}", indexName, mappingFile);
    }

    /**
     * 导入前优化索引参数，降低大批量写入成本。
     */
    public void beforeImport(ImportContext context) {
        TPlusOneImportConfig config = requireConfig(context);
        EsImportProperties properties = requireProperties(context);
        String indexName = requireText(config.getIndexName(), "indexName");
        if (properties.getTPlusOne().isDisableRefresh()) {
            indexManager.updateRefreshInterval(indexName, "-1");
        }
        if (properties.getTPlusOne().isDisableReplica()) {
            indexManager.updateReplicaCount(indexName, "0");
        }
    }

    /**
     * 导入完成后恢复索引参数，并刷新索引使数据可查询。
     */
    public void afterImport(ImportContext context) {
        TPlusOneImportConfig config = requireConfig(context);
        EsImportProperties properties = requireProperties(context);
        String indexName = requireText(config.getIndexName(), "indexName");
        if (properties.getTPlusOne().isDisableRefresh()) {
            indexManager.updateRefreshInterval(indexName, DEFAULT_REFRESH_INTERVAL);
        }
        if (properties.getTPlusOne().isDisableReplica()) {
            indexManager.updateReplicaCount(indexName, DEFAULT_REPLICAS);
        }
        indexManager.refresh(indexName);
    }

    /**
     * 为已完成导入的物理索引绑定业务Alias。
     */
    public void bindAlias(ImportContext context) {
        TPlusOneImportConfig config = requireConfig(context);
        indexManager.bindAlias(
                requireText(config.getIndexName(), "indexName"),
                requireText(config.getIndexAlias(), "indexAlias")
        );
    }

    /**
     * 按表配置删除一个刚超过保留期的历史物理索引。
     *
     * <p>每次任务最多计算并删除一个确定索引，不扫描Alias下的全部索引。删除属于最佳努力，
     * 失败只记录日志，不反向改变已经完成的T+1任务结果。</p>
     */
    public void deleteHistoryIndex(ImportContext context) {
        TPlusOneImportConfig config = requireConfig(context);
        if (!config.isDeleteHistoryIndex()) {
            return;
        }

        String alias = requireText(config.getIndexAlias(), "indexAlias");
        String currentIndex = requireText(config.getIndexName(), "indexName");
        int reserveDays = Math.max(1, config.getReserveDays());
        LocalDate importDate = config.getImportDate();
        if (importDate == null) {
            throw new ServiceException("ES import importDate cannot be null");
        }

        String expiredIndex = alias + "_"
                + INDEX_DATE_FORMATTER.format(importDate.minusDays(reserveDays));
        if (StringUtils.equals(expiredIndex, currentIndex) || !indexManager.exists(expiredIndex)) {
            return;
        }
        try {
            indexManager.deleteIndex(expiredIndex);
        } catch (RuntimeException error) {
            log.warn("===> ES-Import delete history index failed. index={}, error={}",
                    expiredIndex, error.getMessage(), error);
        }
    }

    /**
     * 校验导入上下文并返回T+1任务配置。
     */
    private TPlusOneImportConfig requireConfig(ImportContext context) {
        if (context == null || context.getConfig() == null) {
            throw new ServiceException("ES import context config cannot be null");
        }
        return context.getConfig();
    }

    /**
     * 校验并返回同步全局参数。
     */
    private EsImportProperties requireProperties(ImportContext context) {
        if (context == null || context.getProperties() == null) {
            throw new ServiceException("ES import properties cannot be null");
        }
        return context.getProperties();
    }

    /**
     * 校验并规范化T+1索引业务的必填字符串。
     */
    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("ES import " + fieldName + " cannot be blank");
        }
        return value.trim();
    }
}
