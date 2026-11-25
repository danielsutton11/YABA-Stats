package com.dsuttz.yaba.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class KeyRepository {

    public record StoredKey(long id, String key, Long ownerTornId, boolean healthy, String lastError,
                            boolean bspActive, OffsetDateTime bspExpiresAt, OffsetDateTime bspCheckedAt) { }

    private static final String COLS = "id, api_key, owner_torn_id, healthy, last_error, bsp_active, bsp_expires_at, bsp_checked_at";
    private final JdbcClient jdbc;

    public KeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<StoredKey> all() {
        return jdbc.sql("SELECT " + COLS + " FROM api_key ORDER BY id").query(this::map).list();
    }

    public List<StoredKey> healthy() {
        return jdbc.sql("SELECT " + COLS + " FROM api_key WHERE healthy ORDER BY id").query(this::map).list();
    }

    public List<StoredKey> bspActive() {
        return jdbc.sql("SELECT " + COLS + " FROM api_key WHERE healthy AND bsp_active "
                + "AND (bsp_expires_at IS NULL OR bsp_expires_at > now()) ORDER BY id").query(this::map).list();
    }

    public Optional<StoredKey> find(String key) {
        return jdbc.sql("SELECT " + COLS + " FROM api_key WHERE api_key = ?").param(key).query(this::map).optional();
    }

    /** Insert or refresh a key after it validated against Torn. */
    public void upsertValid(String key, long ownerTornId) {
        jdbc.sql("""
                INSERT INTO api_key (api_key, owner_torn_id, healthy, last_error) VALUES (?, ?, TRUE, NULL)
                ON CONFLICT (api_key) DO UPDATE SET owner_torn_id = EXCLUDED.owner_torn_id, healthy = TRUE, last_error = NULL""")
                .params(key, ownerTornId).update();
    }

    public void markUnhealthy(long id, String reason) {
        jdbc.sql("UPDATE api_key SET healthy = FALSE, last_error = ? WHERE id = ?").params(reason, id).update();
    }

    public void setBsp(long id, boolean active, OffsetDateTime expires) {
        jdbc.sql("UPDATE api_key SET bsp_active = ?, bsp_expires_at = ?, bsp_checked_at = now() WHERE id = ?")
                .params(active, expires, id).update();
    }

    private StoredKey map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long ownerRaw = rs.getLong("owner_torn_id");
        Long owner = rs.wasNull() ? null : ownerRaw;
        return new StoredKey(rs.getLong("id"), rs.getString("api_key"), owner,
                rs.getBoolean("healthy"), rs.getString("last_error"), rs.getBoolean("bsp_active"),
                rs.getObject("bsp_expires_at", OffsetDateTime.class), rs.getObject("bsp_checked_at", OffsetDateTime.class));
    }
}
