package com.rpgmaster.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.Executors;

/**
 * Virtual Thread configuration — Java 21.
 *
 * <p>Enables Virtual Threads for all async and Tomcat request handling.
 * This is set up correctly even in the CLI MVP to demonstrate Java 21 knowledge.
 *
 * <p>Note: PDFBox may pin Virtual Threads due to internal {@code synchronized}
 * blocks. Monitor with {@code -Djdk.tracePinnedThreads=full} if throughput drops.
 */
@Configuration
public class VirtualThreadConfig {

    /**
     * Replaces the default Spring async executor with a Virtual Thread executor.
     * All {@code @Async} methods and Spring Shell commands will use Virtual Threads.
     */
    @Bean
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
