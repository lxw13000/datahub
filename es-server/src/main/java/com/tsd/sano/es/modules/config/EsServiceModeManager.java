package com.tsd.sano.es.modules.config;

import com.tsd.sano.es.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 当前进程的服务模式与运行时能力门禁
 *
 * <p>服务模式不改变Bean注册结果，查询能力始终开放，只控制同步调度和后台流水线
 * 是否允许执行模式在进程启动时确定，同一镜像可作为all或query实例运行</p>
 */
@Component
public class EsServiceModeManager {

    private final EsServiceMode serviceMode;

    /**
     * 读取启动配置并固定当前进程的服务模式
     */
    public EsServiceModeManager(@Value("${sano.server-mode:all}") String configuredMode) {
        this.serviceMode = EsServiceMode.fromConfig(configuredMode);
    }

    /**
     * 返回当前进程配置的服务模式
     */
    public EsServiceMode currentMode() {
        return serviceMode;
    }

    /**
     * 当前实例是否允许执行同步调度、读取和写入
     */
    public boolean isSyncEnabled() {
        return serviceMode.isSyncEnabled();
    }

    /**
     * 校验当前实例具备同步职责，query模式下直接拒绝调用
     */
    public void requireSyncEnabled() {
        if (!isSyncEnabled()) {
            throw new ServiceException(503, "ES sync capability is disabled: serviceMode="
                    + serviceMode.name());
        }
    }
}
