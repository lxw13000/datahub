package com.tsd.sano.es.modules.notify.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通知模块接收的通用消息。
 *
 * <p>事件类型、业务标识、标题和正文均由调用模块组织，通知模块不解释具体业务含义。</p>
 */
@Getter
@AllArgsConstructor
public class NotifyMessage {

    /** 调用模块定义的事件类型，用于通知日志分类。 */
    private final String eventType;

    /** 任务ID、表名或索引名等业务定位标识。 */
    private final String businessId;

    /** 消息标题。 */
    private final String title;

    /** 纯文本消息正文。 */
    private final String content;
}
