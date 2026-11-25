-- API keys are now stored encrypted (AES-GCM, key from YABA_KEY_SECRET).
-- key_hash is an HMAC fingerprint used to find / de-duplicate keys without decrypting.
-- Existing plaintext keys are encrypted by KeyEncryptionMigrator on the next start, which then
-- blanks the old api_key column.
ALTER TABLE api_key ADD COLUMN key_enc TEXT;
ALTER TABLE api_key ADD COLUMN key_hash TEXT;
ALTER TABLE api_key ALTER COLUMN api_key DROP NOT NULL;
ALTER TABLE api_key DROP CONSTRAINT IF EXISTS api_key_api_key_key;
CREATE UNIQUE INDEX api_key_hash_idx ON api_key (key_hash);
