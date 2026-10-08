package com.triagedeck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
// 自动注册本包及子包里所有 @ConfigurationProperties 类，不用再逐个写 @EnableConfigurationProperties
@ConfigurationPropertiesScan
public class TriageDeckApplication {

    public static void main(String[] args) {
        SpringApplication.run(TriageDeckApplication.class, args);
    }
}
