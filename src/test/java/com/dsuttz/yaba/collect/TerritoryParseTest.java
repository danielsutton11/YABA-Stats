package com.dsuttz.yaba.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerritoryParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void countsTerritories() throws Exception {
        assertEquals(2, CollectorService.territoryCount(mapper.readTree(
                "{\"territory\":[{\"id\":\"ABC\",\"acquired_at\":1},{\"id\":\"DEF\",\"acquired_at\":2}]}")));
        assertEquals(0, CollectorService.territoryCount(mapper.readTree("{\"territory\":[]}")));
        assertEquals(1, CollectorService.territoryCount(mapper.readTree("{\"territory\":{\"ABC\":{}}}")));
        assertNull(CollectorService.territoryCount(mapper.readTree("{}")));
    }
}
