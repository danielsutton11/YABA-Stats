package com.dsuttz.yaba.report;

import com.dsuttz.yaba.store.DataRepository;
import com.dsuttz.yaba.store.DataRepository.MemberRow;
import com.dsuttz.yaba.store.RunRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/** Builds the JSON the sheet draws from: per-member 1M/3M differences plus faction details. */
@Service
public class ReportService {

    public record Period(Long xan, Double act, Long rw, Long rev) { }

    public record Member(long factionId, String factionName, long id, String name, Integer level, String position,
                         Integer daysInFaction, String lastAction, Period m1, Period m3, Long elo,
                         Long bs, String bsSource, String bsAt, String estNote, String note) { }

    public record FactionInfo(long id, String name, String type, boolean yaba, Long leaderId, String leaderName,
                              Long coLeaderId, String coLeaderName, Integer members, Integer capacity,
                              String rosterAt, String rosterError, String rank, Integer rankLevel) { }

    public record Report(String generatedAt, RunRepository.Run lastRun, List<FactionInfo> factions, List<Member> members) { }

    public record ProspectReport(RunRepository.Run run, FactionInfo faction, List<Member> members) { }

    private final DataRepository data;
    private final RunRepository runs;

    public ReportService(DataRepository data, RunRepository runs) {
        this.data = data;
        this.runs = runs;
    }

    public Report yaba() {
        List<DataRepository.Faction> fs = data.yabaFactions();
        List<Long> ids = fs.stream().map(DataRepository.Faction::id).toList();
        String generated = runs.latestCompleteYaba().map(r -> String.valueOf(r.finishedAt())).orElse(null);
        return new Report(generated, runs.latest("yaba").orElse(null),
                fs.stream().map(ReportService::info).toList(),
                data.memberRows(ids).stream().map(ReportService::member).toList());
    }

    public Optional<ProspectReport> prospect(long factionId) {
        return data.faction(factionId).map(f -> new ProspectReport(
                runs.latestProspect(factionId).orElse(null), info(f),
                data.memberRows(List.of(factionId)).stream().map(ReportService::member).toList()));
    }

    static FactionInfo info(DataRepository.Faction f) {
        return new FactionInfo(f.id(), f.name(), f.type(), f.yaba(), f.leaderId(), f.leaderName(), f.coLeaderId(),
                f.coLeaderName(), f.memberCount(), f.capacity(),
                f.rosterAt() == null ? null : f.rosterAt().toString(), f.rosterError(), f.rankLabel(), f.rankLevel());
    }

    static Member member(MemberRow r) {
        boolean hasEst = r.bsEstimate() != null && r.bsEstimate() > 0;
        String estNote = r.bsFetchedAt() != null && !hasEst ? "No FFScouter estimate" : null;
        return new Member(r.factionId(), r.factionName(), r.id(), r.name(), r.level(), r.position(), r.daysInFaction(),
                r.lastAction(), period(r.now(), r.d30()), period(r.now(), r.d90()), r.elo(),
                hasEst ? r.bsEstimate() : null, hasEst ? r.bsSource() : null,
                r.bsFetchedAt() == null ? null : r.bsFetchedAt().toLocalDate().toString(), estNote, r.error());
    }

    /** now − then for xantaken, timeplayed (→ hours), rankedwarhits, revives; null if either side is missing. */
    static Period period(Long[] now, Long[] then) {
        Long xan = diff(now[0], then[0]), tp = diff(now[1], then[1]), rw = diff(now[2], then[2]), rev = diff(now[3], then[3]);
        return new Period(xan, tp == null ? null : Math.round(tp / 360.0) / 10.0, rw, rev);
    }

    private static Long diff(Long a, Long b) {
        return a == null || b == null ? null : Math.max(0, a - b);
    }
}
