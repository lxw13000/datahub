package com.tsd.sano.es.importer1;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.tsd.sano.es.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据ES索引操作Service
 * 提供Elasticsearch索引的创建、数据导入等核心功能
 *
 * @author lxw
 * @version V1.3
 * @date 2024-7-18
 */
@Service
public class EsProcessServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(EsProcessServiceImpl.class);

    /**
     * Elasticsearch客户端
     * 用于执行ES操作（已测试可用）
     */
    private final ElasticsearchClient client;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造函数 - 依赖注入
     *
     * @param client Elasticsearch客户端（已测试可用）
     */
    public EsProcessServiceImpl(ElasticsearchClient client, JdbcTemplate jdbcTemplate) {
        this.client = client;
        this.jdbcTemplate = jdbcTemplate;
    }


    /**
     * 数据导入ES索引
     *
     * @param indexAlias      别名（数据库表名）
     * @param mappingFileName es索引的mapping文件名（位于resources/esmapping目录下）
     * @author lxw
     * @date 2026/6/29 16:44
     **/
    public void toLeadEsData(String indexAlias, String mappingFileName) {
        // 如果未传入esClient，使用默认配置的客户端
        String indexName = indexAlias + "_" + TimeUtils.formatDate(LocalDate.now().minusDays(1), TimeUtils.YYYYMMDD);
        try {
            log.info("===> ES-Import 开始异步导入ES数据，索引：{}", indexName);


            // 第四步：创建ES索引结构
            // realIndexName()方法生成带时间戳的真实索引名
            if (!createEsIndex(indexName, indexAlias, mappingFileName)) {
                return;
            }
            // 第五步：导入数据到ES索引（传入批次号用于进度追踪）
            doImportEsData(indexName, indexAlias);
        } catch (Exception e) {
            // 发生异常时记录错误日志
            log.error("===> ES-Import 数据导入失败，批次号：{}，错误：{}", indexName, e.getMessage(), e);

        }
    }


    /**
     * 创建ES索引结构
     *
     * @param indexName       索引名称
     * @param indexAlias      索引别名
     * @param mappingFileName es索引的mapping文件名（位于resources/esmapping目录下）
     * @return boolean
     * @author lxw
     * @date 2026/6/29 16:46
     **/
    public boolean createEsIndex(String indexName, String indexAlias, String mappingFileName) {

        try {
            // 检查索引是否已存在（使用Elasticsearch Java客户端）
            BooleanResponse exists = client.indices().exists(e -> e.index(indexName));
            if (exists.value()) {
                // 如果索引已存在，跳过创建，直接返回true，继续执行数据导入
                log.info("===> ES-Import 生成ES索引结构，索引已存在，跳过创建！索引名称：{}", indexName);
                return true;
            }
            ClassPathResource classPathResource = new ClassPathResource("esmapping/" + mappingFileName);
            // 将mapping JSON字符串转换为InputStream，供ES客户端使用
            InputStream inputStream = classPathResource.getInputStream();


            // 构建创建索引请求
            CreateIndexRequest createIndexRequest = new CreateIndexRequest.Builder()
                    .index(indexName)                      // 设置索引名称
                    .aliases(indexAlias, a -> a)           // 设置索引别名
                    .withJson(inputStream)
                    .build();

            // 执行创建索引操作
            CreateIndexResponse indexResponse = client.indices().create(createIndexRequest);
            boolean created = indexResponse.acknowledged();
            log.info("===> ES-Import 生成ES索引结构，索引创建响应: {}", created);
            return created;
        } catch (IOException e) {
            // IO异常处理：可能是网络问题或ES服务不可用
            log.error("===> ES-Import 生成ES索引结构，创建索引失败，索引名：{}，错误：{}", indexName, e.getMessage());
            return false;
        }
    }

    /**
     * 执行数据导入ES索引操作
     *
     * @param indexName  索引名称
     * @param indexAlias 索引别名（数据库表名）
     * @author lxw
     * @date 2026/6/29 16:47
     **/
    public void doImportEsData(String indexName, String indexAlias) {

        // 获取数据库表名和查询条件
        String tableName = indexAlias;
        String dt = TimeUtils.formatDate(LocalDate.now().minusDays(1), TimeUtils.YYYY_MM_DD);
        String sql = "SELECT COUNT(1) FROM " + tableName + " WHERE dt='" + dt + "' ";

        Long totalCount = jdbcTemplate.queryForObject(sql, Long.class);
        // 验证查询结果,处理count为null的情况，防止空指针异常
        if (totalCount == null || totalCount <= 0L) {
            throw new BusinessException("本次无数据需要收割。");
        }
        // ============ 第三部分：批量查询和导入数据 ============
        // 初始化失败数据ID列表
        List<String> failedIds = new ArrayList<>();

        // 分批次查询和导入配置
        int cardinality = 500; // 每批次处理的数据量
        long lastId = 0L; // 上一次查询的最大ID，用于分页查询
        int total = (int) Math.ceil(totalCount / (double) cardinality); // 总批次数
        long processedCount = 0L; // 已处理的数据条数

        // 获取真实需要导入数据的ES索引名称
        // 循环批量查询和导入数据
        for (int i = 0; i < total; i++) {
            List<Map<String, Object>> dataList = fetchData(tableName, dt, lastId, cardinality);
            // 如果没有数据了，退出循环
            if (dataList.isEmpty()) {
                break;
            }
            lastId = (Long) dataList.getLast().get("id"); // 更新lastId为当前批次的最大ID
            // 批量索引数据到ES
            BulkResponse bulkResponse = processBulkRequest(indexName, dataList);

            // 处理批量操作结果
            if (bulkResponse != null) {
                // 检查是否有错误
                if (bulkResponse.errors()) {
                    handleBulkErrors(bulkResponse);
                }

                // 计算本批次失败数量
                long failCount = bulkResponse.items().stream()
                        .filter(item -> item.error() != null)
                        .count();

                // 计算失败率
                double failRate = (double) failCount / dataList.size();

                // 如果失败率超过50%，终止导入
                if (failRate > 0.5) {
                    log.error("===> ES-Import 索引：{}，批量导入失败率过高：{}%，终止导入！", indexName, String.format("%.2f", failRate * 100));
                    return;
                }
                log.info("===> ES-Import 批次号：{}，当前循环批量中，成功：{}，失败：{}", indexName, dataList.size() - failCount, failCount);
            } else {
                // 批量操作返回null，说明请求失败
                log.error("===> ES-Import 批次号：{}，当前循环ES bulkRequest请求失败，已终止导入", indexName);

                // 批量操作失败，延迟删除缓存并记录失败信息
                return;
            }

            // 更新已处理数量
            processedCount += dataList.size();

            // 更新进度缓存
            log.info("===> ES-Import 批次号：{}，进度更新：{}/{}", indexName, processedCount, totalCount);
        }

    }

    private List<Map<String, Object>> fetchData(String tableName, String dt, long lastId, int pageSize) {
        try {
            // 构建SQL语句
            StringBuilder sql = new StringBuilder("SELECT * FROM ");
            sql.append(tableName)
                    .append(" a WHERE a.dt='").append(dt).append("' ")
                    .append(" AND a.id > ").append(lastId)
                    .append(" ORDER BY a.id ASC ")
                    .append(" LIMIT ").append(pageSize);

            // 使用参数化查询，防止SQL注入
            // lastId和cardinality通过占位符传递，安全可靠
            return jdbcTemplate.queryForList(String.valueOf(sql));

        } catch (DataAccessException e) {
            // 捕获数据访问异常，记录详细日志后返回空列表
            log.error("===> ES-Import {}数据表查询数据错误，错误: {}", tableName, e.getMessage());
            return new ArrayList<>();
        }
    }


    private BulkResponse processBulkRequest(String indexName, List<Map<String, Object>> list) {
        // 创建批量请求构建器
        BulkRequest.Builder bulkRequest = new BulkRequest.Builder();

        // 遍历每条数据，转换并添加到批量请求中
        for (Map<String, Object> map : list) {
            // 处理特殊字段：数组字段、对象字段等
            // processArrayFields方法会根据配置转换数据格式


            // 获取文档ID
            // 增强onlyId空字符串判断
            Object idObj = map.get("id");
            String id = (idObj != null) ? idObj.toString() : System.currentTimeMillis() + "";

            // 添加索引操作到批量请求
            bulkRequest.operations(op -> op
                    .index(idx -> idx
                            .index(indexName)      // 指定索引名称
                            .id(id)                // 指定文档ID
                            .document(map)  // 设置文档内容
                    )
            );
        }

        // 设置立即刷新，使数据立即可搜索
        bulkRequest.refresh(Refresh.True);

        try {
            // 执行批量索引操作
            return client.bulk(bulkRequest.build());
        } catch (IOException e) {
            // 记录错误日志
            log.error("===> ES-Import {}索引批量创建失败，ES错误: {}", indexName, e.getMessage());
            return null;
        }
    }


    /**
     * 处理批量索引操作的错误结果
     * 从BulkResponse中提取失败的文档ID
     * <p>
     * 使用场景：
     * - 批量导入数据后，需要知道哪些数据导入失败
     * - 后续可以针对失败的数据进行重试或人工处理
     *
     * @param bulkResponse ES批量操作响应对象（已测试可用）
     * @author lxw
     * @date 2024/12/9 9:25
     */
    private void handleBulkErrors(BulkResponse bulkResponse) {
        // 使用Stream API过滤出有错误的条目，并添加到失败列表
        bulkResponse.items().stream()
                .filter(item -> item.error() != null)  // 只处理有错误的条目
                .forEach(item -> {
                    // 记录详细的错误信息，便于排查问题
                    log.warn("===> ES-Import 文档索引失败，ID：{}，错误：{}", item.id(), item.error().reason());
                });
    }

}