package com.dsuttz.yaba.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yaba")
public record YabaProperties(
        String apiSecret,
        String tornBase,
        String ffsBase,
        int rpmPerPlayer,
        int statsCacheHours,
        int ffsCacheHours,
        int ffsBatchSize,
        int ffsPerSecond,
        String nightlyCron,
        String zone,
        String comment) {
}
