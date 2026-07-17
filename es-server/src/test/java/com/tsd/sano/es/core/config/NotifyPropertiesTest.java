package com.tsd.sano.es.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务级消息配置独立绑定测试。
 */
class NotifyPropertiesTest {

    /**
     * sano.notify只绑定总开关和消息通道能力。
     */
    @Test
    void shouldBindNotifySwitchAndChannels() {
        Map<String, String> values = Map.of(
                "sano.notify.enabled", "true",
                "sano.notify.lark.enabled", "true",
                "sano.notify.lark.webhook-url", "https://example.test/hook",
                "sano.notify.lark.secret", "secret"
        );

        NotifyProperties properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("sano.notify", Bindable.of(NotifyProperties.class))
                .orElseThrow(() -> new AssertionError("Notify properties were not bound"));

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getLark().isEnabled()).isTrue();
        assertThat(properties.getLark().getWebhookUrl())
                .isEqualTo("https://example.test/hook");
        assertThat(properties.getDingtalk().isEnabled()).isFalse();
    }
}
