package com.tsd.sano.es.importer.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉导入通知实现。
 *
 * <p>支持无签名机器人和开启secret签名的自定义机器人。</p>
 *
 * @author lxw
 */
@Component
public class DingTalkImportNotifier implements ImportNotifier {

    /**
     * webhook请求超时时间，避免通知链路长时间占用任务线程。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final EsImportProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    /**
     * 注入导入配置和JSON序列化器。
     */
    public DingTalkImportNotifier(EsImportProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送钉钉文本通知。
     */
    @Override
    public void send(ImportNotifyMessage message) throws Exception {
        EsImportProperties.NotifyChannelConfig config = properties.getNotify().getChannels().getDingtalk();
        if (!config.isEnabled() || StringUtils.isBlank(config.getWebhookUrl())) {
            return;
        }

        String webhookUrl = config.getWebhookUrl();
        if (StringUtils.isNotBlank(config.getSecret())) {
            long timestamp = System.currentTimeMillis();
            // 钉钉签名规则：secret作为HmacSHA256密钥，timestamp + "\n" + secret作为待签名内容。
            String sign = sign(timestamp, config.getSecret());
            String separator = webhookUrl.contains("?") ? "&" : "?";
            webhookUrl = webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + sign;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "text");
        payload.put("text", Map.of("content", message.getContent()));

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("DingTalk webhook response status=" + response.statusCode()
                    + ", body=" + StringUtils.left(response.body(), 500));
        }
        JsonNode responseBody = objectMapper.readTree(response.body());
        JsonNode errorCodeNode = responseBody.get("errcode");
        if (errorCodeNode != null && errorCodeNode.asInt() != 0) {
            throw new IllegalStateException("DingTalk webhook response failed, body=" + StringUtils.left(response.body(), 500));
        }
    }

    /**
     * 生成钉钉机器人签名。
     */
    private String sign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
    }
}
