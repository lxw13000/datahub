package com.tsd.sano.es.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用健康检查接口。
 * 用于 Docker HEALTHCHECK 判断 Web 服务是否已经真实可访问。
 */
@RestController
public class HealthController {

    /**
     * 返回轻量健康状态。
     *
     * @return 固定返回 OK，表示 Spring Web 容器已启动并可处理请求
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
