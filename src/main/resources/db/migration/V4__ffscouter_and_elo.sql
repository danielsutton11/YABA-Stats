-- Battle stat estimates now come from FFScouter instead of BSP.
CREATE TABLE stat_estimate (
    torn_id           BIGINT PRIMARY KEY,
    bs_estimate       BIGINT,
    fair_fight        DOUBLE PRECISION,
    source            TEXT,             -- FFScouter's source for the estimate: bss | premium | spies
    estimate_updated  TIMESTAMPTZ,      -- when FFScouter last updated it
    fetched_at        TIMESTAMPTZ NOT NULL
);
DROP TABLE IF EXISTS bsp_prediction;

ALTER TABLE api_key DROP COLUMN IF EXISTS bsp_active;
ALTER TABLE api_key DROP COLUMN IF EXISTS bsp_expires_at;
ALTER TABLE api_key DROP COLUMN IF EXISTS bsp_checked_at;
ALTER TABLE api_key ADD COLUMN ffs_active BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE api_key ADD COLUMN ffs_checked_at TIMESTAMPTZ;
ALTER TABLE api_key ADD COLUMN ffs_error TEXT;

-- Current Torn Elo (personal stat "elo").
ALTER TABLE member_stats ADD COLUMN elo BIGINT;
ALTER TABLE member_stats ADD COLUMN elo_at TIMESTAMPTZ;
