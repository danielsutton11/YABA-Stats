package com.dsuttz.yaba.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Factions, members, personal-stat snapshots, Elo and FFScouter estimates. */
@Repository
public class DataRepository {

    public record FactionRef(long id, String type) { }

    public record Faction(long id, String name, String type, boolean yaba, Integer sortOrder,
                          Long leaderId, String leaderName, Long coLeaderId, String coLeaderName,
                          Integer memberCount, Integer capacity, OffsetDateTime rosterAt, String rosterError,
                          String rankLabel, Integer rankLevel, Integer territories, Long respect) { }

    public record RosterMember(long id, String name, Integer level, String position, Integer daysInFaction,
                               String lastAction, Long lastActionTs, String statusState) {
        public boolean fallen() {
            return "Fallen".equalsIgnoreCase(statusState);
        }
    }

    /** SQL condition: member is not Fallen (Fallen members are left out of everything). */
    private static final String ACTIVE = " AND COALESCE(m.status_state, '') <> 'Fallen' ";

    /** Which stat slots a member still needs (elo = current Elo is missing or stale). */
    public record StatNeed(long tornId, boolean now, boolean d30, boolean d90, boolean elo) { }

    /** One joined row for reports. Stats values are null when not fetched / errored. */
    public record MemberRow(long factionId, String factionName, long id, String name, Integer level, String position,
                            Integer daysInFaction, String lastAction,
                            Long[] now, Long[] d30, Long[] d90, String error, Long elo,
                            Long bsEstimate, String bsSource, OffsetDateTime bsFetchedAt) { }

    public enum Slot {
        NOW("now", "now_at"), D30("30", "at_30"), D90("90", "at_90");
        final String suffix, atCol;
        Slot(String suffix, String atCol) { this.suffix = suffix; this.atCol = atCol; }
        String cols() { return "xan_" + suffix + ", tp_" + suffix + ", rw_" + suffix + ", rev_" + suffix; }
    }

    private final JdbcClient jdbc;

    public DataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- factions

    /** Replaces the YABA list with the sheet's (order kept). Factions dropped from it keep their data. */
    @Transactional
    public void setYabaList(List<FactionRef> list) {
        jdbc.sql("UPDATE faction SET is_yaba = FALSE, sort_order = NULL WHERE is_yaba").update();
        int i = 0;
        for (FactionRef f : list) {
            jdbc.sql("""
                    INSERT INTO faction (id, faction_type, is_yaba, sort_order) VALUES (?, ?, TRUE, ?)
                    ON CONFLICT (id) DO UPDATE SET faction_type = EXCLUDED.faction_type, is_yaba = TRUE,
                        sort_order = EXCLUDED.sort_order""")
                    .params(f.id(), normType(f.type()), i++).update();
        }
    }

    /** A prospect check: create the faction if new; YABA factions keep their own type. */
    public void ensureProspect(long id, String type) {
        jdbc.sql("""
                INSERT INTO faction (id, faction_type, is_yaba) VALUES (?, ?, FALSE)
                ON CONFLICT (id) DO UPDATE SET faction_type =
                    CASE WHEN faction.is_yaba THEN faction.faction_type ELSE EXCLUDED.faction_type END""")
                .params(id, normType(type)).update();
    }

    public List<Faction> yabaFactions() {
        return jdbc.sql("SELECT * FROM faction WHERE is_yaba ORDER BY sort_order, id").query(this::mapFaction).list();
    }

    public Optional<Faction> faction(long id) {
        return jdbc.sql("SELECT * FROM faction WHERE id = ?").param(id).query(this::mapFaction).optional();
    }

