package com.tsd.sano.es.importer.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch.indices.*;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.tsd.sano.es.core.config.EsImportProperties;
import com.tsd.sano.es.core.exception.BusinessException;
import com.tsd.sano.es.importer.model.EsImportConfig;
import com.tsd.sano.es.importer.model.ImportContext;
import com.tsd.sano.es.importer.util.MappingLoader;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ES索引生命周期管理器。
 *
 * <p>负责V2导入流程中的索引创建、导入前参数优化、导入后恢复、
 * alias原子切换，以及历史索引清理。</p>
 */
@Service
public class EsIndexManager {

    private static final Logger log = LoggerFactory.getLogger(EsIndexManager.class);

    /**
     * 真实索引名称中的日期格式，约定为 alias_yyyyMMdd。
     */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 导入完成后恢复的默认刷新间隔。
     *
     * <p>1s是ES默认常用值，兼顾近实时查询和写入性能。</p>
     */
    private static final String DEFAULT_REFRESH_INTERVAL = "1s";

    /**
     * 单机Docker部署下默认副本数。
     *
     * <p>单节点ES无法分配副本分片，保持0可以避免索引长期处于yellow状态。</p>
     */
    private static final String DEFAULT_REPLICAS = "0";

    private final ElasticsearchClient client;
    private final MappingLoader mappingLoader;

    /**
     * 注入ES客户端和Mapping加载器。
     */
    public EsIndexManager(ElasticsearchClient client, MappingLoader mappingLoader) {
        this.client = client;
        this.mappingLoader = mappingLoader;
    }

    /**
     * 创建本次导入使用的真实索引。
     *
     * <p>这里不绑定业务alias，避免导入未完成的数据被查询流量读到； alias统一在导入成功后再切换。</p>
     */
    public boolean createIndex(ImportContext context) {
        EsImportConfig config = requireConfig(context);
        String indexName = requireText(config.getIndexName(), "indexName");
        String mappingFile = requireText(config.getMappingFile(), "mappingFile");

        try {
            if (exists(indexName)) {
                // 同名索引可能是上次失败留下的半成品，稳定性优先，拒绝复用。
                throw new BusinessException("ES import target index already exists, index=" + indexName
                        + ". Please delete the old index or use a new indexName before retry.");
            }

            try (InputStream mapping = mappingLoader.load(mappingFile)) {
                // mapping文件同时包含settings和mappings，直接作为create index请求体。
                CreateIndexRequest request = new CreateIndexRequest.Builder()
                        .index(indexName)
                        .withJson(mapping)
                        .build();

                CreateIndexResponse response = client.indices().create(request);
                boolean acknowledged = response.acknowledged();
                log.info("===> ES-Import create index result. index={}, acknowledged={}", indexName, acknowledged);
                return acknowledged;
            }
        } catch (IOException | ElasticsearchException e) {
            throw new BusinessException("ES create index failed, index=" + indexName + ", error=" + e.getMessage());
        }
    }

    /**
     * 导入前优化索引参数，降低大批量写入成本。
     */
    public void beforeImport(ImportContext context) {
        EsImportConfig config = requireConfig(context);
        EsImportProperties properties = requireProperties(context);
        String indexName = requireText(config.getIndexName(), "indexName");

        // 批量写入期间关闭自动刷新，减少segment频繁生成。
        if (properties.isDisableRefresh()) {
            updateRefreshInterval(indexName, "-1");
        }
        // 单机Docker部署下副本数保持0，避免副本分片长期yellow。
        if (properties.isDisableReplica()) {
            updateReplicaCount(indexName, "0");
        }
    }

    /**
     * 导入完成后恢复索引参数，并刷新索引使数据可查询。
     */
    public void afterImport(ImportContext context) {
        EsImportConfig config = requireConfig(context);
        EsImportProperties properties = requireProperties(context);
        String indexName = requireText(config.getIndexName(), "indexName");

        if (properties.isDisableRefresh()) {
            updateRefreshInterval(indexName, DEFAULT_REFRESH_INTERVAL);
        }
        if (properties.isDisableReplica()) {
            updateReplicaCount(indexName, DEFAULT_REPLICAS);
        }
        refresh(indexName);
    }

    /**
     * 为本次新索引绑定业务alias。
     *
     * <p>当前V2按 alias_yyyyMMdd 保存每日分区索引，业务alias需要同时指向
     * 保留期内的多个日期索引；因此这里只追加alias，不移除旧索引alias。</p>
     */
    public boolean switchAlias(ImportContext context) {
        EsImportConfig config = requireConfig(context);
        String indexName = requireText(config.getIndexName(), "indexName");
        String alias = requireText(config.getIndexAlias(), "indexAlias");

        try {
            // 历史索引是否可查由deleteHistoryIndices控制，不能在这里批量移除旧alias。
            UpdateAliasesResponse response = client.indices().updateAliases(request -> request
                    .actions(action -> action.add(add -> add
                            .index(indexName)
                            .alias(alias)
                    ))
            );

            boolean acknowledged = response.acknowledged();
            log.info("===> ES-Import bind alias result. alias={}, index={}, acknowledged={}",
                    alias, indexName, acknowledged);
            return acknowledged;
        } catch (IOException | ElasticsearchException e) {
            throw new BusinessException("ES bind alias failed, alias=" + alias + ", index=" + indexName
                    + ", error=" + e.getMessage());
        }
    }

