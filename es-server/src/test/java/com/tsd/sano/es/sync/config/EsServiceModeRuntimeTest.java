package com.tsd.sano.es.sync.config;

import com.tsd.sano.es.SanoEsApplication;
import com.tsd.sano.es.core.config.AsyncConfig;
import com.tsd.sano.es.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * server-mode运行时能力和公共基础设施注册测试。
 */
class EsServiceModeRuntimeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EsServiceModeManager.class, AsyncConfig.class);

    /**
     * 缺省模式保持历史all行为，同时启用查询与同步能力。
     */
    @Test
    void shouldDefaultToAllMode() {
        contextRunner.run(context -> {
            EsServiceModeManager manager = context.getBean(EsServiceModeManager.class);
            assertThat(manager.currentMode()).isEqualTo(EsServiceMode.ALL);
            assertThat(manager.isSyncEnabled()).isTrue();
            assertThat(context).hasBean("esImportExecutor");
        });
    }

    /**
     * query模式仍注册公共执行器，但运行时拒绝同步能力。
     */
    @Test
    void shouldKeepInfrastructureAndDisableOnlySyncCapabilityInQueryMode() {
        contextRunner.withPropertyValues("sano.server-mode=query")
                .run(context -> {
                    EsServiceModeManager manager = context.getBean(EsServiceModeManager.class);
                    assertThat(context).hasBean("esImportExecutor");
                    assertThat(manager.isSyncEnabled()).isFalse();
                    assertThatThrownBy(manager::requireSyncEnabled)
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("serviceMode=QUERY");
                });
    }

    /**
     * 当前节点不支持仅同步consumer模式，避免引入暂时无用的第三种部署角色。
     */
    @Test
    void shouldRejectRemovedConsumerMode() {
        contextRunner.withPropertyValues("sano.server-mode=consumer")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 非法模式必须在启动阶段失败，不能静默退化。
     */
    @Test
    void shouldRejectUnsupportedMode() {
        contextRunner.withPropertyValues("sano.server-mode=worker")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 调度能力在应用层统一启用，新增定时器无需重复声明调度配置。
     */
    @Test
    void shouldEnableSchedulingGlobally() {
        assertThat(SanoEsApplication.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
    }
}
