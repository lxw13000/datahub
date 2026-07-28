package com.tsd.sano.es.modules.notify.service;

import com.tsd.sano.es.modules.notify.config.NotifyProperties;
import com.tsd.sano.es.modules.notify.channel.NotifyChannel;
import com.tsd.sano.es.modules.notify.model.NotifyMessage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 公共消息通道分发服务。
 *
 * <p>调用模块自行决定发送时机并组织消息内容；本服务只处理总开关、通道遍历和异常隔离。
 * 任一通道失败均只记录日志，不向调用方传播，避免通知链路影响业务主流程。</p>
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    /** 公共通知配置。 */
    private final NotifyProperties properties;

    /** 当前应用注册的全部通知通道。 */
    private final List<NotifyChannel> channels;

    /**
     * 注入通知配置和通道实现。
     */
    public NotifyService(NotifyProperties properties, List<NotifyChannel> channels) {
        this.properties = properties;
        this.channels = channels;
    }

    /**
     * 向所有启用的通知通道发送一条完整消息。
     */
    public void send(NotifyMessage message) {
        if (!properties.isEnabled()) {
            return;
        }
        if (message == null || StringUtils.isBlank(message.getContent())) {
            log.warn("===> ES-Notify skipped because message content is empty.");
            return;
        }
        if (channels == null || channels.isEmpty()) {
            log.warn("===> ES-Notify skipped because no channel bean exists. eventType={}, businessId={}",
                    message.getEventType(), message.getBusinessId());
            return;
        }

        for (NotifyChannel channel : channels) {
            try {
                channel.send(message);
            } catch (Exception error) {
                log.error("===> ES-Notify channel failed. eventType={}, businessId={}, channel={}, error={}",
                        message.getEventType(), message.getBusinessId(),
                        channel.getClass().getSimpleName(), error.getMessage(), error);
            }
        }
    }
}
