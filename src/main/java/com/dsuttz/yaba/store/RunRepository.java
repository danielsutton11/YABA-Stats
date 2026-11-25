package com.dsuttz.yaba.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class RunRepository {

    public record Run(long id, String kind, Long factionId, String factionType, boolean rosterOnly, String status,
                      String phase, int done, int total, String message,
                      OffsetDateTime startedAt, OffsetDateTime finishedAt) { }

    private static final String COLS = "id, kind, faction_id, faction_type, roster_only, status, phase, done, total, message, started_at, finished_at";
    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long start(String kind, Long factionId, String type, boolean rosterOnly) {
        return jdbc.sql("INSERT INTO run (kind, faction_id, faction_type, roster_only, status, phase) "
                        + "VALUES (?, ?, ?, ?, 'running', 'queued') RETURNING id")
                .params(kind, factionId, type, rosterOnly).query(Long.class).single();
    }

    public void progress(long id, String phase, int done, int total) {
        jdbc.sql("UPDATE run SET phase = ?, done = ?, total = ? WHERE id = ?").params(phase, done, total, id).update();
    }

    public void finish(long id, String status, String message) {
        jdbc.sql("UPDATE run SET status = ?, phase = 'finished', message = ?, finished_at = now() WHERE id = ?")
                .params(status, message, id).update();
    }

    public Optional<Run> find(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM run WHERE id = ?").param(id).query(this::map).optional();
    }

    public Optional<Run> latest(String kind) {
        return jdbc.sql("SELECT " + COLS + " FROM run WHERE kind = ? ORDER BY id DESC LIMIT 1").param(kind).query(this::map).optional();
    }

    /** Last finished full (not roster-only) YABA run — the report's "as of" time. */
    public Optional<Run> latestCompleteYaba() {
        return jdbc.sql("SELECT " + COLS + " FROM run WHERE kind = 'yaba' AND NOT roster_only AND status = 'done' "
                + "ORDER BY id DESC LIMIT 1").query(this::map).optional();
    }

    public Optional<Run> latestProspect(long factionId) {
        return jdbc.sql("SELECT " + COLS + " FROM run WHERE kind = 'prospect' AND faction_id = ? ORDER BY id DESC LIMIT 1")
                .param(factionId).query(this::map).optional();
    }

    public List<Run> running() {
        return jdbc.sql("SELECT " + COLS + " FROM run WHERE status = 'running' ORDER BY id").query(this::map).list();
    }

    /** On startup: anything left "running" by a restart/redeploy is marked failed. */
    public int failOrphans() {
        return jdbc.sql("UPDATE run SET status = 'failed', message = 'Interrupted by restart', finished_at = now() "
                + "WHERE status = 'running'").update();
    }

    private Run map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long fidRaw = rs.getLong("faction_id");
        Long fid = rs.wasNull() ? null : fidRaw;
        return new Run(rs.getLong("id"), rs.getString("kind"), fid, rs.getString("faction_type"),
                rs.getBoolean("roster_only"), rs.getString("status"), rs.getString("phase"), rs.getInt("done"),
                rs.getInt("total"), rs.getString("message"),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class));
    }
}
