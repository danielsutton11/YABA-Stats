package com.dsuttz.yaba.store;

import com.dsuttz.yaba.config.KeyCrypto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class KeyRepository {

    public record StoredKey(long id, String key, Long ownerTornId, boolean healthy, String lastError,
                            boolean ffsActive, OffsetDateTime ffsCheckedAt, String ffsError) { }

    // only rows already encrypted are read; plain-text rows are converted at start-up (KeyEncryptionMigrator)
    private static final String COLS = "id, key_enc, owner_torn_id, healthy, last_error, ffs_active, ffs_checked_at, ffs_error";
    private static final String ENC = " key_enc IS NOT NULL ";
    private final JdbcClient jdbc;
    private final KeyCrypto crypto;

    public KeyRepository(JdbcClient jdbc, KeyCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public List<StoredKey> all() {
        return jdbc.sql("SELECT " + COLS + " FROM api_key WHERE" + ENC + "ORDER BY id").query(this::map).list();
    }

    public List<StoredKey> healthy() {
        return jdbc.sql("SELECT " + COLS + " FROM api_key WHERE healthy AND" + ENC + "ORDER BY id").query(this::map).list();
    }

    /** Healthy keys registered with FFScouter. */
    public List<StoredKey> ffsActive() {
        return jdbc.sql("SELECT " + COLS + " FROM api_key WHERE healthy AND ffs_active AND" + ENC + "ORDER BY id")
                .query(this::map).list();
    }

    public Optional<StoredKey> find(String key) {
        return jdbc.sql("SELECT " + COLS + " FROM api_key WHERE key_hash = ? AND" + ENC)
                .param(crypto.fingerprint(key)).query(this::map).optional();
    }

    /** Insert or refresh a key after it validated against Torn. */
    public void upsertValid(String key, long ownerTornId) {
        jdbc.sql("""
                INSERT INTO api_key (key_enc, key_hash, owner_torn_id, healthy, last_error) VALUES (?, ?, ?, TRUE, NULL)
                ON CONFLICT (key_hash) DO UPDATE SET owner_torn_id = EXCLUDED.owner_torn_id, healthy = TRUE, last_error = NULL""")
                .params(crypto.encrypt(key), crypto.fingerprint(key), ownerTornId).update();
    }

    public void markUnhealthy(long id, String reason) {
        jdbc.sql("UPDATE api_key SET healthy = FALSE, last_error = ? WHERE id = ?").params(reason, id).update();
    }

    public void setFfs(long id, boolean active, String error) {
        jdbc.sql("UPDATE api_key SET ffs_active = ?, ffs_error = ?, ffs_checked_at = now() WHERE id = ?")
                .params(active, error, id).update();
    }

    private StoredKey map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long ownerRaw = rs.getLong("owner_torn_id");
        Long owner = rs.wasNull() ? null : ownerRaw;
        return new StoredKey(rs.getLong("id"), crypto.decrypt(rs.getString("key_enc")), owner,
                rs.getBoolean("healthy"), rs.getString("last_error"), rs.getBoolean("ffs_active"),
                rs.getObject("ffs_checked_at", OffsetDateTime.class), rs.getString("ffs_error"));
    }
}
