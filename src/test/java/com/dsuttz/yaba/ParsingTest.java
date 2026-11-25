package com.dsuttz.yaba;

import com.dsuttz.yaba.collect.CollectorServiceTestAccess;
import com.dsuttz.yaba.report.ReportServiceTestAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractsV2PersonalStatsArray() throws Exception {
        var json = mapper.readTree("""
                {"personalstats":[{"name":"xantaken","value":120,"timestamp":1},{"name":"timeplayed","value":7200},
                 {"name":"revives","value":9}]}""");
        Long[] v = CollectorServiceTestAccess.extract(json);
        assertArrayEquals(new Long[]{120L, 7200L, null, 9L}, v);
    }

    @Test
    void extractsNestedObjectForm() throws Exception {
        var json = mapper.readTree("""
                {"personalstats":{"drugs":{"xantaken":5},"other":{"activity":{"time":1}},"timeplayed":3600,
                 "rankedwarhits":2,"revives":1}}""");
        assertArrayEquals(new Long[]{5L, 3600L, 2L, 1L}, CollectorServiceTestAccess.extract(json));
    }

    @Test
    void parsesMembersArrayAndObject() throws Exception {
        var arr = mapper.readTree("[{\"id\":5,\"name\":\"A\",\"level\":10,\"position\":\"Leader\",\"days_in_faction\":3,"
                + "\"last_action\":{\"relative\":\"1 hour ago\",\"timestamp\":99}}]");
        var obj = mapper.readTree("{\"7\":{\"name\":\"B\",\"level\":2}}");
        assertEquals("1 hour ago", CollectorServiceTestAccess.members(arr).getFirst().lastAction());
        assertEquals(7L, CollectorServiceTestAccess.members(obj).getFirst().id());
    }

    @Test
    void periodDiffsAndHours() {
        var p = ReportServiceTestAccess.period(new Long[]{100L, 36000L, 10L, 5L}, new Long[]{40L, 0L, 10L, null});
        assertEquals(60L, p.xan());
        assertEquals(10.0, p.act());
        assertEquals(0L, p.rw());
        assertNull(p.rev());
    }
}