    @Transactional
    public void saveRoster(long factionId, String name, Long leaderId, Long coLeaderId, Integer capacity,
                           String rankLabel, Integer rankLevel, Integer territories, Long respect, List<RosterMember> members) {
        Map<Long, String> names = members.stream().collect(Collectors.toMap(RosterMember::id,
                m -> m.name() == null ? "" : m.name(), (a, b) -> a));
        jdbc.sql("""
                UPDATE faction SET name = ?, leader_id = ?, leader_name = ?, co_leader_id = ?, co_leader_name = ?,
                    member_count = ?, capacity = ?, rank_label = ?, rank_level = ?, territories = ?, respect = ?, roster_at = now(), roster_error = NULL
                WHERE id = ?""")
                .params(name, leaderId, leaderId == null ? null : names.get(leaderId),
                        coLeaderId, coLeaderId == null ? null : names.get(coLeaderId),
                        (int) members.stream().filter(m -> !m.fallen()).count(), capacity, rankLabel, rankLevel, territories, respect, factionId).update();
        for (RosterMember m : members) {
            jdbc.sql("""
                    INSERT INTO member (torn_id, faction_id, name, level, position, days_in_faction, last_action,
                        last_action_ts, status_state, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (torn_id) DO UPDATE SET faction_id = EXCLUDED.faction_id, name = EXCLUDED.name,
                        level = EXCLUDED.level, position = EXCLUDED.position,
                        days_in_faction = EXCLUDED.days_in_faction, last_action = EXCLUDED.last_action,
                        last_action_ts = EXCLUDED.last_action_ts, status_state = EXCLUDED.status_state, updated_at = now()""")
                    .params(m.id(), factionId, m.name(), m.level(), m.position(), m.daysInFaction(),
                            m.lastAction(), m.lastActionTs(), m.statusState()).update();
        }
        // people who left this faction
        String keep = members.isEmpty() ? "-1" : members.stream().map(m -> String.valueOf(m.id())).collect(Collectors.joining(","));
        jdbc.sql("UPDATE member SET faction_id = NULL WHERE faction_id = ? AND torn_id NOT IN (" + keep + ")")
                .param(factionId).update();
    }

    public void rosterError(long factionId, String message) {
        jdbc.sql("UPDATE faction SET roster_error = ? WHERE id = ?").params(message, factionId).update();
    }

    // ---------------------------------------------------------------- stats

