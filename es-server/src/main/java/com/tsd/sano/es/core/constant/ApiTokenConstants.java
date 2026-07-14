package com.tsd.sano.es.core.constant;

import java.util.Set;

/**
 * 内部 API Token 常量。
 *
 * <p>当前服务仅供内部调用，Token 固定维护在代码中。后续接入统一认证时，
 * 调用方继续使用 token 字段，只需替换过滤器中的校验实现。</p>
 *
 * @author lxw
 */
public final class ApiTokenConstants {

    /** 内部接口使用的 Token 请求头和 URL 参数名称。 */
    public static final String TOKEN_NAME = "token";

    /**
     * 内部调用方 Token 白名单。
     *
     * <p>新增或轮换 Token 时直接维护此集合；Token 原文不可输出到日志。</p>
     */
    public static final Set<String> VALID_TOKENS = Set.of(
            "sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F",
            "sano-es-B6v3L9a1Q5e8N2u7G4k0S6p9X3j5M8d",
            "sano-es-H4r8C2z6W1f9J5t3K7m0D4q8V2y6P1n"
    );

    /**
     * 无需 Token 的公开路径。
     *
     * <p>仅保留容器健康检查和 Spring 错误转发，不能在此添加业务接口。</p>
     */
    public static final Set<String> PUBLIC_PATHS = Set.of(
            "/health",
            "/error"
    );

    private ApiTokenConstants() {
    }
}
