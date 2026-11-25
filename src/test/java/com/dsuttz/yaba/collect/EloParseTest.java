package com.dsuttz.yaba.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EloParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void findsEloInV2ArrayAndObjectForms() throws Exception {
        assertEquals(1873L, CollectorService.findStat(mapper.readTree(
                "{\"personalstats\":[{\"name\":\"xantaken\",\"value\":5},{\"name\":\"elo\",\"value\":1873}]}"), "elo"));
        assertEquals(1500L, CollectorService.findStat(mapper.readTree(
                "{\"personalstats\":{\"attacking\":{\"elo\":1500,\"attacks\":{\"won\":3}}}}"), "elo"));
        assertNull(CollectorService.findStat(mapper.readTree("{\"personalstats\":[]}"), "elo"));
    }
}
