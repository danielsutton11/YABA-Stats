package com.dsuttz.yaba.store;

import com.dsuttz.yaba.config.KeyCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** On start: encrypts any key still stored in plain text (from before V2) and blanks the plain copy. */
@Component
@Order(0)
public class KeyEncryptionMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KeyEncryptionMigrator.class);
    private final JdbcClient jdbc;
    private final KeyCrypto crypto;

    public KeyEncryptionMigrator(JdbcClient jdbc, KeyCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        record Plain(long id, String key) { }
        var rows = jdbc.sql("SELECT id, api_key FROM api_key WHERE api_key IS NOT NULL")
                .query((rs, i) -> new Plain(rs.getLong(1), rs.getString(2))).list();
        for (Plain p : rows) {
            jdbc.sql("UPDATE api_key SET key_enc = ?, key_hash = ?, api_key = NULL WHERE id = ?")
                    .params(crypto.encrypt(p.key()), crypto.fingerprint(p.key()), p.id()).update();
        }
        if (!rows.isEmpty()) log.info("Encrypted {} stored API key(s)", rows.size());
    }
}
