package com.tsd.sano.es.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * ES后台任务使用的异步执行器配置。
 *
 * <p>所有server-mode注册同一执行器；query模式只是不提交同步任务。
 * 定时调度由应用全局启用，具体任务在执行入口检查运行模式和功能开关。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 创建 T+1 人工任务和任务分发使用的后台执行器。
     *
     * @return 已初始化的导入任务执行器
     */
    @Bean(name = "esImportExecutor")
    public Executor esImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);          // 核心线程数
        executor.setMaxPoolSize(20);          // 最大线程数
        executor.setQueueCapacity(100);       // 队列容量
        executor.setKeepAliveSeconds(60);     // 空闲线程存活时间
        executor.setThreadNamePrefix("ES-import-");
        executor.setWaitForTasksToCompleteOnShutdown(true); // 关闭时等待任务完成
        executor.initialize();
        return executor;
    }

    /**
     * 创建统计对账、Polling历史索引删除和失败批次通知使用的后台执行器。
     *
     * <p>该执行器与T+1任务分离，承载不应阻塞同步主循环的最佳努力副作用。
     * 应用关闭时不等待队列执行完成，也不增加对应的YML线程和队列配置。</p>
     *
     * @return 已初始化的对账后台执行器
     */
    @Bean(name = "esReconcileExecutor")
    public Executor esReconcileExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(20);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("ES-reconcile-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
