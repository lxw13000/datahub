package com.tsd.sano.es.modules.notify.channel;

import com.tsd.sano.es.modules.notify.model.NotifyMessage;

/**
 * 公共通知通道。
 *
 * <p>新增消息渠道时只需实现本接口，业务模块不依赖具体Webhook协议。</p>
 */
public interface NotifyChannel {

    /**
     * 向当前通道发送已经组织完成的消息。
     *
     * @param message 通用通知消息
     */
    void send(NotifyMessage message) throws Exception;
}
