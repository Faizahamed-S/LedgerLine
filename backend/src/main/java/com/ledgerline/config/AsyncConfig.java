package com.ledgerline.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String ANOMALY_EXECUTOR = "anomalyExecutor";

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Anomaly flagging runs here, off the request thread. Running it on the request thread after commit would
     * need a second pool connection while the first is still held, which can exhaust the pool under load.
     * Flags are advisory: if the queue is full, evaluation is skipped rather than slowing money movement.
     */
    @Bean(name = ANOMALY_EXECUTOR)
    public ThreadPoolTaskExecutor anomalyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("anomaly-");
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Anomaly evaluation queue is full; skipping flag evaluation for one transaction"));
        return executor;
    }
}
