package com.dsuttz.yaba.collect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Full YABA refresh every night (default 01:00 Europe/London; NIGHTLY_CRON / YABA_ZONE to change). */
@Component
public class NightlySchedule {

    private static final Logger log = LoggerFactory.getLogger(NightlySchedule.class);
    private final CollectorService collector;

    public NightlySchedule(CollectorService collector) {
        this.collector = collector;
    }

    @Scheduled(cron = "${yaba.nightly-cron}", zone = "${yaba.zone}")
    public void nightly() {
        try {
            var run = collector.startYaba(false);
            log.info("Nightly YABA run {} started", run.id());
        } catch (IllegalStateException e) {
            log.warn("Nightly run skipped: {}", e.getMessage());
        }
    }
}
