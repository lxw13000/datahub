package com.tsd.sano.es.modules.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch.indices.*;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.tsd.sano.es.core.exception.ServiceException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * Elasticsearch索引通用远程操作服务。
 *
 * <p>该服务只处理索引创建、存在性检查、Alias、Settings、Refresh和删除等原子操作，
 * 不感知T+1、Polling、业务日期和历史保留规则。具体同步模块负责组织调用时机和异常策略。</p>
 */
@Service
public class EsIndexManager {

    private static final Logger log = LoggerFactory.getLogger(EsIndexManager.class);

    /**
     * Elasticsearch客户端。
     */
    private final ElasticsearchClient client;

    /**
     * classpath Mapping文件加载器。
     */
    private final MappingLoader mappingLoader;

    /**
     * 注入ES客户端和Mapping加载器。
     */
    public EsIndexManager(ElasticsearchClient client, MappingLoader mappingLoader) {
        this.client = client;
        this.mappingLoader = mappingLoader;
    }

    /**
     * 使用指定Mapping创建不带Alias的索引。
     *
     * @return true表示ES已确认创建
     */
    public void createIndex(String indexName, String mappingFile) {
        createIndex(indexName, mappingFile, null);
    }

    /**
     * 使用指定Mapping创建索引，并在Alias非空时随创建请求一次性绑定。
     */
    public void createIndex(String indexName, String mappingFile, String alias) {
        String requiredIndexName = requireText(indexName, "indexName");
        String requiredMappingFile = requireText(mappingFile, "mappingFile");
        try (InputStream mapping = mappingLoader.load(requiredMappingFile)) {
            CreateIndexRequest.Builder builder = new CreateIndexRequest.Builder()
                    .index(requiredIndexName)
                    .withJson(mapping);
            if (StringUtils.isNotBlank(alias)) {
                builder.aliases(alias.trim(), aliasBuilder -> aliasBuilder);
            }

            CreateIndexResponse response = client.indices().create(builder.build());
            boolean acknowledged = response.acknowledged();
            log.info("===> ES-Index create result. index={}, alias={}, acknowledged={}",
                    requiredIndexName, StringUtils.trimToNull(alias), acknowledged);
            if (!response.acknowledged()) {
                throw new ServiceException("ES index creation was not acknowledged, index=" + requiredIndexName);
            }
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES index creation failed, index=" + requiredIndexName
                    + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 判断指定索引是否存在。
     */
    public boolean exists(String indexName) {
        String requiredIndexName = requireText(indexName, "indexName");
        try {
            ExistsRequest request = new ExistsRequest.Builder().index(requiredIndexName).build();
            BooleanResponse response = client.indices().exists(request);
            return response.value();
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES index exists check failed, index=" + requiredIndexName
                    + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 幂等为指定物理索引绑定业务Alias。
     */
    public void bindAlias(String indexName, String alias) {
        String requiredIndexName = requireText(indexName, "indexName");
        String requiredAlias = requireText(alias, "alias");
        try {
            UpdateAliasesResponse response = client.indices().updateAliases(request -> request
                    .actions(action -> action.add(add -> add
                            .index(requiredIndexName)
                            .alias(requiredAlias)
                    ))
            );
            boolean acknowledged = response.acknowledged();
            log.info("===> ES-Index bind alias result. alias={}, index={}, acknowledged={}",
                    requiredAlias, requiredIndexName, acknowledged);
            if (!response.acknowledged()) {
                throw new ServiceException("ES alias binding was not acknowledged, alias=" + requiredAlias
                        + ", index=" + requiredIndexName);
            }
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES alias binding failed, alias=" + requiredAlias
                    + ", index=" + requiredIndexName + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 更新指定索引的自动刷新间隔。
     */
    public void updateRefreshInterval(String indexName, String refreshInterval) {
        String requiredIndexName = requireText(indexName, "indexName");
        String requiredInterval = requireText(refreshInterval, "refreshInterval");
        try {
            PutIndicesSettingsResponse response = client.indices().putSettings(request -> request
                    .index(requiredIndexName)
                    .settings(settings -> settings
                            .refreshInterval(Time.of(time -> time.time(requiredInterval))))
            );
            log.info("===> ES-Index update refresh_interval. index={}, refreshInterval={}, acknowledged={}",
                    requiredIndexName, requiredInterval, response.acknowledged());
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES refresh_interval update failed, index=" + requiredIndexName
                    + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 更新指定索引的副本数量。
     */
    public void updateReplicaCount(String indexName, String replicas) {
        String requiredIndexName = requireText(indexName, "indexName");
        String requiredReplicas = requireText(replicas, "replicas");
        try {
            PutIndicesSettingsResponse response = client.indices().putSettings(request -> request
                    .index(requiredIndexName)
                    .settings(settings -> settings.numberOfReplicas(requiredReplicas))
            );
            log.info("===> ES-Index update number_of_replicas. index={}, replicas={}, acknowledged={}",
                    requiredIndexName, requiredReplicas, response.acknowledged());
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES replica count update failed, index=" + requiredIndexName
                    + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 主动刷新指定索引，使已写入文档可被查询。
     */
    public void refresh(String indexName) {
        String requiredIndexName = requireText(indexName, "indexName");
        try {
            RefreshResponse response = client.indices().refresh(request -> request.index(requiredIndexName));
            if (response.shards() == null) {
                log.warn("===> ES-Index refresh completed without shard result. index={}", requiredIndexName);
                return;
            }
            log.info("===> ES-Index refresh completed. index={}, successfulShards={}",
                    requiredIndexName, response.shards().successful());
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES index refresh failed, index=" + requiredIndexName
                    + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 删除指定索引。
     */
    public void deleteIndex(String indexName) {
        String requiredIndexName = requireText(indexName, "indexName");
        try {
            DeleteIndexResponse response = client.indices().delete(request -> request.index(requiredIndexName));
            boolean acknowledged = response.acknowledged();
            log.info("===> ES-Index delete result. index={}, acknowledged={}",
                    requiredIndexName, acknowledged);
            if (!response.acknowledged()) {
                throw new ServiceException("ES index deletion was not acknowledged, index=" + requiredIndexName);
            }
        } catch (IOException | ElasticsearchException error) {
            throw new ServiceException("ES index deletion failed, index=" + requiredIndexName
                    + ", error=" + error.getMessage(), error);
        }
    }

    /**
     * 校验并规范化索引操作的必填字符串。
     */
    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("ES index " + fieldName + " cannot be blank");
        }
        return value.trim();
    }
}
