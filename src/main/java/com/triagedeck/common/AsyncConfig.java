package com.triagedeck.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 打开 @Async：标了 @Async 的方法会交给 Spring Boot 自带的线程池在后台执行，调用方立刻返回。
 * 目前用于发验证邮件，发信慢（要连 SMTP 服务器）不会拖慢接口响应。
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {}