    public List<StatNeed> statNeeds(Collection<Long> factionIds, int cacheHours) {
        if (factionIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT m.torn_id,
                    (s.now_at IS NULL OR s.now_at < now() - make_interval(hours => ?)) AS n,
                    (s.at_30  IS NULL OR s.at_30  < now() - make_interval(hours => ?)) AS a,
                    (s.at_90  IS NULL OR s.at_90  < now() - make_interval(hours => ?)) AS b,
                    (s.elo_at IS NULL OR s.elo_at < now() - make_interval(hours => ?)) AS e
                FROM member m LEFT JOIN member_stats s ON s.torn_id = m.torn_id
                WHERE m.faction_id IN (""" + ids(factionIds) + ")" + ACTIVE)
                .params(cacheHours, cacheHours, cacheHours, cacheHours)
                .query((rs, i) -> new StatNeed(rs.getLong(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5)))
                .list().stream().filter(n -> n.now() || n.d30() || n.d90() || n.elo()).toList();
    }

    /** Clears old error notes for members about to be refreshed (new errors are written as they happen). */
    public void clearErrors(Collection<Long> tornIds) {
        if (tornIds.isEmpty()) return;
        jdbc.sql("UPDATE member_stats SET error = NULL WHERE torn_id IN (" + ids(tornIds) + ")").update();
    }

    /** values = xantaken, timeplayed, rankedwarhits, revives (null when the stat wasn't returned). */
    public void saveSlot(long tornId, Slot slot, Long[] values) {
        jdbc.sql("INSERT INTO member_stats (torn_id, " + slot.cols() + ", " + slot.atCol + ") VALUES (?, ?, ?, ?, ?, now()) "
                        + "ON CONFLICT (torn_id) DO UPDATE SET xan_" + slot.suffix + " = EXCLUDED.xan_" + slot.suffix
                        + ", tp_" + slot.suffix + " = EXCLUDED.tp_" + slot.suffix
                        + ", rw_" + slot.suffix + " = EXCLUDED.rw_" + slot.suffix
                        + ", rev_" + slot.suffix + " = EXCLUDED.rev_" + slot.suffix
                        + ", " + slot.atCol + " = now()")
                .params(tornId, values[0], values[1], values[2], values[3]).update();
    }

    public void saveSlotError(long tornId, Slot slot, String error) {
        jdbc.sql("INSERT INTO member_stats (torn_id, " + slot.atCol + ", error) VALUES (?, now(), ?) "
                        + "ON CONFLICT (torn_id) DO UPDATE SET xan_" + slot.suffix + " = NULL, tp_" + slot.suffix + " = NULL, "
                        + "rw_" + slot.suffix + " = NULL, rev_" + slot.suffix + " = NULL, " + slot.atCol + " = now(), error = ?")
                .params(tornId, error, error).update();
    }

    /** Current Elo (null if Torn didn't return it); stamps elo_at either way. */
    public void saveElo(long tornId, Long elo) {
        jdbc.sql("""
                INSERT INTO member_stats (torn_id, elo, elo_at) VALUES (?, ?, now())
                ON CONFLICT (torn_id) DO UPDATE SET elo = EXCLUDED.elo, elo_at = now()""")
                .params(tornId, elo).update();
    }

    /** Members of these factions whose Elo is missing or older than cacheHours. */
    public List<Long> eloNeeds(Collection<Long> factionIds, int cacheHours) {
        if (factionIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT m.torn_id FROM member m LEFT JOIN member_stats s ON s.torn_id = m.torn_id "
                        + "WHERE m.faction_id IN (" + ids(factionIds) + ") " + ACTIVE
                        + "AND (s.elo_at IS NULL OR s.elo_at < now() - make_interval(hours => ?))")
                .param(cacheHours).query(Long.class).list();
    }

    // ---------------------------------------------------------------- FFScouter estimates

    public List<Long> estimateNeeds(Collection<Long> factionIds, int cacheHours) {
        if (factionIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT m.torn_id FROM member m LEFT JOIN stat_estimate e ON e.torn_id = m.torn_id "
                        + "WHERE m.faction_id IN (" + ids(factionIds) + ") " + ACTIVE
                        + "AND (e.fetched_at IS NULL OR e.fetched_at < now() - make_interval(hours => ?))")
                .param(cacheHours).query(Long.class).list();
    }

    /** bsEstimate null = FFScouter has no estimate for this player (cached so we don't re-ask every run). */
    public void saveEstimate(long tornId, Long bsEstimate, Double fairFight, String source, OffsetDateTime updated) {
        jdbc.sql("""
                INSERT INTO stat_estimate (torn_id, bs_estimate, fair_fight, source, estimate_updated, fetched_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (torn_id) DO UPDATE SET bs_estimate = EXCLUDED.bs_estimate, fair_fight = EXCLUDED.fair_fight,
                    source = EXCLUDED.source, estimate_updated = EXCLUDED.estimate_updated, fetched_at = now()""")
                .params(tornId, bsEstimate, fairFight, source, updated).update();
    }

    // ---------------------------------------------------------------- reports

    public List<MemberRow> memberRows(Collection<Long> factionIds) {
        if (factionIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT m.faction_id, f.name AS fname, m.torn_id, m.name, m.level, m.position, m.days_in_faction,
                    m.last_action, s.*, e.bs_estimate, e.source AS bs_source, e.fetched_at AS bs_at
                FROM member m JOIN faction f ON f.id = m.faction_id
                LEFT JOIN member_stats s ON s.torn_id = m.torn_id
                LEFT JOIN stat_estimate e ON e.torn_id = m.torn_id
                WHERE m.faction_id IN (""" + ids(factionIds) + ")" + ACTIVE + "ORDER BY f.sort_order NULLS LAST, m.faction_id, m.name")
                .query(this::mapRow).list();
    }

    // ---------------------------------------------------------------- helpers

    private static String ids(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String normType(String t) {
        return "Revive".equalsIgnoreCase(t) ? "Revive" : "Combat";
    }

    private static Long lng(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private Faction mapFaction(ResultSet rs, int i) throws SQLException {
        return new Faction(rs.getLong("id"), rs.getString("name"), rs.getString("faction_type"),
                rs.getBoolean("is_yaba"), integer(rs, "sort_order"), lng(rs, "leader_id"), rs.getString("leader_name"),
                lng(rs, "co_leader_id"), rs.getString("co_leader_name"), integer(rs, "member_count"),
                integer(rs, "capacity"), rs.getObject("roster_at", OffsetDateTime.class), rs.getString("roster_error"),
                rs.getString("rank_label"), integer(rs, "rank_level"), integer(rs, "territories"),
                rs.getObject("respect") == null ? null : rs.getLong("respect"));
    }

    private MemberRow mapRow(ResultSet rs, int i) throws SQLException {
        Long[][] slots = new Long[3][];
        String[] sfx = {"now", "30", "90"};
        for (int s = 0; s < 3; s++) {
            slots[s] = new Long[]{lng(rs, "xan_" + sfx[s]), lng(rs, "tp_" + sfx[s]), lng(rs, "rw_" + sfx[s]), lng(rs, "rev_" + sfx[s])};
        }
        return new MemberRow(rs.getLong("faction_id"), rs.getString("fname"), rs.getLong(3), rs.getString("name"),
                integer(rs, "level"), rs.getString("position"), integer(rs, "days_in_faction"), rs.getString("last_action"),
                slots[0], slots[1], slots[2], rs.getString("error"), lng(rs, "elo"),
                lng(rs, "bs_estimate"), rs.getString("bs_source"), rs.getObject("bs_at", OffsetDateTime.class));
    }
}
