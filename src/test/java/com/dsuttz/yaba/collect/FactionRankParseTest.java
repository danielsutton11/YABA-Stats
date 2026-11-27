package com.dsuttz.yaba.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FactionRankParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void formatsRank() throws Exception {
        var r = mapper.readTree("{\"level\":14,\"name\":\"Platinum\",\"division\":2,\"position\":40,\"wins\":12}");
        assertEquals("Platinum II", CollectorService.rankLabel(r));
        assertEquals(14, CollectorService.rankLevel(r));
        var capx = mapper.readTree("{\"basic\":{\"id\":9357,\"name\":\"Capacity X\",\"capacity\":100,\"rank\":{\"level\":21,\"name\":\"Diamond\",\"division\":3,\"position\":17,\"wins\":113}}}").path("basic").path("rank");
        assertEquals("Diamond III", CollectorService.rankLabel(capx));
        assertEquals(21, CollectorService.rankLevel(capx));
        assertEquals("Unranked", CollectorService.rankLabel(mapper.readTree("{\"name\":\"Unranked\",\"division\":0}")));
        assertEquals("Gold", CollectorService.rankLabel(mapper.readTree("\"Gold\"")));
        assertNull(CollectorService.rankLabel(mapper.readTree("{}").path("rank")));
        assertNull(CollectorService.rankLevel(mapper.readTree("{}").path("rank")));
    }
}
