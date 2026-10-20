package com.agentplatform.app.chat;

import com.agentplatform.api.chat.ChatEventStream;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration
@Profile("chat-local")
@EnableConfigurationProperties(EventStreamingProperties.class)
public class ChatWebConfiguration implements WebMvcConfigurer {
    @Bean ThreadPoolTaskExecutor sseExecutor() {
        var pool = new ThreadPoolTaskExecutor(); pool.setCorePoolSize(4); pool.setMaxPoolSize(8);
        pool.setQueueCapacity(0); pool.setThreadNamePrefix("chat-sse-"); pool.initialize(); return pool;
    }
    @Bean ThreadPoolTaskExecutor eventReadExecutor() {
        var pool = new ThreadPoolTaskExecutor(); pool.setCorePoolSize(2); pool.setMaxPoolSize(2);
        pool.setQueueCapacity(16); pool.setThreadNamePrefix("event-read-"); pool.initialize(); return pool;
    }
    @Bean(destroyMethod = "dispose") Scheduler eventReads() { return Schedulers.fromExecutorService(eventReadExecutor().getThreadPoolExecutor()); }
    @Bean(destroyMethod = "dispose") Scheduler eventTimers() { return Schedulers.newParallel("event-timer", 2, true); }
    @Bean ChatEventStream chatEventStream(com.agentplatform.api.chat.ChatApi chat, EventStreamingProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("eventReads") Scheduler reads,
            @org.springframework.beans.factory.annotation.Qualifier("eventTimers") Scheduler timers) {
        return new EventStreamService(chat, properties, reads, timers);
    }
    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) { configurer.setTaskExecutor(sseExecutor()).setDefaultTimeout(30000); }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) { response.setHeader("Cache-Control", "no-store"); return true; }
            private void close(HttpServletRequest request) {
                if (request.getAttribute(ChatEventStream.Handle.class.getName()) instanceof ChatEventStream.Handle handle) handle.close();
            }
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getAttribute(ChatEventStream.Handle.class.getName()) == null) return;
                var async = request.getAsyncContext(); async.setTimeout(30000);
                async.addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent event) { close(request); }
                    @Override public void onTimeout(AsyncEvent event) { close(request); }
                    @Override public void onError(AsyncEvent event) { close(request); }
                    @Override public void onStartAsync(AsyncEvent event) {}
                });
            }
            @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) { close(request); }
        }).addPathPatterns("/api/v1/**");
    }
}
