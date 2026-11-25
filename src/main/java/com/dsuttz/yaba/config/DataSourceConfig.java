package com.dsuttz.yaba.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Uses SPRING_DATASOURCE_URL (+ username/password) if set, otherwise Railway's
 * DATABASE_URL (postgresql://user:pass@host:port/db), so the service can simply reference
 * ${{Postgres.DATABASE_URL}}.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(Environment env) {
        HikariDataSource ds = new HikariDataSource();
        String jdbc = env.getProperty("spring.datasource.url");
        if (jdbc != null && !jdbc.isBlank()) {
            ds.setJdbcUrl(jdbc);
            ds.setUsername(env.getProperty("spring.datasource.username"));
            ds.setPassword(env.getProperty("spring.datasource.password"));
        } else {
            String raw = env.getProperty("DATABASE_URL");
            if (raw == null || raw.isBlank()) {
                throw new IllegalStateException("Set DATABASE_URL (Railway: ${{Postgres.DATABASE_URL}}) or SPRING_DATASOURCE_URL");
            }
            URI u = URI.create(raw.replaceFirst("^postgres(ql)?://", "http://"));
            String[] userInfo = u.getUserInfo() == null ? new String[0] : u.getUserInfo().split(":", 2);
            int port = u.getPort() > 0 ? u.getPort() : 5432;
            ds.setJdbcUrl("jdbc:postgresql://" + u.getHost() + ":" + port + u.getPath()
                    + (u.getQuery() != null ? "?" + u.getQuery() : ""));
            if (userInfo.length > 0) ds.setUsername(java.net.URLDecoder.decode(userInfo[0], java.nio.charset.StandardCharsets.UTF_8));
            if (userInfo.length > 1) ds.setPassword(java.net.URLDecoder.decode(userInfo[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        ds.setMaximumPoolSize(10);
        return ds;
    }
}
