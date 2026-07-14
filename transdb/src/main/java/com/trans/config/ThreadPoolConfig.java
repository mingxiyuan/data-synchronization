package com.trans.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Configuration
public class ThreadPoolConfig {

    @Value("${transdb.sync.core-threads:20}")
    private int syncCoreThreads;

    @Value("${transdb.sync.max-threads:40}")
    private int syncMaxThreads;

    @Value("${transdb.sync.queue-size:100}")
    private int syncQueueSize;

    @Value("${transdb.merge.core-threads:20}")
    private int mergeCoreThreads;

    @Value("${transdb.merge.max-threads:40}")
    private int mergeMaxThreads;

    @Bean(name = "syncExecutor", destroyMethod = "")
    public ExecutorService syncExecutor() {
        return new ThreadPoolExecutor(
                syncCoreThreads, syncMaxThreads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(syncQueueSize),
                new NamedThreadFactory("sync-exec"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean(name = "mergeExecutor", destroyMethod = "")
    public ExecutorService mergeExecutor() {
        return new ThreadPoolExecutor(
                mergeCoreThreads, mergeMaxThreads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(400),
                new NamedThreadFactory("sync-merge"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @PreDestroy
    public void shutdown() {
        shutdownExecutor(syncExecutor(), "syncExecutor");
        shutdownExecutor(mergeExecutor(), "mergeExecutor");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        log.info("正在关闭线程池 {}...", name);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("线程池 {} 未在30秒内完成, 强制关闭", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("线程池 {} 已关闭", name);
    }

    static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String prefix;

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
