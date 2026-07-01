package com.tsd.sano.es.core.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsd.sano.es.core.exception.ServiceException;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class ElasticClientConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticClientConfig.class);

    @Value("${sano.es.uris}")
    private String hosts;

    @Value("${sano.es.username:}")
    private String userName;

    @Value("${sano.es.password:}")
    private String passWord;

    @Value("${sano.es.connection-timeout:5000}")
    private Integer connectTimeout;

    @Value("${sano.es.socket-timeout:60000}")
    private Integer socketTimeout;

    @Value("${sano.es.connection-request-timeout:5000}")
    private Integer connectionRequestTimeout;

    @Value("${sano.es.max-conn-total:100}")
    private Integer maxConnTotal;

    @Value("${sano.es.max-conn-per-route:20}")
    private Integer maxConnPerRoute;

    // 直接注入 Spring Boot 自动配置好的 ObjectMapper
    private final ObjectMapper objectMapper;

    public ElasticClientConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    @ConditionalOnMissingBean(ElasticsearchClient.class)
    public ElasticsearchClient elasticsearchClient() {

        HttpHost[] httpHosts = toHttpHost();

        RestClientBuilder builder = RestClient.builder(httpHosts);

        // =========================
        // 1. Auth（可选）
        // =========================
        CredentialsProvider credentialsProvider = null;

        if (StringUtils.isNotBlank(userName)) {
            credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(userName, passWord)
            );
        }

        CredentialsProvider finalCredentialsProvider = credentialsProvider;

        // =========================
        // 2. HTTP client config
        // =========================
        builder.setHttpClientConfigCallback(httpClientBuilder -> {

            if (finalCredentialsProvider != null) {
                httpClientBuilder.setDefaultCredentialsProvider(finalCredentialsProvider);
            }

            httpClientBuilder
                    .setMaxConnTotal(maxConnTotal)
                    .setMaxConnPerRoute(maxConnPerRoute);

            return httpClientBuilder;
        });

        // =========================
        // 3. Request config
        // =========================
        builder.setRequestConfigCallback(req -> req
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout(socketTimeout)
                .setConnectionRequestTimeout(connectionRequestTimeout)
        );

        RestClient restClient = builder.build();

        // =========================
        // 4. JSON mapper（关键优化点）
        // =========================
        JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper(objectMapper);
        ElasticsearchTransport transport =
                new RestClientTransport(restClient, jsonpMapper);

        LOGGER.info("=====> Elasticsearch Client initialized");

        return new ElasticsearchClient(transport);
    }

    private HttpHost[] toHttpHost() {
        if (StringUtils.isBlank(hosts)) {
            throw new ServiceException("elasticsearch hosts不能为空");
        }

        String[] hostArray = hosts.split(",");

        HttpHost[] httpHosts = new HttpHost[hostArray.length];

        for (int i = 0; i < hostArray.length; i++) {
            String[] parts = hostArray[i].split(":");
            httpHosts[i] = new HttpHost(
                    parts[0],
                    Integer.parseInt(parts[1]),
                    "http"
            );
        }

        return httpHosts;
    }
}
