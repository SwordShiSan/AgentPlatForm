package com.agentplatform.app.chat;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.*;

@Configuration @Profile("chat-local")
public class ChatWebConfiguration implements WebMvcConfigurer {
    @Bean ThreadPoolTaskExecutor sseExecutor(){var pool=new ThreadPoolTaskExecutor();pool.setCorePoolSize(4);pool.setMaxPoolSize(8);pool.setQueueCapacity(0);pool.setThreadNamePrefix("chat-sse-");pool.initialize();return pool;}
    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer){configurer.setTaskExecutor(sseExecutor()).setDefaultTimeout(30000);}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){@Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){response.setHeader("Cache-Control","no-store");return true;}}).addPathPatterns("/api/v1/**");}
}
