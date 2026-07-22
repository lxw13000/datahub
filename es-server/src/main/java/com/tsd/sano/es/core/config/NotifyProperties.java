package com.tsd.sano.es.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 服务级消息通知配置。
 *
 * <p>配置前缀为 {@code sano.notify}，与ES连接和导入配置相互独立，供当前导入任务
 * 及后续其他业务模块复用。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sano.notify")
public class NotifyProperties {

    /** 通知总开关，关闭后所有渠道都不发送。 */
    private boolean enabled;

    /** 飞书/Lark机器人配置。 */
    private Channel lark = new Channel();

    /**
     * 单个webhook通知渠道配置。
     */
    @Getter
    @Setter
    public static class Channel {

        /** 当前渠道开关。 */
        private boolean enabled;

        /** 机器人webhook地址。 */
        private String webhookUrl;

        /** 机器人签名密钥，未配置时按无签名机器人发送。 */
        private String secret;
    }
}
