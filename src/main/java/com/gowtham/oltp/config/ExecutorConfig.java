package com.gowtham.oltp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Thread pool configuration for async transaction processing.
 *
 * Uses a bounded queue with CallerRunsPolicy to apply backpressure
 * rather than silently dropping tasks under sustained high load.
 */
@Slf4j
@Configuration
public class ExecutorConfig {

    @Value("${transaction.executor.core-pool-size:10}")
    private int corePoolSize;

    @Value("${transaction.executor.max-pool-size:50}")
    private int maxPoolSize;

    @Value("${transaction.executor.queue-capacity:500}")
    private int queueCapacity;

    @Bean(name = "transactionExecutor")
    public Executor transactionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("txn-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("Transaction executor initialized: corePool={}, maxPool={}, queueCapacity={}",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }
}
