-- Torn API keys sent from the sheet. Keys of the same player share one rate limit (owner_torn_id).
CREATE TABLE api_key (
    id              BIGSERIAL PRIMARY KEY,
    api_key         TEXT        NOT NULL UNIQUE,
    owner_torn_id   BIGINT,
    healthy         BOOLEAN     NOT NULL DEFAULT TRUE,
    last_error      TEXT,
    bsp_active      BOOLEAN     NOT NULL DEFAULT FALSE,
    bsp_expires_at  TIMESTAMPTZ,
    bsp_checked_at  TIMESTAMPTZ,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- YABA factions (is_yaba) and prospective factions that have been analysed.
CREATE TABLE faction (
    id              BIGINT PRIMARY KEY,
    name            TEXT,
    faction_type    TEXT        NOT NULL DEFAULT 'Combat',
    is_yaba         BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_order      INT,
    leader_id       BIGINT,
    leader_name     TEXT,
    co_leader_id    BIGINT,
    co_leader_name  TEXT,
    member_count    INT,
    capacity        INT,
    roster_at       TIMESTAMPTZ,
    roster_error    TEXT
);

CREATE TABLE member (
    torn_id          BIGINT PRIMARY KEY,
    faction_id       BIGINT,
    name             TEXT,
    level            INT,
    position         TEXT,
    days_in_faction  INT,
    last_action      TEXT,
    last_action_ts   BIGINT,
    updated_at       TIMESTAMPTZ
);
CREATE INDEX member_faction_idx ON member (faction_id);

-- Personal stats: current values and values at 30 / 90 days ago.
-- *_at marks when each slot was fetched (NULL = not fetched yet).
CREATE TABLE member_stats (
    torn_id  BIGINT PRIMARY KEY,
    xan_now  BIGINT, tp_now BIGINT, rw_now BIGINT, rev_now BIGINT, now_at TIMESTAMPTZ,
    xan_30   BIGINT, tp_30  BIGINT, rw_30  BIGINT, rev_30  BIGINT, at_30  TIMESTAMPTZ,
    xan_90   BIGINT, tp_90  BIGINT, rw_90  BIGINT, rev_90  BIGINT, at_90  TIMESTAMPTZ,
    error    TEXT
);

-- Battle Stats Predictor results (cached for yaba.bsp-cache-days).
CREATE TABLE bsp_prediction (
    torn_id     BIGINT PRIMARY KEY,
    tbs         BIGINT,
    score       BIGINT,
    result      INT,
    fetched_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE run (
    id            BIGSERIAL PRIMARY KEY,
    kind          TEXT        NOT NULL,          -- yaba | prospect
    faction_id    BIGINT,                         -- prospect only
    faction_type  TEXT,
    roster_only   BOOLEAN     NOT NULL DEFAULT FALSE,
    status        TEXT        NOT NULL,          -- running | done | failed
    phase         TEXT,
    done          INT         NOT NULL DEFAULT 0,
    total         INT         NOT NULL DEFAULT 0,
    message       TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);
CREATE INDEX run_kind_idx ON run (kind, started_at DESC);
