package com.tsd.sano.es.modules.config;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * 当前实例承担的服务职责
 *
 * <p>所有模式均开放查询并注册相同BeanALL额外启用同步能力，QUERY仅关闭
 * T+1和polling同步任务、Reader、Worker及写入链路</p>
 */
public enum EsServiceMode {

    ALL(true),
    QUERY(false);

    private final boolean syncEnabled;

    EsServiceMode(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    /**
     * 是否启用T+1或polling同步能力
     */
    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    /**
     * 解析配置值；未配置时保持历史行为，默认使用 ALL
     *
     * @param value sano.server-mode 配置值
     * @return 解析后的运行模式
     */
    public static EsServiceMode fromConfig(String value) {
        String normalized = StringUtils.defaultIfBlank(value, ALL.name())
                .trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return EsServiceMode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported sano.server-mode: " + value
                    + ", expected one of: all, query", e);
        }
    }
}