    /**
     * 按配置清理历史真实索引。
     */
    public void deleteHistoryIndices(ImportContext context) {
        EsImportConfig config = requireConfig(context);
        EsImportProperties properties = requireProperties(context);

        if (!properties.isDeleteHistoryIndex()) {
            return;
        }

        String alias = requireText(config.getIndexAlias(), "indexAlias");
        String currentIndex = requireText(config.getIndexName(), "indexName");
        LocalDate keepAfter = LocalDate.now().minusDays(properties.getReserveDays());

        // 仅清理符合 alias_yyyyMMdd 命名规则且早于保留日期的索引。
        List<String> candidates = listHistoryIndices(alias);
        candidates.stream()
                .filter(index -> !index.equals(currentIndex))
                .filter(index -> isBeforeKeepDate(alias, index, keepAfter))
                .sorted(Comparator.naturalOrder())
                .forEach(this::deleteIndexQuietly);
    }

    public boolean exists(String indexName) {
        try {
            // exists接口用于保护创建流程，避免覆盖已有索引。
            BooleanResponse response = client.indices().exists(request -> request.index(indexName));
            return response.value();
        } catch (IOException | ElasticsearchException e) {
            throw new BusinessException("ES check index exists failed, index=" + indexName
                    + ", error=" + e.getMessage());
        }
    }

    /**
     * 更新索引refresh_interval。
     */
    private void updateRefreshInterval(String indexName, String refreshInterval) {
        try {
            // refresh_interval=-1表示暂停自动刷新，适合批量导入阶段。
            PutIndicesSettingsResponse response = client.indices().putSettings(request -> request
                    .index(indexName)
                    .settings(settings -> settings.refreshInterval(Time.of(time -> time.time(refreshInterval))))
            );
            log.info("===> ES-Import update refresh_interval. index={}, refreshInterval={}, acknowledged={}",
                    indexName, refreshInterval, response.acknowledged());
        } catch (IOException | ElasticsearchException e) {
            throw new BusinessException("ES update refresh_interval failed, index=" + indexName
                    + ", error=" + e.getMessage());
        }
    }

    /**
     * 更新索引副本数。
     */
    private void updateReplicaCount(String indexName, String replicas) {
        try {
            // 单机部署通常使用0副本，避免副本无法分配导致集群yellow。
            PutIndicesSettingsResponse response = client.indices().putSettings(request -> request
                    .index(indexName)
                    .settings(settings -> settings.numberOfReplicas(replicas))
            );
            log.info("===> ES-Import update number_of_replicas. index={}, replicas={}, acknowledged={}",
                    indexName, replicas, response.acknowledged());
        } catch (IOException | ElasticsearchException e) {
            throw new BusinessException("ES update number_of_replicas failed, index=" + indexName
                    + ", error=" + e.getMessage());
        }
    }

    /**
     * 刷新索引，使本次导入数据可被查询。
     */
    private void refresh(String indexName) {
        try {
            RefreshResponse response = client.indices().refresh(request -> request.index(indexName));
            if (response.shards() != null) {
                // 记录成功刷新分片数，便于排查ES分片状态问题。
                log.info("===> ES-Import refresh index. index={}, successfulShards={}",
                        indexName, response.shards().successful());
            } else {
                log.warn("===> ES-Import refresh index. index={}, shards=null", indexName);
            }
        } catch (IOException | ElasticsearchException e) {
            throw new BusinessException("ES refresh index failed, index=" + indexName
                    + ", error=" + e.getMessage());
        }
    }

    /**
     * 查询业务alias对应的历史真实索引列表。
     */
    private List<String> listHistoryIndices(String alias) {
        try {
            GetIndexResponse response = client.indices().get(request -> request.index(alias + "_*"));
            Map<String, IndexState> indices = response.result();
            return new ArrayList<>(indices.keySet());
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 没有历史索引是正常场景，返回空列表即可。
                return List.of();
            }
            throw new BusinessException("ES list history indices failed, alias=" + alias
                    + ", error=" + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException("ES list history indices failed, alias=" + alias
                    + ", error=" + e.getMessage());
        }
    }

    /**
     * 判断索引日期是否早于保留边界。
     */
    private boolean isBeforeKeepDate(String alias, String indexName, LocalDate keepAfter) {
        String prefix = alias + "_";
        if (!indexName.startsWith(prefix)) {
            return false;
        }

        String dateText = indexName.substring(prefix.length());
        // 当前真实索引约定为 alias_yyyyMMdd，格式不匹配时不做自动删除。
        if (dateText.length() != 8) {
            return false;
        }

        try {
            LocalDate indexDate = LocalDate.parse(dateText, INDEX_DATE_FORMATTER);
            return indexDate.isBefore(keepAfter);
        } catch (DateTimeParseException e) {
            // 日期无法解析时不自动删除，避免误删非标准命名索引。
            log.warn("===> ES-Import skip history index with unparsable date. index={}", indexName);
            return false;
        }
    }

    /**
     * 删除历史索引，失败只记录日志，不影响本次导入成功结果。
     */
    private void deleteIndexQuietly(String indexName) {
        try {
            DeleteIndexResponse response = client.indices().delete(request -> request.index(indexName));
            log.info("===> ES-Import delete history index. index={}, acknowledged={}",
                    indexName, response.acknowledged());
        } catch (Exception e) {
            log.warn("===> ES-Import delete history index failed. index={}, error={}", indexName, e.getMessage());
        }
    }

    /**
     * 校验导入上下文并返回业务配置。
     */
    private EsImportConfig requireConfig(ImportContext context) {
        if (context == null || context.getConfig() == null) {
            throw new BusinessException("ES import context config cannot be null");
        }
        return context.getConfig();
    }

    /**
     * 校验并返回导入全局参数。
     */
    private EsImportProperties requireProperties(ImportContext context) {
        if (context == null || context.getProperties() == null) {
            throw new BusinessException("ES import properties cannot be null");
        }
        return context.getProperties();
    }

    /**
     * 校验必填字符串参数。
     */
    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new BusinessException("ES import " + fieldName + " cannot be blank");
        }
        return value.trim();
    }
}
