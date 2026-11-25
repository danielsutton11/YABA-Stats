package com.dsuttz.yaba.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yaba")
public record YabaProperties(
        String apiSecret,
        String tornBase,
        String bspBase,
        String bspVersion,
        int rpmPerPlayer,
        int statsCacheHours,
        int bspCacheDays,
        int bspPerSecond,
        String nightlyCron,
        String zone,
        String comment) {
}
