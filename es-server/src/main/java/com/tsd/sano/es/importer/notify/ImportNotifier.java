package com.tsd.sano.es.importer.notify;

/**
 * 导入任务通知渠道接口。
 *
 * <p>后续如需接入邮件、短信或其他IM，只需要新增实现类。</p>
 *
 * @author lxw
 */
public interface ImportNotifier {

    /**
     * 发送导入任务通知。
     *
     * @param message 通知消息
     */
    void send(ImportNotifyMessage message) throws Exception;
}
