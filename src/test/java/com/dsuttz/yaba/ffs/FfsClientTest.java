package com.dsuttz.yaba.ffs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FfsClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPremiumEstimate() throws Exception {
        var e = FfsClient.parse(mapper.readTree("""
                {"player_id":2675763,"fair_fight":3.12,"bs_estimate":2989885521,"bs_estimate_human":"2.99b",
                 "last_updated":1764096000,"source":"premium",
                 "available_estimates":{"bss":{"bs_estimate":2500000000},"premium":{"bs_estimate":2989885521},"spies":null}}"""));
        assertEquals(2675763L, e.playerId());
        assertEquals(2989885521L, e.bsEstimate());
        assertEquals("premium", e.source());
        assertEquals(3.12, e.fairFight());
        assertNotNull(e.updated());
    }

    @Test
    void missingEstimateIsNull() throws Exception {
        var e = FfsClient.parse(mapper.readTree("{\"player_id\":5,\"fair_fight\":null,\"bs_estimate\":null,\"last_updated\":null}"));
        assertNull(e.bsEstimate());
        assertNull(e.updated());
    }
}
