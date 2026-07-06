package com.tsd.sano.es.importer.notify;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 导入任务通知消息。
 *
 * <p>通知渠道只关心标题和正文，避免业务流程绑定具体机器人消息格式。</p>
 *
 * @author lxw
 */
@Getter
@AllArgsConstructor
public class ImportNotifyMessage {

    /**
     * 通知事件类型，例如SUCCESS、FAILED、TIMEOUT_PARTIAL。
     */
    private final String eventType;

    /**
     * 通知标题。
     */
    private final String title;

    /**
     * 通知正文，使用纯文本便于兼容不同webhook渠道。
     */
    private final String content;
}
