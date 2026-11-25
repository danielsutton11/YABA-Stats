package com.dsuttz.yaba;

import com.dsuttz.yaba.config.YabaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(YabaProperties.class)
public class YabaApplication {
    public static void main(String[] args) {
        SpringApplication.run(YabaApplication.class, args);
    }
}
